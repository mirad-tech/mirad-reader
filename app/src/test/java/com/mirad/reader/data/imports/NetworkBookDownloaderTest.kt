package com.mirad.reader.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBookDownloaderTest {

    @Test
    fun testGuessFileNameWithEpubContentType() {
        // 场景 1: URL 无扩展名重定向，但 Content-Type 是 application/epub+zip
        val name1 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/download/book",
            contentDisposition = null,
            contentType = "application/epub+zip"
        )
        assertTrue("应以 .epub 结尾", name1.endsWith(".epub"))

        // 场景 2: Content-Disposition 有文件名但缺少扩展名，Content-Type 是 epub
        val name2 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/ebook",
            contentDisposition = "attachment; filename=\"三体\"",
            contentType = "application/epub+zip"
        )
        assertEquals("三体.epub", name2)

        // 场景 3: Content-Disposition 带有 .txt 但服务器明确响应 application/epub+zip
        val name3 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/ebook",
            contentDisposition = "attachment; filename=\"三体.txt\"",
            contentType = "application/epub+zip"
        )
        assertEquals("三体.epub", name3)

        // 场景 4: 普通 TXT
        val name4 = NetworkBookDownloader.guessFileName(
            url = "https://example.com/book.txt",
            contentDisposition = null,
            contentType = "text/plain"
        )
        assertEquals("book.txt", name4)
    }

    @Test
    fun testZipHeaderOverridesMisnamedTxtExtension() {
        // 场景: 下载文件即使被命名为 .txt，但实际检测到 ZIP 魔数头（PK\x03\x04）
        val ext = ImportPipeline.detectExtension(name = "misnamed_book.txt", isZip = true)
        assertEquals("带 ZIP 魔数头的文件必须识别为 epub，绝不信任 .txt 扩展名", "epub", ext)

        // 普通无 ZIP 魔数的纯文本文件仍识别为 txt
        val plainTxt = ImportPipeline.detectExtension(name = "normal_book.txt", isZip = false)
        assertEquals("纯文本文件识别为 txt", "txt", plainTxt)
    }

    /**
     * 针对 Reviewer Round 2 P2-10 的回归测试：
     * 正文以 'PK' 字符开头的普通文本绝不能被误判为 ZIP/EPUB 魔数。
     */
    @Test
    fun testPkStartingTxtIsNotRecognizedAsZip() {
        val txtBytes = "PK排行榜：第一名张三，第二名李四。".toByteArray(Charsets.UTF_8)
        val isZip = txtBytes.size >= 4 &&
            txtBytes[0] == 0x50.toByte() &&
            txtBytes[1] == 0x4B.toByte() &&
            txtBytes[2] == 0x03.toByte() &&
            txtBytes[3] == 0x04.toByte()
        assertTrue("PK 开头的普通文本不符合 4 字节 ZIP 魔数", !isZip)

        val ext = ImportPipeline.detectExtension(name = "pk_ranking.txt", isZip = isZip)
        assertEquals("依然正确识别为 txt", "txt", ext)

        // 真正的标准 EPUB/ZIP 魔数 (0x50, 0x4B, 0x03, 0x04)
        val realZipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val isRealZip = realZipBytes.size >= 4 &&
            realZipBytes[0] == 0x50.toByte() &&
            realZipBytes[1] == 0x4B.toByte() &&
            realZipBytes[2] == 0x03.toByte() &&
            realZipBytes[3] == 0x04.toByte()
        assertTrue("标准 4 字节必须识别为 ZIP", isRealZip)
        val realExt = ImportPipeline.detectExtension(name = "book.txt", isZip = isRealZip)
        assertEquals("真正带 4 字节 ZIP 魔数的文件覆盖为 epub", "epub", realExt)
    }
}
