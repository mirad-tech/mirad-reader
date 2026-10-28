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

    @Synchronized
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

    /**
     * 与 [bind] / [zipReader] 共用同一把锁：否则 `zipReader()` 先读到非空 `book`、
     * 再等锁时，`release()` 已经把书清空，结果会给一本已关闭的书留下一个打开的 zip 句柄。
     */
    @Synchronized
    fun release() {
        releaseZip()
        book = null
        chapters = emptyList()
    }

    /**
     * Chromium 会在多个线程并发拦截资源请求，而这里只有一份共享的 `ZipFile`。
     * "检查—释放—打开—赋值" 不加锁时，一个线程可能关掉另一个线程正在读的句柄
     * （表现为章节/图片偶发空白），或重复打开造成句柄泄漏。
     */
    @Synchronized
    private fun releaseZip() {
        runCatching { zip?.close() }
        zip = null
        zipPath = null
    }

    @Synchronized
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

        /**
         * 阅读文档的内容安全策略。
         *
         * EPUB 正文是不可信内容，`wrapBody()` 只做包裹、不做消毒，因此首次整章加载时
         * `<script>` / `<iframe>` / `on*` 属性都会以 `mirad.local` 同源身份执行，能直接调用
         * `MiradBridge` 的回调伪造进度与标注。这里用 CSP 在渲染层兜底，与是否消毒无关：
         *
         * - `script-src` 只放行虚拟源里的 reader.js（EPUB 内联脚本无源可用）；
         * - `connect-src` 必须保留本机虚拟源：滚动模式的补章走 `fetch(fragmentUrl)`；
         * - `style-src` 保留 `unsafe-inline`：内置样式、EPUB 自带 `<style>` 与 style 属性都靠它；
         * - 图片/字体允许 `data:`，部分 EPUB 用 data URI 内嵌图片与字体。
         */
        internal val CONTENT_SECURITY_POLICY = listOf(
            "default-src 'none'",
            "script-src $ORIGIN",
            "style-src 'unsafe-inline' $ORIGIN",
            "img-src $ORIGIN data:",
            "font-src $ORIGIN data:",
            "media-src $ORIGIN data:",
            "connect-src $ORIGIN",
            "base-uri 'self'",
            "form-action 'none'",
            "frame-src 'none'",
            "object-src 'none'"
        ).joinToString("; ")

        fun chapterUrl(bookId: Long, chapterIndex: Int): String = "$ORIGIN/book/$bookId/chapter/$chapterIndex"
        fun assetUrl(bookId: Long, zipPath: String): String {
            val encoded = zipPath.split('/').joinToString("/") { Uri.encode(it) }
            return "$ORIGIN/book/$bookId/asset/$encoded"
        }
        fun backgroundUrl(bookId: Long) = "$ORIGIN/book/$bookId/bg"
        fun fontUrl(bookId: Long) = "$ORIGIN/book/$bookId/font"
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
        val stream = context.assets.open("reader/$name")
        return WebResourceResponse(guessMime(name), null, stream)
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
            // 章节片段：滚动模式窗口追加用（只含 <section>，无 head/主题/reader.js）
            "fragment" -> {
                val index = segments.getOrNull(2)?.toIntOrNull() ?: return notFound()
                fragmentResponse(index)
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

    /** 从 assets 读取素材（内置背景 / 内置字体），直接流式返回，避免把几 MB 字体读进内存。 */
    private fun assetResponseOrNull(assetName: String?): WebResourceResponse? {
        if (assetName.isNullOrBlank()) return null
        val stream = runCatching { context.assets.open(assetName) }.getOrNull() ?: return null
        return WebResourceResponse(guessMime(assetName), null, stream)
    }

    private fun chapterResponse(index: Int): WebResourceResponse? {
        val current = book ?: return null
        val chapter = chapters.firstOrNull { it.index == index }
            ?: return chapterError(index, "目录里找不到这一章。")

        val html = when (current.format) {
            BookFormat.EPUB -> {
                val raw = textProvider.readEpubHtml(current, chapter.href ?: return chapterError(index, "这一章没有对应的正文文件。"))
                    ?: return chapterError(index, "正文内容读不出来，文件可能已损坏或缺失。")
                val wrapped = ChapterHtmlBuilder.wrapBody(raw, chapter.index)
                val baseDir = (chapter.href ?: "").substringBeforeLast('/', "")
                val baseHref = assetBaseHref(current.id, baseDir)
                injectHead(wrapped, baseHref)
            }
            else -> {
                // TXT：正文文件缺失时 readTxtText 会静默返回空串（getOrDefault("")），用户只看到一片空白。
                // 先判存在性，给出与 BookMaintenance 一致的"正文文件缺失"提示。
                val contentPath = current.contentPath
                if (contentPath.isNullOrBlank() || !File(contentPath).exists()) {
                    return chapterError(index, "正文文件缺失，建议回书架重新导入这本书。")
                }
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

    /**
     * 章节片段：只返回一个 `<section>`（不含 head / 主题 / reader.js），供滚动模式窗口追加。
     *
     * 与 [chapterResponse] 共用同一份正文与标题行规则，所以片段内的局部文本与整章加载逐字相同；
     * EPUB 的相对资源在此处绝对化，因为窗口文档只有一个 `<base>`。
     */
    private fun fragmentResponse(index: Int): WebResourceResponse? {
        val current = book ?: return null
        val chapter = chapters.firstOrNull { it.index == index } ?: return notFound()
        val html = when (current.format) {
            BookFormat.EPUB -> {
                val raw = textProvider.readEpubHtml(current, chapter.href ?: return notFound())
                    ?: return notFound()
                val baseDir = (chapter.href ?: "").substringBeforeLast('/', "")
                ChapterHtmlBuilder.epubSection(raw, chapter.index) { ref ->
                    com.mirad.reader.core.html.HtmlUrlRewriter.resolveRelative(baseDir, ref)
                        ?.let { assetUrl(current.id, it) }
                }
            }
            else -> {
                val text = textProvider.readTxtText(current, chapter)
                ChapterHtmlBuilder.buildSection(
                    title = chapter.title,
                    bodyText = text,
                    chapterIndex = chapter.index,
                    titleLineChars = chapter.titleLineChars,
                    includeTitleElement = !chapter.isVolume
                )
            }
        }
        return response("text/html", html.toByteArray(Charsets.UTF_8))
    }

    /**
     * 章节读不出来时给用户一个**可读的说明页**。
     * 旧实现返回空 404：WebView 对顶层导航会显示自带的网络错误页，用户只看到空白/错误码，
     * 既不知道发生了什么，也不知道能做什么。
     */
    private fun chapterError(index: Int, reason: String): WebResourceResponse {
        val html = ChapterHtmlBuilder.build(
            title = "无法显示这一章",
            bodyText = "第 ${index + 1} 章读不出来。$reason\n\n" +
                "可以回书架重新导入这本书；如果是文字内容异常，先用「文字乱码？重新识别编码…」检查正文是否完整。",
            chapterIndex = index,
            titleLineChars = 0,
            includeTitleElement = true
        )
        return response("text/html", injectHead(html, null).toByteArray(Charsets.UTF_8))
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
        // 与响应头同源的双保险：不同 WebView 版本对 shouldInterceptRequest 响应头的处理不完全一致，
        // 文档内的 meta 版本在任何版本都生效。策略只有一份常量，两条通道不会漂移。
        // （meta 形式不支持 frame-ancestors / report-uri / sandbox，这里都没用到。）
        extra.append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
            .append(CONTENT_SECURITY_POLICY)
            .append("\">")
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
            responseHeaders = mapOf(
                "Cache-Control" to "no-store",
                // 只挂在文档响应上：reader.js/CSS/图片不需要，挂上也不会错，但这里保持最小面。
                "Content-Security-Policy" to CONTENT_SECURITY_POLICY
            )
        }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))).apply {
            setStatusCodeAndReasonPhrase(404, "Not Found")
        }
}
