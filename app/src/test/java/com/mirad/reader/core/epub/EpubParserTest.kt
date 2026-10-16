package com.mirad.reader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    private fun writeEpub(entries: Map<String, String>): File {
        val file = Files.createTempFile("mirad-epub-", ".epub").toFile()
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            // 规范要求 mimetype 首个且不压缩；解析器不依赖，但保持真实结构
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun epub2Entries(): Map<String, String> = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent(),
        "OEBPS/content.opf" to """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>测试之书</dc:title>
                <dc:creator>测试作者</dc:creator>
                <dc:language>zh-CN</dc:language>
                <dc:publisher>测试出版社</dc:publisher>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
                <item id="css" href="style/main.css" media-type="text/css"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent(),
        "OEBPS/toc.ncx" to """
            <?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="v1" playOrder="1">
                  <navLabel><text>第一卷 少年游</text></navLabel>
                  <content src="text/ch1.xhtml"/>
                  <navPoint id="c1" playOrder="2">
                    <navLabel><text>第一章 出发</text></navLabel>
                    <content src="text/ch1.xhtml#p1"/>
                  </navPoint>
                </navPoint>
                <navPoint id="c2" playOrder="3">
                  <navLabel><text>第二章 相遇</text></navLabel>
                  <content src="text/ch2.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent(),
        "OEBPS/text/ch1.xhtml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head>
            <body><h2 id="p1">第一章 出发</h2><p>他背上行囊，&nbsp;走出了城门。</p>
            <p><img src="../images/cover.jpg" alt="插图"/></p></body></html>
        """.trimIndent(),
        "OEBPS/text/ch2.xhtml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head>
            <body><h2>第二章 相遇</h2><p>她撑着伞，站在雨中。</p></body></html>
        """.trimIndent(),
        "OEBPS/style/main.css" to "body{font-family:serif;}",
        "OEBPS/images/cover.jpg" to "fake-jpeg-binary"
    )

    @Test
    fun parsesEpub2WithNcxToc() {
        val file = writeEpub(epub2Entries())
        val info = EpubParser.parse(file)

        assertEquals("测试之书", info.title)
        assertEquals("测试作者", info.author)
        assertEquals("zh-CN", info.language)
        assertEquals("测试出版社", info.publisher)
        assertEquals("OEBPS/content.opf", info.opfPath)
        assertEquals("OEBPS", info.opfRoot)
        assertEquals(2, info.spine.size)
        assertEquals("OEBPS/text/ch1.xhtml", info.spine[0].href)
        assertEquals("OEBPS/text/ch2.xhtml", info.spine[1].href)
        assertEquals("OEBPS/images/cover.jpg", info.coverHref)
        assertEquals(2, info.toc.size)
        assertEquals("第一卷 少年游", info.toc[0].title)
        assertTrue(info.toc[0].isVolume)
        assertEquals(1, info.toc[0].children.size)
        assertEquals("第一章 出发", info.toc[0].children[0].title)
        assertEquals(0, info.toc[0].children[0].spineIndex)
        assertEquals("p1", info.toc[0].children[0].anchor)
        assertEquals(1, info.toc[1].spineIndex)
        assertEquals("第一章 出发", info.chapterTitles[0])
        assertEquals("第二章 相遇", info.chapterTitles[1])
    }

    @Test
    fun parsesEpub3NavigationDocument() {
        val entries = linkedMapOf(
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent(),
            "EPUB/package.opf" to """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>三体</dc:title><dc:creator>刘慈欣</dc:creator><dc:language>zh</dc:language>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="ch01.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch02.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>
            """.trimIndent(),
            "EPUB/nav.xhtml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>目录</title></head><body>
                <nav epub:type="toc"><ol>
                  <li><a href="ch01.xhtml">第一章 疯狂年代</a>
                    <ol><li><a href="ch01.xhtml#s2">一节</a></li></ol>
                  </li>
                  <li><a href="ch02.xhtml">第二章 寂静的春天</a></li>
                </ol></nav>
                </body></html>
            """.trimIndent(),
            "EPUB/ch01.xhtml" to "<html><body><h1>第一章 疯狂年代</h1></body></html>",
            "EPUB/ch02.xhtml" to "<html><body><h1>第二章 寂静的春天</h1></body></html>",
            "EPUB/cover.png" to "png"
        )
        val info = EpubParser.parse(writeEpub(entries))

        assertEquals("三体", info.title)
        assertEquals("刘慈欣", info.author)
        assertEquals("EPUB/cover.png", info.coverHref)
        assertEquals(2, info.toc.size)
        assertEquals("第一章 疯狂年代", info.toc[0].title)
        assertEquals(1, info.toc[0].children.size)
        assertEquals("第一章 疯狂年代", info.chapterTitles[0])
        assertEquals("第二章 寂静的春天", info.chapterTitles[1])
    }

    @Test
    fun missingContainerThrowsHelpfulError() {
        val file = writeEpub(mapOf("mimetype" to "application/epub+zip"))
        val error = runCatching { EpubParser.parse(file) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is EpubParseException)
        assertTrue(error!!.message!!.contains("container.xml"))
    }

    @Test
    fun pathNormalizationHandlesDotSegmentsAndEncoding() {
        assertEquals("OEBPS/text/ch 1.xhtml", ZipEntryReader.normalize("OEBPS/./text//ch%201.xhtml"))
        assertEquals("a/c.xhtml", ZipEntryReader.normalize("a/b/../c.xhtml"))
        assertEquals("OEBPS/text/ch1.xhtml", ZipEntryReader.resolve("OEBPS", "text/./ch1.xhtml#anchor"))
        assertEquals("OEBPS/ch1.xhtml", ZipEntryReader.resolve("OEBPS/text", "../ch1.xhtml"))
    }

    @Test
    fun spineWithoutTocFallsBackToHeadings() {
        val entries = epub2Entries().toMutableMap()
        entries.remove("OEBPS/toc.ncx")
        entries["OEBPS/content.opf"] = entries["OEBPS/content.opf"]!!.replace("<spine toc=\"ncx\">", "<spine>")
        val info = EpubParser.parse(writeEpub(entries))
        assertTrue(info.toc.isEmpty())
        assertEquals("第一章 出发", info.chapterTitles[0])
        assertEquals("第二章 相遇", info.chapterTitles[1])
    }
}
