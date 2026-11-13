package com.mirad.reader.core.epub

import com.mirad.reader.core.text.TextCodec
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * zip 内条目读取器：统一路径规范化（反斜杠、./、//、%XX、大小写），
 * 同时支持本地 [File] 与基于 [FileChannel]（如 SAF `ParcelFileDescriptor`）的零拷贝随机访问。
 */
class ZipEntryReader private constructor(private val backend: Backend) : Closeable {

    constructor(zip: ZipFile) : this(ZipFileBackend(zip))

    private interface EntryHandle {
        val name: String
        val isDirectory: Boolean
        val size: Long
    }

    private interface Backend : Closeable {
        fun entries(): Sequence<EntryHandle>
        fun openStream(entry: EntryHandle): InputStream?
    }

    private val index: LinkedHashMap<String, EntryHandle> = LinkedHashMap()
    private val lowerIndex: LinkedHashMap<String, EntryHandle> = LinkedHashMap()

    init {
        for (e in backend.entries()) {
            if (e.isDirectory) continue
            val key = normalize(e.name)
            index.putIfAbsent(key, e)
            lowerIndex.putIfAbsent(key.lowercase(), e)
        }
    }

    private fun findEntry(path: String): EntryHandle? {
        val key = normalize(path)
        return index[key] ?: lowerIndex[key.lowercase()]
    }

    fun contains(path: String): Boolean = findEntry(path) != null

    fun names(): Set<String> = index.keys

    /** 只读流，调用方负责关闭；适合大文件（封面、图片）。 */
    fun openStream(path: String): InputStream? = findEntry(path)?.let { backend.openStream(it) }

    fun sizeOf(path: String): Long = findEntry(path)?.size ?: -1L

    /**
     * 读取条目字节。
     *
     * 超过 [maxBytes] 时**返回 null 而不是半截数据**：旧实现是边读边 `break`，
     * 返回值与完整内容无法区分，于是被截断的 XHTML 会被当成完整正文——
     * 截断点常落在多字节 UTF-8 中间，严格解码失败后退到候选评分，可能整章判成 GB18030 而全篇乱码。
     * 先用 `entry.size` 预检，再在读取过程中兜住"声明大小不可信"的情况。
     */
    fun readBytes(path: String, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray? {
        val e = findEntry(path) ?: return null
        if (e.size > maxBytes) return null
        return runCatching {
            val raw = backend.openStream(e) ?: return null
            raw.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (out.size() + n > maxBytes) return@use null
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
        runCatching { backend.close() }
    }

    private class ZipFileEntry(val delegate: ZipEntry) : EntryHandle {
        override val name: String get() = delegate.name
        override val isDirectory: Boolean get() = delegate.isDirectory
        override val size: Long get() = delegate.size
    }

    private class ZipFileBackend(private val zip: ZipFile) : Backend {
        override fun entries(): Sequence<EntryHandle> = sequence {
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                yield(ZipFileEntry(enumeration.nextElement()))
            }
        }

        override fun openStream(entry: EntryHandle): InputStream? =
            runCatching { zip.getInputStream((entry as ZipFileEntry).delegate) }.getOrNull()

        override fun close() {
            zip.close()
        }
    }

    /**
     * 基于 [FileChannel] 无状态定位读（pread）的随机访问 ZIP 后端：
     * 仅解析末尾 Central Directory 建立内存目录，按需定位解压条目，无需把外部 EPUB 复制到私有目录。
     */
    private class ChannelEntry(
        override val name: String,
        override val isDirectory: Boolean,
        override val size: Long,
        val compressedSize: Long,
        val method: Int,
        val localHeaderOffset: Long
    ) : EntryHandle

    private class ChannelZipBackend(
        private val channel: FileChannel,
        private val owner: Closeable?
    ) : Backend {

        private val parsedEntries: List<ChannelEntry> = parseCentralDirectory(channel)

        override fun entries(): Sequence<EntryHandle> = parsedEntries.asSequence()

        override fun openStream(entry: EntryHandle): InputStream? = runCatching {
            val item = entry as ChannelEntry
            val localHeader = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            readFullyAt(channel, localHeader, item.localHeaderOffset)
            localHeader.flip()
            if (localHeader.int != LOCAL_HEADER_SIG) throw ZipException("损坏的 ZIP 本地文件头")
            val nameLen = localHeader.getShort(26).toInt() and 0xFFFF
            val extraLen = localHeader.getShort(28).toInt() and 0xFFFF
            val dataOffset = item.localHeaderOffset + 30L + nameLen + extraLen
            val raw = BoundedChannelInputStream(channel, dataOffset, item.compressedSize)
            when (item.method) {
                METHOD_STORED -> raw
                METHOD_DEFLATED -> OwnedInflaterInputStream(raw)
                else -> {
                    raw.close()
                    null
                }
            }
        }.getOrNull()

        override fun close() {
            runCatching { channel.close() }
            runCatching { owner?.close() }
        }

        companion object {
            private const val EOCD_SIG = 0x06054b50
            private const val ZIP64_LOCATOR_SIG = 0x07064b50
            private const val ZIP64_EOCD_SIG = 0x06064b50
            private const val CEN_SIG = 0x02014b50
            private const val LOCAL_HEADER_SIG = 0x04034b50
            private const val METHOD_STORED = 0
            private const val METHOD_DEFLATED = 8
            private const val GP_UTF8_FLAG = 1 shl 11
            private const val ZIP64_EXTRA_ID = 0x0001
            private const val MAX_CENTRAL_DIR_BYTES = 64L * 1024 * 1024

            private fun parseCentralDirectory(channel: FileChannel): List<ChannelEntry> {
                val fileSize = channel.size()
                if (fileSize < 22L) throw ZipException("不是有效的 ZIP 文件（长度不足）")
                val tailSize = minOf(fileSize, 22L + 65535L).toInt()
                val tailOffset = fileSize - tailSize
                val tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN)
                readFullyAt(channel, tail, tailOffset)
                val bytes = tail.array()

                var eocdRel = -1
                for (i in tailSize - 22 downTo 0) {
                    if (tail.getInt(i) == EOCD_SIG) {
                        val commentLen = tail.getShort(i + 20).toInt() and 0xFFFF
                        if (i + 22 + commentLen <= tailSize) {
                            eocdRel = i
                            break
                        }
                    }
                }
                if (eocdRel < 0) throw ZipException("找不到 ZIP 中央目录结尾记录")

                var cdSize = tail.getInt(eocdRel + 12).toLong() and 0xFFFFFFFFL
                var cdOffset = tail.getInt(eocdRel + 16).toLong() and 0xFFFFFFFFL

                val eocdAbs = tailOffset + eocdRel
                if (eocdAbs >= 20L) {
                    val locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                    readFullyAt(channel, locator, eocdAbs - 20L)
                    if (locator.getInt(0) == ZIP64_LOCATOR_SIG) {
                        val zip64EocdOffset = locator.getLong(8)
                        if (zip64EocdOffset in 0..(fileSize - 56L)) {
                            val zip64 = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
                            readFullyAt(channel, zip64, zip64EocdOffset)
                            if (zip64.getInt(0) == ZIP64_EOCD_SIG) {
                                cdSize = zip64.getLong(40)
                                cdOffset = zip64.getLong(48)
                            }
                        }
                    }
                }

                if (cdSize < 0L || cdSize > MAX_CENTRAL_DIR_BYTES || cdOffset < 0L || cdOffset + cdSize > fileSize) {
                    throw ZipException("ZIP 中央目录范围无效")
                }

                val cdBuf = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                readFullyAt(channel, cdBuf, cdOffset)
                val cdBytes = cdBuf.array()
                val result = ArrayList<ChannelEntry>()
                var pos = 0
                val limit = cdBytes.size
                while (pos + 46 <= limit) {
                    if (cdBuf.getInt(pos) != CEN_SIG) break
                    val flags = cdBuf.getShort(pos + 8).toInt() and 0xFFFF
                    val method = cdBuf.getShort(pos + 10).toInt() and 0xFFFF
                    var compSize = cdBuf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
                    var uncompSize = cdBuf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
                    val nameLen = cdBuf.getShort(pos + 28).toInt() and 0xFFFF
                    val extraLen = cdBuf.getShort(pos + 30).toInt() and 0xFFFF
                    val commentLen = cdBuf.getShort(pos + 32).toInt() and 0xFFFF
                    var localOffset = cdBuf.getInt(pos + 42).toLong() and 0xFFFFFFFFL

                    val nameStart = pos + 46
                    val extraStart = nameStart + nameLen
                    val nextPos = extraStart + extraLen + commentLen
                    if (nextPos > limit) throw ZipException("ZIP 中央目录条目越界")

                    val name = decodeEntryName(cdBytes, nameStart, nameLen, (flags and GP_UTF8_FLAG) != 0)
                    if (uncompSize == 0xFFFFFFFFL || compSize == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                        var cursor = extraStart
                        val extraEnd = extraStart + extraLen
                        while (cursor + 4 <= extraEnd) {
                            val headerId = cdBuf.getShort(cursor).toInt() and 0xFFFF
                            val dataSize = cdBuf.getShort(cursor + 2).toInt() and 0xFFFF
                            val fieldStart = cursor + 4
                            val fieldEnd = fieldStart + dataSize
                            if (fieldEnd > extraEnd) break
                            if (headerId == ZIP64_EXTRA_ID) {
                                var zPos = fieldStart
                                if (uncompSize == 0xFFFFFFFFL && zPos + 8 <= fieldEnd) {
                                    uncompSize = cdBuf.getLong(zPos)
                                    zPos += 8
                                }
                                if (compSize == 0xFFFFFFFFL && zPos + 8 <= fieldEnd) {
                                    compSize = cdBuf.getLong(zPos)
                                    zPos += 8
                                }
                                if (localOffset == 0xFFFFFFFFL && zPos + 8 <= fieldEnd) {
                                    localOffset = cdBuf.getLong(zPos)
                                }
                                break
                            }
                            cursor = fieldEnd
                        }
                    }

                    result.add(
                        ChannelEntry(
                            name = name,
                            isDirectory = name.endsWith("/"),
                            size = uncompSize,
                            compressedSize = compSize,
                            method = method,
                            localHeaderOffset = localOffset
                        )
                    )
                    pos = nextPos
                }
                return result
            }

            private fun decodeEntryName(bytes: ByteArray, offset: Int, length: Int, utf8Flag: Boolean): String {
                if (length == 0) return ""
                if (utf8Flag) return String(bytes, offset, length, Charsets.UTF_8)
                val strictUtf8 = runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, offset, length))
                        .toString()
                }.getOrNull()
                if (strictUtf8 != null) return strictUtf8
                return runCatching { String(bytes, offset, length, Charset.forName("GB18030")) }
                    .getOrElse { String(bytes, offset, length, Charsets.UTF_8) }
            }

            private fun readFullyAt(channel: FileChannel, target: ByteBuffer, position: Long) {
                var offset = position
                while (target.hasRemaining()) {
                    val n = channel.read(target, offset)
                    if (n < 0) throw EOFException("ZIP 读取意外到达文件末尾")
                    offset += n
                }
            }
        }
    }

    private class BoundedChannelInputStream(
        private val channel: FileChannel,
        startOffset: Long,
        length: Long
    ) : InputStream() {
        private var currentOffset = startOffset
        private var remaining = length.coerceAtLeast(0L)
        private val singleByte = ByteArray(1)

        override fun read(): Int {
            val n = read(singleByte, 0, 1)
            return if (n <= 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = channel.read(ByteBuffer.wrap(b, off, toRead), currentOffset)
            if (n <= 0) return -1
            currentOffset += n
            remaining -= n
            return n
        }

        override fun available(): Int = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private class OwnedInflaterInputStream(
        raw: InputStream,
        private val ownedInflater: Inflater = Inflater(true)
    ) : InflaterInputStream(raw, ownedInflater, 8192) {
        private var inflaterClosed = false

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return try {
                super.read(b, off, len)
            } catch (e: EOFException) {
                // 部分打包器省去末尾 1 字节 dummy byte：若 inflater 已结束则返回 -1
                if (ownedInflater.finished()) -1 else throw e
            }
        }

        override fun close() {
            if (inflaterClosed) return
            inflaterClosed = true
            try {
                super.close()
            } finally {
                ownedInflater.end()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 24 * 1024 * 1024

        /** 封面这类不可信来源的读取上限：zip 条目可以声明极小压缩体却解压出巨量数据。 */
        const val MAX_COVER_BYTES = 12 * 1024 * 1024

        /**
         * 单个章节 XHTML 的读取上限：导入、正文（TTS/搜索）与渲染三条路径共用，
         * 避免各写各的字面量后口径漂移（曾出现"能渲染但搜索永远 0 命中"的 4/8MB 不一致）。
         */
        const val MAX_CHAPTER_BYTES: Int = 8 * 1024 * 1024

        /**
         * 按上限读取流；超过上限返回 null。
         * 纯逻辑（不依赖 Android），因此可以直接单测——封面解码本身依赖 BitmapFactory，测不了。
         */
        fun readAtMost(input: InputStream, maxBytes: Int): ByteArray? = runCatching {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                total += n
                if (total > maxBytes) return@runCatching null
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        }.getOrNull()

        fun open(file: File): ZipEntryReader = ZipEntryReader(ZipFile(file))

        fun open(channel: FileChannel, owner: Closeable? = null): ZipEntryReader {
            try {
                return ZipEntryReader(ChannelZipBackend(channel, owner))
            } catch (t: Throwable) {
                runCatching { channel.close() }
                runCatching { owner?.close() }
                throw if (t is IOException) t else IOException("无法打开 EPUB 压缩流", t)
            }
        }

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
