package com.mirad.reader.core.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 中文 EPUB 大量存在"XHTML 实际是 GBK、甚至没有声明"的情况。
 * 这些测试直接证明正文能被正确读出来（而不是整段乱码）。
 */
class ZipEntryReaderEncodingTest {

    private val gbk = Charset.forName("GBK")

    private val body = "李慕白提剑站在山巅，望着远处的云海，心中忽然生出无限感慨。"

    private fun epub(xhtml: ByteArray, opfCharset: Charset = Charsets.UTF_8): File {
        val file = File.createTempFile("mirad-enc-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent().toByteArray(opfCharset)
            )
            put(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>编码测试</dc:title><dc:identifier id="id">x</dc:identifier>
                  </metadata>
                  <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """.trimIndent().toByteArray(opfCharset)
            )
            put("OEBPS/ch1.xhtml", xhtml)
        }
        return file
    }

    @Test
    fun gbkXhtmlWithDeclarationIsReadCorrectly() {
        val xhtml = (
            "<?xml version=\"1.0\" encoding=\"gb2312\"?>\n<html><body><p>" +
                body + "</p></body></html>"
            ).toByteArray(gbk)
        val file = epub(xhtml)
        try {
            ZipEntryReader.open(file).use { reader ->
                val text = reader.readText("OEBPS/ch1.xhtml").orEmpty()
                assertTrue("应读出正确中文，实际：$text", text.contains(body))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun gbkXhtmlWithoutDeclarationIsStillReadCorrectly() {
        // 最恶劣的情况：GBK 字节、没有任何编码声明。
        val xhtml = "<html><body><p>$body</p></body></html>".toByteArray(gbk)
        val file = epub(xhtml)
        try {
            ZipEntryReader.open(file).use { reader ->
                val text = reader.readText("OEBPS/ch1.xhtml").orEmpty()
                assertTrue("应读出正确中文，实际：$text", text.contains(body))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun utf8XhtmlStillReadsCorrectly() {
        val xhtml = "<html><body><p>$body</p></body></html>".toByteArray(Charsets.UTF_8)
        val file = epub(xhtml)
        try {
            ZipEntryReader.open(file).use { reader ->
                val text = reader.readText("OEBPS/ch1.xhtml").orEmpty()
                assertEquals(true, text.contains(body))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun explicitCharsetStillHonoured() {
        val xhtml = "<html><body><p>$body</p></body></html>".toByteArray(gbk)
        val file = epub(xhtml)
        try {
            ZipEntryReader.open(file).use { reader ->
                assertEquals(true, reader.readText("OEBPS/ch1.xhtml", charset = gbk).orEmpty().contains(body))
            }
        } finally {
            file.delete()
        }
    }
}
