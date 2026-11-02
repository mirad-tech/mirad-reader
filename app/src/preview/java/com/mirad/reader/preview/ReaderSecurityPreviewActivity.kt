package com.mirad.reader.preview

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.mirad.reader.core.epub.EpubParser
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.repo.ChapterTextProvider
import com.mirad.reader.reader.ReaderJsBridge
import com.mirad.reader.reader.ReaderResourceProvider
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.reader.bindReader
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.json.JSONTokener

/** 合成 EPUB 直达生产 provider/bindReader；不构造 AppContainer、Room 或导入服务。 */
class ReaderSecurityPreviewActivity : Activity() {
    private lateinit var view: ReaderWebView
    private lateinit var resources: ReaderResourceProvider
    private val main = Handler(Looper.getMainLooper())
    private var chapter = 0
    private var mode = "PAGED"
    private var readyCalls = 0
    private var stateCalls = 0
    private var poisonCalls = 0
    private var disposed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode")?.takeIf { it in setOf("PAGED", "ORIGINAL", "WINDOWED") } ?: "PAGED"
        val archive = File(cacheDir, "reader-security-fixture.epub")
        createFixture(archive)
        ZipEntryReader.open(archive).use { check(EpubParser(it).parse().spine.size == 2) }
        val book = BookEntity(id = 1, title = "安全边界合成书", format = BookFormat.EPUB, filePath = archive.absolutePath, totalChapters = 2)
        val chapters = (0..1).map { ChapterEntity(bookId = 1, index = it, title = "合成章节 $it", href = "OPS/chapter$it.xhtml") }
        resources = ReaderResourceProvider(this, ChapterTextProvider()).apply {
            bind(book, chapters)
            updateTheme("body{font-size:18px;background:#faf8f3} #legitimate{color:rgb(12,34,56)!important}", null, null)
        }
        view = ReaderWebView(this)
        val bridge = ReaderJsBridge(
            onReady = { _, _ -> readyCalls++; scheduleDump() },
            onState = { _, _, _, _ -> stateCalls++; scheduleDump() },
            onSelection = { _: Int, _: Int, text: String, _: Int -> if (text == "poison") poisonCalls++ },
            onChapterChanged = { chapter = it; scheduleDump() },
            onRequestNextChapter = { loadChapter(it) }
        )
        view.bindReader(resources, bridge, { ReaderResourceProvider.chapterUrl(1, chapter) }) {
            view.evaluateJavascript("MiradReader.init(${config()});true;", null)
            scheduleDump()
        }
        setContentView(view)
        loadChapter(0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.getStringExtra("action")) {
            "next" -> view.evaluateJavascript("MiradReader.advanceChapterForAuto();", null)
            "scroll-end" -> view.evaluateJavascript("window.scrollTo(0,document.documentElement.scrollHeight);", null)
            "paged" -> { mode = "PAGED"; view.evaluateJavascript("MiradReader.applyConfig(${config()});", null) }
            "reload" -> loadChapter(0)
        }
        scheduleDump()
    }

    private fun config(): String = "{mode:'${if (mode == "PAGED") "PAGED" else "SCROLL"}',anim:'NONE',chapterCount:2,windowedScroll:${mode == "WINDOWED"}}"

    private fun loadChapter(index: Int) {
        chapter = index.coerceIn(0, 1)
        view.loadUrl(ReaderResourceProvider.chapterUrl(1, chapter))
    }

    private fun scheduleDump() {
        if (disposed) return
        main.removeCallbacks(dump)
        main.postDelayed(dump, 250)
    }

    private val dump = Runnable {
        if (!disposed) view.evaluateJavascript("""JSON.stringify({
            url:location.href, reader:!!window.MiradReader,
            state:window.MiradReader?JSON.parse(MiradReader.getState()):null,
            poison:!!(window.__unsafeExternal||window.__unsafeInline||window.__unsafeHandler||window.__unsafeFrame),
            image:document.getElementById('local-image')?document.getElementById('local-image').naturalWidth:0,
            color:document.getElementById('legitimate')?getComputedStyle(document.getElementById('legitimate')).color:null,
            font:Array.from(document.fonts).some(function(f){return f.family==='AuditFont'&&f.status==='loaded'}),
            sections:Array.from(document.querySelectorAll('section.mirad-chapter')).map(function(s){return Number(s.dataset.chapter)}),
            policy:document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.content
        })""") { raw ->
            if (!disposed) {
                val value = JSONTokener(raw).nextValue() as? String
                val state = if (value != null) JSONObject(value) else JSONObject()
                state.put("mode", mode).put("chapter", chapter).put("nativeReadyCalls", readyCalls)
                    .put("nativeStateCalls", stateCalls).put("nativePoisonCalls", poisonCalls)
                    .put("api", android.os.Build.VERSION.SDK_INT)
                File(filesDir, "reader-security-state.json").writeText(state.toString())
            }
        }
    }

    private fun createFixture(target: File) {
        val script = "window.__unsafeExternal=true;MiradBridge.onSelection(777,778,'poison',0);"
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<?xml version='1.0' encoding='UTF-8'?><container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/content.opf'/></rootfiles></container>",
            "OPS/content.opf" to "<?xml version='1.0' encoding='UTF-8'?><package xmlns='http://www.idpf.org/2007/opf' version='3.0'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>合成书</dc:title></metadata><manifest><item id='a' href='chapter0.xhtml' media-type='application/xhtml+xml'/><item id='b' href='chapter1.xhtml' media-type='application/xhtml+xml'/><item id='js' href='proof.js' media-type='application/javascript'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>",
            "OPS/proof.js" to script,
            "OPS/book.css" to "@font-face{font-family:AuditFont;src:url('font.woff2')} #legitimate{font-family:AuditFont;color:red}",
            "OPS/frame.html" to "<script>parent.__unsafeFrame=true;parent.MiradBridge.onSelection(777,778,'poison',0)</script>"
        )
        for (index in 0..1) entries["OPS/chapter$index.xhtml"] = """<!doctype html><html><head><meta charset="utf-8">
            <script src="proof.js"></script><link rel="stylesheet" href="book.css"></head><body>
            <h2>合成章节 $index</h2><p id="legitimate">${"用于验证真实阅读边界的合成正文。".repeat(300)}</p>
            <img id="local-image" src="pixel.png"><script src="./%70roof.js?case=body#fragment"></script>
            <script>window.__unsafeInline=true;MiradBridge.onSelection(777,778,'poison',0)</script>
            <img src="missing.png" onerror="window.__unsafeHandler=true"><iframe src="frame.html"></iframe>
            </body></html>""".trimIndent()
        ZipOutputStream(target.outputStream()).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); crc = CRC32().apply { update(mime) }.value })
            zip.write(mime); zip.closeEntry()
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("OPS/font.woff2")); assets.open("fonts/mirad-reading-sans.woff2").use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry("OPS/pixel.png"))
            zip.write(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", android.util.Base64.DEFAULT))
            zip.closeEntry()
        }
    }

    override fun onDestroy() {
        disposed = true
        main.removeCallbacksAndMessages(null)
        view.removeJavascriptInterface("MiradBridge")
        view.stopLoading(); view.destroy(); resources.release()
        super.onDestroy()
    }
}
