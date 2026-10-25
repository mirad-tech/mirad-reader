package com.mirad.reader.data.imports

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

sealed interface DownloadProgress {
    object Idle : DownloadProgress
    data class Connecting(val url: String) : DownloadProgress
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percent: Float) : DownloadProgress
    data class Success(val file: File, val title: String) : DownloadProgress
    data class Skipped(val title: String, val message: String = "书籍已在书架中，无需重复导入") : DownloadProgress
    data class Error(val message: String) : DownloadProgress
}

/**
 * 网络书源/网址导入下载器：
 * 1. 支持 HTTP/HTTPS 直链下载（TXT / EPUB 文件）；
 * 2. 支持网页智能正文解析（自动抽取标题与正文段落，过滤广告，转换为本地书籍）；
 * 3. 下载完成后直接复用 ImportPipeline 入库。
 */
class NetworkBookDownloader(
    private val context: Context,
    private val importer: BookImporter
) {

    private val _progress = MutableStateFlow<DownloadProgress>(DownloadProgress.Idle)
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    fun resetState() {
        _progress.value = DownloadProgress.Idle
    }

    suspend fun downloadAndImport(urlString: String): Boolean = withContext(Dispatchers.IO) {
        val trimmedUrl = urlString.trim()
        if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
            !trimmedUrl.startsWith("https://", ignoreCase = true)
        ) {
            _progress.value = DownloadProgress.Error("网址格式不正确，必须以 http:// 或 https:// 开头")
            return@withContext false
        }

        _progress.value = DownloadProgress.Connecting(trimmedUrl)

        var connection: HttpURLConnection? = null
        var activeStream: InputStream? = null
        var currentTempFile: File? = null
        var cancelHandle: kotlinx.coroutines.DisposableHandle? = null
        try {
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            cancelHandle = job?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    runCatching { connection?.disconnect() }
                    runCatching { activeStream?.close() }
                }
            }
            var currentUrl = trimmedUrl
            var redirects = 0
            val maxRedirects = 5

            // 只在确定要跳转时才断开旧连接；最终连接保持打开，供后续读取，
            // 否则重定向耗尽后会在已 disconnect 的连接上取 responseCode。
            while (true) {
                val candidate = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    // 读取超时同样作用于响应头，保留正常服务器处理请求的等待时间。
                    // 无数据时取消可能要等当前读取超时，再由协程取消检查收敛。
                    readTimeout = 20000
                    instanceFollowRedirects = false
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    setRequestProperty("Connection", "close")
                }

                val status = candidate.responseCode
                if (status in 301..308 && redirects < maxRedirects) {
                    val location = candidate.getHeaderField("Location")
                    candidate.disconnect()
                    if (location.isNullOrBlank()) break
                    currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                    redirects++
                } else {
                    connection = candidate
                    break
                }
            }

            val conn = connection ?: throw IllegalStateException("重定向过多或缺少跳转目标，无法建立网络连接")
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("服务器响应错误: HTTP ${conn.responseCode}")
            }

            val contentType = conn.contentType?.lowercase().orEmpty()
            val contentDisposition = conn.getHeaderField("Content-Disposition")
            val guessedName = guessFileName(currentUrl, contentDisposition, contentType)

            val downloadDir = File(context.cacheDir, "network_imports").apply { if (!exists()) mkdirs() }

            if (isHtmlPage(currentUrl, contentType)) {
                // 网页长文/小说页智能正文抽取（默认 UTF-8，兼顾 meta charset 声明）；
                // 页面来源不可信，读取必须有上限，防止超大页面直接 OOM。
                val rawBytes = conn.inputStream.use { stream ->
                    activeStream = stream
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    var read: Int
                    while (readChunkWithTimeout(stream, buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        total += read
                        if (total > MAX_HTML_BYTES) {
                            throw IllegalStateException("网页过大（上限 ${MAX_HTML_BYTES / 1024 / 1024}MB），已取消解析")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                currentCoroutineContext().ensureActive()
                val declaredCharset = com.mirad.reader.core.text.TextCodec.declaredCharset(rawBytes)
                val htmlText = com.mirad.reader.core.text.TextCodec.decode(rawBytes, declared = declaredCharset).text
                val parsed = parseWebArticle(htmlText, guessedName)
                if (parsed.content.isBlank()) {
                    throw IllegalStateException("未能解析出网页正文内容")
                }
                val targetFile = File(downloadDir, "${parsed.title.sanitizeFileName()}.txt")
                currentTempFile = targetFile
                targetFile.writeText(parsed.content, Charsets.UTF_8)
                currentCoroutineContext().ensureActive()
                val summary = importer.importAndWait(listOf(ImportSource.FromFile(targetFile)))
                targetFile.delete()
                currentTempFile = null

                if (summary.success > 0) {
                    _progress.value = DownloadProgress.Success(targetFile, parsed.title)
                    return@withContext true
                } else if (summary.skipped > 0) {
                    val msg = summary.messages.firstOrNull() ?: "书籍《${parsed.title}》已在书架中，已跳过"
                    _progress.value = DownloadProgress.Skipped(parsed.title, msg)
                    return@withContext false
                } else {
                    val msg = summary.messages.firstOrNull() ?: "书籍导入失败（文件损坏或格式不支持）"
                    _progress.value = DownloadProgress.Error(msg)
                    return@withContext false
                }
            } else {
                // 直接下载电子书文件
                val targetFile = File(downloadDir, guessedName.sanitizeFileName())
                currentTempFile = targetFile
                val totalBytes = conn.contentLength.toLong()
                if (totalBytes > MAX_FILE_BYTES) {
                    throw IllegalStateException("文件过大（上限 ${MAX_FILE_BYTES / 1024 / 1024}MB），已取消下载")
                }
                var downloaded = 0L
                var lastReportedBytes = -1L

                conn.inputStream.use { input ->
                    activeStream = input
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (readChunkWithTimeout(input, buffer).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            downloaded += read
                            if (downloaded > MAX_FILE_BYTES) {
                                throw IllegalStateException("文件过大（上限 ${MAX_FILE_BYTES / 1024 / 1024}MB），已取消下载")
                            }
                            output.write(buffer, 0, read)
                            // 每 256KB 上报一次：每 8KB 一次会让大文件触发海量无意义重组。
                            if (downloaded - lastReportedBytes >= 256 * 1024L) {
                                lastReportedBytes = downloaded
                                val pct = if (totalBytes > 0) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                                _progress.value = DownloadProgress.Downloading(downloaded, totalBytes, pct)
                            }
                        }
                        output.flush()
                    }
                }
                currentCoroutineContext().ensureActive()

                if (targetFile.length() == 0L) {
                    throw IllegalStateException("下载文件内容为空")
                }

                // 严格通过完整 4 字节魔数核验（标准 ZIP/EPUB 文件头为 0x50, 0x4B, 0x03, 0x04，杜绝以 PK 开头的普通 TXT 误判）
                val isZipHeader = targetFile.length() >= 4 && runCatching {
                    targetFile.inputStream().use { input ->
                        val head = ByteArray(4)
                        val readCount = input.read(head)
                        readCount == 4 &&
                            head[0] == 0x50.toByte() &&
                            head[1] == 0x4B.toByte() &&
                            head[2] == 0x03.toByte() &&
                            head[3] == 0x04.toByte()
                    }
                }.getOrDefault(false)

                val finalFile = if (isZipHeader && !targetFile.name.lowercase().endsWith(".epub")) {
                    val epubFile = File(downloadDir, "${targetFile.nameWithoutExtension}.epub")
                    if (epubFile.exists()) epubFile.delete()
                    if (targetFile.renameTo(epubFile)) epubFile else targetFile
                } else {
                    targetFile
                }
                currentTempFile = finalFile

                val title = finalFile.nameWithoutExtension
                currentCoroutineContext().ensureActive()
                // 等导入把文件复制进书籍目录后再删除缓存源文件，避免 cacheDir 只增不减。
                val summary = importer.importAndWait(listOf(ImportSource.FromFile(finalFile)))
                finalFile.delete()
                currentTempFile = null

                if (summary.success > 0) {
                    _progress.value = DownloadProgress.Success(finalFile, title)
                    return@withContext true
                } else if (summary.skipped > 0) {
                    val msg = summary.messages.firstOrNull() ?: "书籍《$title》已在书架中，已跳过"
                    _progress.value = DownloadProgress.Skipped(title, msg)
                    return@withContext false
                } else {
                    val msg = summary.messages.firstOrNull() ?: "书籍导入失败（文件损坏或格式不支持）"
                    _progress.value = DownloadProgress.Error(msg)
                    return@withContext false
                }
            }
        } catch (ce: CancellationException) {
            _progress.value = DownloadProgress.Idle
            throw ce
        } catch (e: Throwable) {
            if (currentCoroutineContext()[kotlinx.coroutines.Job]?.isCancelled == true) {
                _progress.value = DownloadProgress.Idle
                throw CancellationException("下载已取消", e)
            }
            _progress.value = DownloadProgress.Error(e.message ?: "下载导入失败")
            return@withContext false
        } finally {
            cancelHandle?.dispose()
            runCatching { connection?.disconnect() }
            currentTempFile?.let { runCatching { if (it.exists()) it.delete() } }
        }
    }

    /**
     * 在读取前与读取超时后核验协程取消状态。
     * 阻塞读取受连接的 20 秒超时约束，无数据时取消也可能等待该超时。
     */
    private suspend fun readChunkWithTimeout(input: InputStream, buffer: ByteArray, maxWaitMs: Long = 20000L): Int {
        val startWait = System.currentTimeMillis()
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return input.read(buffer)
            } catch (ste: java.net.SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() - startWait >= maxWaitMs) {
                    throw ste
                }
            }
        }
    }

    private fun isHtmlPage(url: String, contentType: String): Boolean {
        if (contentType.contains("epub") || contentType.contains("application/zip")) return false
        if (contentType.contains("text/html")) return true
        val lowerUrl = url.lowercase()
        return !lowerUrl.endsWith(".txt") && !lowerUrl.endsWith(".epub") && contentType.contains("html")
    }

    companion object {
        /** 电子书单文件下载容量上限，复用 ImportPipeline.MAX_TXT_BYTES (80MB) */
        internal val MAX_FILE_BYTES: Long = ImportPipeline.MAX_TXT_BYTES

        /** HTML 页面读取上限：页面来源不可信，防超大页面 OOM。 */
        internal const val MAX_HTML_BYTES: Int = 8 * 1024 * 1024

        internal fun guessFileName(url: String, contentDisposition: String?, contentType: String = ""): String {
            val isEpubType = contentType.contains("epub")
            if (!contentDisposition.isNullOrBlank()) {
                val filenameRegex = Regex("""filename\*?=['"]?(?:UTF-8'')?([^'";\n]+)['"]?""", RegexOption.IGNORE_CASE)
                filenameRegex.find(contentDisposition)?.groupValues?.get(1)?.let { raw ->
                    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                    if (decoded.isNotBlank()) {
                        return if (isEpubType && !decoded.lowercase().endsWith(".epub")) {
                            "${decoded.substringBeforeLast('.')}.epub"
                        } else decoded
                    }
                }
            }
            val cleanPath = url.substringBefore('?').substringBefore('#')
            val lastSegment = cleanPath.substringAfterLast('/')
            if (lastSegment.isNotBlank() && (lastSegment.endsWith(".txt", true) || lastSegment.endsWith(".epub", true))) {
                val decoded = runCatching { URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
                return if (isEpubType && !decoded.lowercase().endsWith(".epub")) {
                    "${decoded.substringBeforeLast('.')}.epub"
                } else decoded
            }
            val ext = if (isEpubType) "epub" else "txt"
            return "在线导入书籍_${System.currentTimeMillis()}.$ext"
        }
    }

    private data class ParsedArticle(val title: String, val content: String)

    /**
     * 轻量启发式网页长文/小说抽取：提取正文、清洗广告、过滤非正文标签。
     */
    private fun parseWebArticle(html: String, fallbackTitle: String): ParsedArticle {
        // 1. 抽取标题
        val titleRegex = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
        val rawTitle = titleRegex.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val cleanTitle = rawTitle
            .replace(Regex("[_\\-|].*?(小说|阅读|无弹窗|笔趣阁|书院|全文).*"), "")
            .trim()
            .ifBlank { fallbackTitle.substringBeforeLast('.') }

        // 2. 剔除 script, style, nav, footer, header
        var sanitized = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
            .replace(Regex("(?is)<header[^>]*>.*?</header>"), "")
            .replace(Regex("(?is)<footer[^>]*>.*?</footer>"), "")
            .replace(Regex("(?is)<nav[^>]*>.*?</nav>"), "")

        // 3. 提取段落内容
        val pRegex = Regex("<p[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE)
        val paragraphs = mutableListOf<String>()
        pRegex.findAll(sanitized).forEach { match ->
            val pText = match.groupValues[1]
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .trim()
            if (pText.length > 5 && !pText.contains("广告") && !pText.contains("点击下载")) {
                paragraphs.add(pText)
            }
        }

        val body = if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n\n")
        } else {
            // 兜底去除所有标签
            sanitized.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .trim()
        }

        val finalContent = "$cleanTitle\n\n$body"
        return ParsedArticle(cleanTitle, finalContent)
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
}
