package com.mirad.reader.core.epub

import com.mirad.reader.core.text.TextCodec
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

    /**
     * 读取文本条目。
     *
     * [charset] 为 null（默认）时走自适应解码：BOM → 文件内声明 → 严格 UTF-8 → 候选评分。
     * 中文 EPUB 里有大量 XHTML 实际是 GBK/GB18030（即使没有声明），写死 UTF-8 会整段乱码。
     */
    fun readText(
        path: String,
        maxBytes: Int = 2 * 1024 * 1024,
        charset: Charset? = null
    ): String? {
        val bytes = readBytes(path, maxBytes) ?: return null
        if (charset != null) return bytes.toString(charset)
        return TextCodec.decode(bytes, declared = TextCodec.declaredCharset(bytes)).text
    }

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

        /**
         * 仅做 %XX 解码（不把 + 当作空格，路径语义与 URL 查询串不同）。
         *
         * %XX 是**字节**序列，必须按字节累积后整体以 UTF-8 解码：旧实现用 `hex.toChar()`
         * 逐字节转字符，`%E4%B8%AD` 会变成三个 Latin-1 字符，与 zip 里按 UTF-8 存储的
         * 中文条目名对不上 → 中文文件名的 href 静默解析成空章节。
         */
        fun percentDecode(text: String): String {
            if (!text.contains('%')) return text
            val out = StringBuilder(text.length)
            val bytes = java.io.ByteArrayOutputStream(4)
            fun flush() {
                if (bytes.size() > 0) {
                    out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                    bytes.reset()
                }
            }
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                flush()
                out.append(c)
                i++
            }
            flush()
            return out.toString()
        }
    }
}
