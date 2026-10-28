package com.mirad.reader.data.downloads

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.sources.SourceCatalog
import com.mirad.reader.data.imports.ImportPipeline
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 以原目录生成 EPUB3；正文只写转义后的文本，不让远端 HTML 带入阅读器。 */
internal object DownloadedEpubWriter {
    fun write(
        target: File,
        taskId: String,
        catalog: SourceCatalog,
        readChapter: (Int) -> String,
        checkCancelled: () -> Unit = {}
    ) {
        require(catalog.chapters.isNotEmpty()) { "目录为空" }
        val pending = File(target.parentFile, target.name + ".new")
        try {
            FileOutputStream(pending).use { file ->
                ZipOutputStream(file).use { zip ->
                    fun entry(name: String, text: String, stored: Boolean = false) {
                        checkCancelled()
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        val limit = if (name == "OEBPS/nav.xhtml") 2 * 1024 * 1024 else ZipEntryReader.MAX_CHAPTER_BYTES
                        require(bytes.size <= limit) { "目录或章节超过阅读器大小上限" }
                        val item = ZipEntry(name).apply {
                            time = 0L
                            if (stored) {
                                method = ZipEntry.STORED
                                size = bytes.size.toLong()
                                compressedSize = size
                                crc = CRC32().also { it.update(bytes) }.value
                            }
                        }
                        zip.putNextEntry(item)
                        zip.write(bytes)
                        zip.closeEntry()
                        check(pending.length() <= ImportPipeline.MAX_TXT_BYTES) { "整本文件超过 80MB 导入上限" }
                    }
                    entry("mimetype", "application/epub+zip", stored = true)
                    entry("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                    val chapters = catalog.chapters
                    val manifest = chapters.indices.joinToString("") { "<item id=\"c$it\" href=\"chapter-$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }
                    val spine = chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
                    val book = catalog.book
                    entry("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:$taskId</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:creator>${xml(book.author)}</dc:creator><dc:language>zh</dc:language><dc:source>${xml(book.bookUrl)}</dc:source><dc:description>${xml(book.intro)}</dc:description><meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""")
                    val links = chapters.mapIndexed { index, chapter -> "<li><a href=\"chapter-$index.xhtml\">${xml(chapter.title)}</a></li>" }.joinToString("")
                    entry("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>$links</ol></nav></body></html>""")
                    chapters.forEachIndexed { index, chapter ->
                        require(!chapter.restricted && !chapter.isVolume) { "目录含受限章节或卷标题" }
                        val text = readChapter(index)
                        require(text.isNotBlank()) { "第 ${index + 1} 章正文为空" }
                        val paragraphs = text.lineSequence().filter { it.isNotBlank() }.joinToString("") { "<p>${xml(it)}</p>" }
                        val html = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${xml(chapter.title)}</title></head><body><h1>${xml(chapter.title)}</h1>$paragraphs</body></html>"""
                        require(html.toByteArray(Charsets.UTF_8).size <= ZipEntryReader.MAX_CHAPTER_BYTES) { "第 ${index + 1} 章超过阅读器 8MB 上限" }
                        entry("OEBPS/chapter-$index.xhtml", html)
                    }
                    zip.finish()
                    file.fd.sync()
                }
            }
            check(pending.length() <= ImportPipeline.MAX_TXT_BYTES) { "整本文件超过 80MB 导入上限" }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c == '\n' || c == '\r' || c == '\t' || (c >= ' ' && c != '\uFFFE' && c != '\uFFFF')) append(c)
            }
        }
    }
}
