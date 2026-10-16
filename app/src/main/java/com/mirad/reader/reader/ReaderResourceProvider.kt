package com.mirad.reader.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.ChapterHtmlBuilder
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.repo.ChapterTextProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * 阅读器资源拦截器：所有章节/图片/字体都通过内存响应返回，WebView 不接触网络与文件系统。
 *
 * 虚拟源约定：
 *   https://mirad.local/reader/…              应用内置 reader.css / reader.js
 *   https://mirad.local/book/<id>/chapter/<i> 章节 HTML（TXT 生成、EPUB 注入后返回）
 *   https://mirad.local/book/<id>/asset/<p>   EPUB zip 内资源（图片 / CSS / 字体）
 *   https://mirad.local/book/<id>/cover       封面
 *   https://mirad.local/book/<id>/bg          背景图片
 *   https://mirad.local/book/<id>/font        自定义字体
 */
class ReaderResourceProvider(
    private val context: Context,
    private val textProvider: ChapterTextProvider
) {

    private var book: BookEntity? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var zip: ZipEntryReader? = null
    private var zipPath: String? = null

    @Volatile
    private var themeCss: String = ""

    @Volatile
    private var backgroundUrl: String? = null

    @Volatile
    private var customFontUrl: String? = null

    /** 背景图片的本地绝对路径（由阅读器按当前配置注入）。 */
    @Volatile
    var backgroundImagePath: String? = null

    /** 内置背景素材（assets 内相对路径，如 backgrounds/paper.jpg）。 */
    @Volatile
    var backgroundAssetName: String? = null

    /** 自定义字体的本地绝对路径。 */
    @Volatile
    var fontFilePath: String? = null

    /** 内置字体（assets 内相对路径，如 fonts/霞鹜文楷.ttf）。 */
    @Volatile
    var fontAssetName: String? = null

    fun bind(book: BookEntity, chapters: List<ChapterEntity>) {
        this.book = book
        this.chapters = chapters
        if (book.format != BookFormat.EPUB) {
            releaseZip()
        }
    }

    fun updateTheme(css: String, backgroundUrl: String?, customFontUrl: String?) {
        this.themeCss = css
        this.backgroundUrl = backgroundUrl
        this.customFontUrl = customFontUrl
    }

    /** 当前主题样式表（阅读中改设置时注入到已打开的页面）。 */
    fun currentThemeCss(): String = themeCss

    fun release() {
        releaseZip()
        book = null
        chapters = emptyList()
    }

    private fun releaseZip() {
        runCatching { zip?.close() }
        zip = null
        zipPath = null
    }

    private fun zipReader(): ZipEntryReader? {
        val current = book ?: return null
        if (current.format != BookFormat.EPUB) return null
        val path = current.filePath
        if (zip == null || zipPath != path) {
            releaseZip()
            zip = runCatching { ZipEntryReader.open(File(path)) }.getOrNull()
            zipPath = path
        }
        return zip
    }

    companion object {
        const val HOST = "mirad.local"
        const val ORIGIN = "https://$HOST"

        fun chapterUrl(bookId: Long, chapterIndex: Int): String = "$ORIGIN/book/$bookId/chapter/$chapterIndex"
        fun assetUrl(bookId: Long, zipPath: String): String {
            val encoded = zipPath.split('/').joinToString("/") { Uri.encode(it) }
            return "$ORIGIN/book/$bookId/asset/$encoded"
        }
        fun backgroundUrl(bookId: Long) = "$ORIGIN/book/$bookId/bg"
        fun fontUrl(bookId: Long) = "$ORIGIN/book/$bookId/font"
        fun coverUrl(bookId: Long) = "$ORIGIN/book/$bookId/cover"
    }

    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.host != HOST) return null
        val path = uri.path ?: return null
        return try {
            when {
                path.startsWith("/reader/") -> assetResponse(path.removePrefix("/reader/"))
                path.startsWith("/book/") -> bookResponse(path, uri)
                else -> notFound()
            }
        } catch (t: Throwable) {
            notFound()
        }
    }

    private fun assetResponse(name: String): WebResourceResponse {
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        val bytes = context.assets.open("reader/$name").use { it.readBytes() }
        return response(mime, bytes)
    }

    private fun bookResponse(path: String, uri: Uri): WebResourceResponse? {
        val current = book ?: return notFound()
        val segments = path.removePrefix("/book/").split('/')
        val bookId = segments.getOrNull(0)?.toLongOrNull() ?: return notFound()
        if (bookId != current.id) return notFound()
        val kind = segments.getOrNull(1) ?: return notFound()
        return when (kind) {
            "chapter" -> {
                val index = segments.getOrNull(2)?.toIntOrNull() ?: return notFound()
                chapterResponse(index)
            }
            "asset" -> {
                val zipPath = Uri.decode(segments.drop(2).joinToString("/"))
                assetFromZip(zipPath)
            }
            "cover" -> fileResponse(current.coverPath)
            "bg" -> assetResponseOrNull(backgroundAssetName) ?: fileResponse(backgroundImagePath)
            "font" -> assetResponseOrNull(fontAssetName) ?: fileResponse(fontFilePath)
            else -> notFound()
        }
    }

    /** 从 assets 读取素材（内置背景 / 内置字体）。 */
    private fun assetResponseOrNull(assetName: String?): WebResourceResponse? {
        if (assetName.isNullOrBlank()) return null
        val bytes = runCatching { context.assets.open(assetName).use { it.readBytes() } }.getOrNull()
            ?: return null
        return WebResourceResponse(guessMime(assetName), null, ByteArrayInputStream(bytes))
    }

    private fun chapterResponse(index: Int): WebResourceResponse? {
        val current = book ?: return null
        val chapter = chapters.firstOrNull { it.index == index } ?: return null

        val html = when (current.format) {
            BookFormat.EPUB -> {
                val raw = textProvider.readEpubHtml(current, chapter.href ?: return null)
                    ?: return notFound()
                val wrapped = ChapterHtmlBuilder.wrapBody(raw, chapter.index)
                val baseDir = (chapter.href ?: "").substringBeforeLast('/', "")
                val baseHref = assetBaseHref(current.id, baseDir)
                injectHead(wrapped, baseHref)
            }
            else -> {
                val text = textProvider.readTxtText(current, chapter)
                val built = ChapterHtmlBuilder.build(
                    title = chapter.title,
                    bodyText = text,
                    chapterIndex = chapter.index,
                    titleLineChars = chapter.titleLineChars,
                    includeTitleElement = !chapter.isVolume
                )
                injectHead(built, null)
            }
        }
        return response("text/html", html.toByteArray(Charsets.UTF_8))
    }

    private fun assetBaseHref(bookId: Long, dir: String): String {
        val suffix = if (dir.isBlank()) "" else dir.split('/').joinToString("/") { Uri.encode(it) } + "/"
        return "$ORIGIN/book/$bookId/asset/$suffix"
    }

    private fun assetFromZip(zipPath: String): WebResourceResponse? {
        val reader = zipReader() ?: return notFound()
        val stream: InputStream = reader.openStream(zipPath) ?: return notFound()
        return WebResourceResponse(guessMime(zipPath), null, stream)
    }

    private fun fileResponse(path: String?): WebResourceResponse? {
        if (path.isNullOrBlank()) return notFound()
        val file = File(path)
        if (!file.exists()) return notFound()
        return WebResourceResponse(guessMime(path), null, file.inputStream())
    }
    private fun injectHead(html: String, baseHref: String?): String {
        val extra = StringBuilder(512)
        extra.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">")
        if (!baseHref.isNullOrBlank()) {
            extra.append("<base href=\"").append(baseHref).append("\">")
        }
        extra.append("<link rel=\"stylesheet\" href=\"").append(ORIGIN).append("/reader/reader.css\">")
        // 主题样式放在带 id 的 style 里，阅读中改设置时由 reader.js 直接替换内容，无需重载章节
        extra.append("<style id=\"mirad-theme\">").append(themeCss).append("</style>")
        extra.append("<script src=\"").append(ORIGIN).append("/reader/reader.js\"></script>")

        val headClose = Regex("(?is)</head\\s*>").find(html)
        if (headClose != null) {
            return html.substring(0, headClose.range.first) + extra + html.substring(headClose.range.first)
        }
        val headOpen = Regex("(?is)<head\\b[^>]*>").find(html)
        if (headOpen != null) {
            val at = headOpen.range.last + 1
            return html.substring(0, at) + extra + html.substring(at)
        }
        val htmlOpen = Regex("(?is)<html\\b[^>]*>").find(html)
        if (htmlOpen != null) {
            val at = htmlOpen.range.last + 1
            return html.substring(0, at) + "<head>" + extra + "</head>" + html.substring(at)
        }
        return "<head>" + extra + "</head>" + html
    }

    private fun guessMime(path: String?): String = when (path?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "xhtml", "html", "htm" -> "text/html"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun response(mime: String, bytes: ByteArray): WebResourceResponse =
        WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store")
        }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))).apply {
            setStatusCodeAndReasonPhrase(404, "Not Found")
        }
}
