package com.mirad.reader.core.epub

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * zip 内条目读取器：统一路径规范化（反斜杠、./、//、%XX、大小写）。
 */
class ZipEntryReader(private val zip: ZipFile) : java.io.Closeable {

    private val index: LinkedHashMap<String, ZipEntry> = LinkedHashMap()
    private val lowerIndex: LinkedHashMap<String, ZipEntry> = LinkedHashMap()

    init {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory) continue
            val key = normalize(e.name)
            index.putIfAbsent(key, e)
            lowerIndex.putIfAbsent(key.lowercase(), e)
        }
    }

    fun entry(path: String): ZipEntry? {
        val key = normalize(path)
        return index[key] ?: lowerIndex[key.lowercase()]
    }

    fun contains(path: String): Boolean = entry(path) != null

    fun names(): Set<String> = index.keys

    /** 只读流，调用方负责关闭；适合大文件（封面、图片）。 */
    fun openStream(path: String): InputStream? = entry(path)?.let { openStream(it) }

    fun openStream(entry: ZipEntry): InputStream? = runCatching { zip.getInputStream(entry) }.getOrNull()

    fun sizeOf(path: String): Long = entry(path)?.size ?: -1L

    fun readBytes(path: String, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray? {
        val e = entry(path) ?: return null
        return runCatching {
            zip.getInputStream(e).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    fun readText(
        path: String,
        maxBytes: Int = 2 * 1024 * 1024,
        charset: Charset = Charsets.UTF_8
    ): String? = readBytes(path, maxBytes)?.toString(charset)

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 24 * 1024 * 1024

        fun open(file: File): ZipEntryReader = ZipEntryReader(ZipFile(file))

        /** 规范化 zip 内路径。 */
        fun normalize(path: String): String {
            var p = path.replace('\\', '/').trim()
            p = percentDecode(p)
            p = p.removePrefix("/")
            val parts = p.split('/')
            val stack = ArrayList<String>(parts.size)
            for (part in parts) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    else -> stack.add(part)
                }
            }
            return stack.joinToString("/")
        }

        /** 相对路径解析：以 baseDir 为基准解析 href，去掉锚点。 */
        fun resolve(baseDir: String, href: String): String {
            val noFragment = href.substringBefore('#')
            val joined = if (baseDir.isBlank()) noFragment else "$baseDir/$noFragment"
            return normalize(joined)
        }

        /** 仅做 %XX 解码（不把 + 当作空格，路径语义与 URL 查询串不同）。 */
        fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.append(hex.toChar())
                        i += 3
                        continue
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
    }
}
