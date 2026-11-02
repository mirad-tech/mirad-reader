package com.mirad.reader.reader

import android.content.ContextWrapper
import com.mirad.reader.core.html.ChapterHtmlBuilder
import com.mirad.reader.data.repo.ChapterTextProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** 直接执行生产 HTML 装配；ContextWrapper 仅是 JVM 的 Android 边界替身。 */
class ReaderDocumentSecurityTest {
    private val origin = ReaderResourceProvider.ORIGIN
    private val base = "$origin/book/1/asset/OPS/"
    private val theme = "body { background-color:rgb(250,248,243); } #legitimate { color:rgb(12,34,56) !important; }"

    private fun document(raw: String, chapterIndex: Int = 0): String {
        val provider = ReaderResourceProvider(ContextWrapper(null), ChapterTextProvider())
        provider.updateTheme(theme, null, null)
        val method = ReaderResourceProvider::class.java.getDeclaredMethod("injectHead", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(provider, ChapterHtmlBuilder.wrapBody(raw, chapterIndex), base) as String
    }

    private fun assertPolicyFirst(raw: String) {
        val parsed = Jsoup.parse(document(raw))
        val first = parsed.head().children().first()!!
        assertEquals("首个实际 head 元素必须先建立 CSP", "meta", first.tagName())
        assertEquals("Content-Security-Policy", first.attr("http-equiv"))
        assertEquals(ReaderResourceProvider.CONTENT_SECURITY_POLICY, first.attr("content"))
        assertNotNull(parsed.selectFirst("script[src='$origin/reader/reader.js']"))
    }

    @Test fun policyPrecedesScriptsInTheOriginalHead() = assertPolicyFirst(cases().getValue("head-script"))
    @Test fun commentWithAFakeHeadCannotCaptureThePolicy() = assertPolicyFirst(cases().getValue("comment-head"))
    @Test fun aMissingHeadStillHasAnEarlyPolicy() = assertPolicyFirst(cases().getValue("missing-head"))
    @Test fun aScriptBeforeTheHtmlTagCannotPrecedeThePolicy() = assertPolicyFirst(cases().getValue("before-html"))

    @Test fun ordinaryTextBookClassesAndThemePrecedenceArePreserved() {
        val raw = "<!doctype html><html lang='zh'><head><style id='book-style'>#legitimate { color:red; }</style></head>" +
            "<body class='book-style'><pre id='legitimate'> A\n   B &amp; C </pre><img src='pixel.png'></body></html>"
        val parsed = Jsoup.parse(document(raw))
        assertEquals("book-style", parsed.body().className())
        assertEquals("zh", parsed.selectFirst("html")!!.attr("lang"))
        assertEquals(" A\n   B & C ", parsed.getElementById("legitimate")!!.wholeText())
        assertEquals(base, parsed.selectFirst("base")!!.attr("href"))
        assertEquals("pixel.png", parsed.selectFirst("img")!!.attr("src"))
        val head = parsed.head().children().toList()
        assertTrue(head.indexOf(parsed.getElementById("book-style")) < head.indexOf(parsed.getElementById("mirad-theme")))
        assertEquals(theme, parsed.getElementById("mirad-theme")!!.data())
        assertNotNull("保留标准模式的 doctype", parsed.documentType())
    }

    @Test fun preformattedCharacterStreamsMatchTheOriginalAndWindowFragments() {
        compatibilityCases().forEach { (name, raw) ->
            val expected = Jsoup.parse(raw).getElementById("whitespace")!!.wholeText()
            assertEquals(name, expected, Jsoup.parse(document(raw)).getElementById("whitespace")!!.wholeText())
            val fragment = ChapterHtmlBuilder.epubSection(raw, 0) { it }
            assertEquals("fragment/$name", expected, Jsoup.parse(fragment).getElementById("whitespace")!!.wholeText())
        }
    }

    @Test fun theBookAssetBasePrecedesRelativeStylesAndFonts() {
        val html = document(chapter(0))
        val head = Jsoup.parse(html).head().children().toList()
        val baseIndex = head.indexOfFirst { it.tagName() == "base" && it.attr("href") == base }
        val bookStyleIndex = head.indexOfFirst { it.id() == "book-style" }
        val bookCssIndex = head.indexOfFirst { it.tagName() == "link" && it.attr("href") == "book.css" }
        assertTrue(baseIndex >= 0 && baseIndex < bookStyleIndex && baseIndex < bookCssIndex)
    }

    /** 只有显式提供本轮目录时导出，浏览器必须使用这些真实生产输出并核对源码摘要。 */
    @Test fun exportsCurrentProductionDocumentsForChromiumWhenRequested() {
        val destination = System.getenv("MIRAD_CSP_FIXTURE_DIR") ?: return
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main/reader/reader.js").isFile }
        val sources = listOf(
            "app/src/main/java/com/mirad/reader/reader/ReaderResourceProvider.kt",
            "app/src/main/java/com/mirad/reader/core/html/ChapterHtmlBuilder.kt"
        )
        val payload = buildJsonObject {
            put("policy", ReaderResourceProvider.CONTENT_SECURITY_POLICY)
            put("origin", origin)
            put("sourceHashes", buildJsonObject {
                sources.forEach { path ->
                    val hash = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                        .joinToString("") { "%02x".format(it) }
                    put(path, hash)
                }
            })
            put("documents", buildJsonArray {
                cases().forEach { (name, raw) -> add(buildJsonObject { put("name", name); put("html", document(raw)) }) }
            })
            put("nextChapter", document(chapter(1), 1))
            put("nextFragment", ChapterHtmlBuilder.epubSection(chapter(1), 1) { "$base$it" })
            put("compatibilityDocuments", buildJsonArray {
                compatibilityCases().forEach { (name, raw) -> add(buildJsonObject {
                    put("name", name); put("raw", raw); put("html", document(raw))
                    put("fragment", ChapterHtmlBuilder.epubSection(raw, 0) { it })
                }) }
            })
        }
        File(destination).mkdirs()
        File(destination, "documents.json").writeText(payload.toString())
    }

    private fun chapter(index: Int): String = "<!doctype html><html><head><meta charset='utf-8'>" +
        "<style id='book-style'>#legitimate { font-family: AuditFont; } @font-face { font-family: AuditFont; src:url('font.woff2'); }</style>" +
        "<link rel='stylesheet' href='book.css'></head><body class='book-style'>" +
        "<h2>合成章节 $index</h2><p id='legitimate'>" + "用于验证分页、样式与跨章的正文。".repeat(300) + "</p>" +
        "<img id='local-image' src='pixel.png'><script src='proof.js'></script>" +
        "<script>window.__unsafeInline = true; MiradBridge.onSelection(777,778,'poison',0);</script>" +
        "<img src='missing.png' onerror=\"window.__unsafeHandler=true\">" +
        "<iframe src='frame.html'></iframe><iframe srcdoc=\"<script>parent.__unsafeFrame=true</script>\"></iframe>" +
        "</body></html>"

    private fun cases(): Map<String, String> {
        val main = chapter(0)
        return linkedMapOf(
            "body-script" to main,
            "head-script" to main.replace("<head>", "<head><ScRiPt src=proof.js></ScRiPt>"),
            "comment-head" to main.replace("<html>", "<!-- <head> false head </head> --><html>")
                .replace("<head>", "<head><script src='proof.js?head=1'></script>"),
            "missing-head" to main.replace("<head>", "").replace("</head>", ""),
            "before-html" to main.replace("<html>", "<script src='${base}proof.js'></script><html>"),
            "encoded-script" to main.replace("src='proof.js'", "src='&#112;roof.js?variant=1#suffix'"),
            "absolute-script" to main.replace("src='proof.js'", "src='${base}proof.js'"),
            "protocol-relative" to main.replace("src='proof.js'", "src='//mirad.local/book/1/asset/OPS/proof.js'"),
            "module-svg" to main.replace("</body>", "<script type='module' src='proof.js'></script>" +
                "<svg><script>window.__unsafeSvg=true</script></svg></body>")
        )
    }

    private fun compatibilityCases(): Map<String, String> = buildMap {
        val text = listOf("\nA", "\n\nA", "\n\n\nA", "&#10;A", "&#10;&#10;A",
            "\r\n\r\nA", "<!--c-->\nA", " A\nB", "\n\r\nA", "\n&#10;A")
        for (tag in listOf("pre", "listing", "textarea")) text.forEachIndexed { index, value ->
            put("$tag-$index", "<!doctype html><html><head></head><body><$tag id='whitespace'>$value</$tag></body></html>")
        }
    }
}
