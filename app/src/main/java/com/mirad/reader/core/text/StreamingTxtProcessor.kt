package com.mirad.reader.core.text

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * 大文件 TXT 极速流式转码、分章与内存优化处理器。
 *
 * 支持两种模式：
 * 1. [indexRaw] + [decodeRawSlice]：零拷贝模式，不写出 `content.utf8.txt`，直接记录章节在原文件中的真实字节区间，
 *    阅读/搜索时按章读取原始字节切片并即时执行同构解码与行归一化；
 * 2. [process]：写出 UTF-8 归一化正文文件（保留供历史契约与单测对照使用）。
 */
object StreamingTxtProcessor {

    data class StreamResult(
        val charset: String,
        val totalChars: Long,
        val totalBytes: Long,
        val ruleId: String?,
        val regex: String?,
        val slices: List<ChapterSlice>
    )

    private const val SAMPLE_BYTES = 64 * 1024
    private const val MAX_LINE_CHARS = 8192
    private const val MAX_LINE_BYTES = 24 * 1024
    private const val FALLBACK_CHUNK_CHARS = ChapterSplitter.FALLBACK_CHUNK_CHARS

    private data class SplitPoint(val charOffset: Int, val byteOffset: Long)

    /**
     * 零落盘扫描原文件字节流：探测编码，记录每章在**原文件**中的精确字节区间 `[startByte, endByte)`
     * 以及归一化后的 `charCount` 与 `titleLineChars`。
     *
     * @param maxRawBytes 最大允许扫描的原文件字节数（默认无限制；传入正数时超限立即抛异常中止，防御未知长度的超大流）。
     */
    fun indexRaw(
        sourceFile: File,
        manualEncoding: String? = null
    ): StreamResult {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            throw IllegalArgumentException("TXT 源文件为空或不存在: ${sourceFile.absolutePath}")
        }
        return indexRaw(
            openStream = { FileInputStream(sourceFile) },
            declaredBytes = sourceFile.length(),
            manualEncoding = manualEncoding
        )
    }

    fun indexRaw(
        openStream: () -> InputStream,
        declaredBytes: Long = -1L,
        manualEncoding: String? = null,
        maxRawBytes: Long = Long.MAX_VALUE
    ): StreamResult {
        val sampleBuf = ByteArray(SAMPLE_BYTES)
        var sampleLen = 0
        var moreAfterSample = false
        openStream().use { input ->
            while (sampleLen < SAMPLE_BYTES) {
                val read = input.read(sampleBuf, sampleLen, SAMPLE_BYTES - sampleLen)
                if (read < 0) break
                sampleLen += read
            }
            if (sampleLen == SAMPLE_BYTES) {
                moreAfterSample = input.read() >= 0
            }
        }
        if (sampleLen == 0) {
            throw IllegalArgumentException("TXT 源文件为空")
        }
        val sampleBytes = if (sampleLen == SAMPLE_BYTES) sampleBuf else sampleBuf.copyOf(sampleLen)
        val isPrefix = if (declaredBytes > sampleLen) true else moreAfterSample
        val charset = resolveCharset(sampleBytes, manualEncoding, isPrefix)

        val titleMarks = mutableListOf<ChapterMark>()
        val fallbackPoints = mutableListOf<SplitPoint>()
        fallbackPoints.add(SplitPoint(0, 0L))

        var totalChars = 0L
        var charsSinceLastFallback = 0L
        var totalRawBytes = 0L

        val normalizer = LineNormalizer(
            stripLeadingBom = true,
            onTitleCandidate = { trimmed, lineLenWithNewline, startChar, rawLineStartByte ->
                titleMarks.add(
                    ChapterMark(
                        title = trimmed,
                        titleLineChars = lineLenWithNewline,
                        startChar = startChar,
                        startByte = rawLineStartByte,
                        isVolume = ChapterDetector.isVolumeTitle(trimmed)
                    )
                )
            },
            onNormalizedPiece = { piece, rawEndByte, emptyLineStreak ->
                val len = piece.length.toLong()
                totalChars += len
                charsSinceLastFallback += len
                // 仅在 emptyLineStreak == 0（非空行或内部非空块结束）处记录兜底切分点，
                // 配合切片的自然行边界上下文，下一节的初始 emptyLineStreak = 0 与全书状态保持一致。
                if (charsSinceLastFallback >= FALLBACK_CHUNK_CHARS && emptyLineStreak == 0) {
                    fallbackPoints.add(SplitPoint(totalChars.toInt(), rawEndByte))
                    charsSinceLastFallback = 0L
                }
            }
        )

        openStream().use { input ->
            totalRawBytes = scanRawStream(input, charset, normalizer, maxRawBytes)
        }

        val detection = ChapterDetector.detectMarks(titleMarks, totalChars.toInt())
        val slices = if (detection.marks.isNotEmpty()) {
            ChapterSplitter.slicesFromMarks(detection.marks, totalChars.toInt(), totalRawBytes)
        } else {
            buildFallbackSlicesFromPoints(fallbackPoints, totalChars.toInt(), totalRawBytes)
        }

        return StreamResult(
            charset = charset.name(),
            totalChars = totalChars,
            totalBytes = totalRawBytes,
            ruleId = detection.rule?.id,
            regex = null,
            slices = slices
        )
    }

    /**
     * 解码并归一化原文件的单个章节字节切片，与 [indexRaw] 共用同一套行扫描与清洗状态机，
     * 确保产出文本长度与章节的 `charCount`、`titleLineChars` 严格一致。
     * [previousRawBytes] 为起点前至多两个字节，[isSourceEnd] 区分章节中断与真正文件结尾。
     */
    fun decodeRawSlice(
        rawBytes: ByteArray,
        charsetName: String?,
        stripLeadingBom: Boolean,
        previousRawBytes: ByteArray,
        isSourceEnd: Boolean
    ): String {
        if (rawBytes.isEmpty()) return ""
        val charset = charsetForNameOrUtf8(charsetName, rawBytes)
        val out = StringBuilder(rawBytes.size)
        val normalizer = LineNormalizer(
            stripLeadingBom = stripLeadingBom,
            startsInsideLine = previousRawBytes.isNotEmpty() && !endsWithLineBreak(previousRawBytes, charset),
            onTitleCandidate = null,
            onNormalizedPiece = { piece, _, _ -> out.append(piece) }
        )
        scanRawStream(
            ByteArrayInputStream(rawBytes), charset, normalizer,
            endsInsideLine = !isSourceEnd && !endsWithLineBreak(rawBytes, charset)
        )
        return out.toString()
    }

    /** 切片边界至多需要前一个码元，不从文件开头重扫，也不把行内空白误当作行尾。 */
    private fun endsWithLineBreak(bytes: ByteArray, charset: Charset): Boolean {
        if ((charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) && bytes.size < 2) return false
        val unit = when (charset) {
            Charsets.UTF_16LE -> (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes.last().toInt() and 0xFF) shl 8)
            Charsets.UTF_16BE -> ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or (bytes.last().toInt() and 0xFF)
            else -> bytes.last().toInt() and 0xFF
        }
        return unit == 0x0A || unit == 0x0D
    }

    /**
     * 打开按需流式解码归一化的 [Reader]，供版本比对（如 `EditionMatcher`）在不落盘、不读全书进内存的前提下逐字符读取。
     */
    fun openNormalizedReader(
        openStream: () -> InputStream,
        charsetName: String?
    ): Reader {
        val rawInput = openStream()
        return NormalizedStreamReader(rawInput, charsetName)
    }

    /**
     * 写出 UTF-8 归一化文件的流式处理入口（保留原有行为与测试契约）。
     */
    fun process(
        sourceFile: File,
        targetContentFile: File,
        manualEncoding: String? = null
    ): StreamResult {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            throw IllegalArgumentException("TXT 源文件为空或不存在: ${sourceFile.absolutePath}")
        }

        val sampleSize = minOf(sourceFile.length(), SAMPLE_BYTES.toLong()).toInt()
        val isPrefix = sourceFile.length() > sampleSize
        val sampleBytes = ByteArray(sampleSize)
        FileInputStream(sourceFile).use { input ->
            var offset = 0
            while (offset < sampleSize) {
                val read = input.read(sampleBytes, offset, sampleSize - offset)
                if (read <= 0) break
                offset += read
            }
        }
        val charset = resolveCharset(sampleBytes, manualEncoding, isPrefix)

        val titleMarks = mutableListOf<ChapterMark>()
        val fallbackPoints = mutableListOf<SplitPoint>()
        fallbackPoints.add(SplitPoint(0, 0L))

        var totalChars = 0L
        var charsSinceLastFallback = 0L

        val parentDir = targetContentFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val countingOut = CountingOutputStream(FileOutputStream(targetContentFile))
        FileInputStream(sourceFile).use { rawIn ->
            OutputStreamWriter(countingOut, Charsets.UTF_8).use { writer ->
                val normalizer = LineNormalizer(
                    stripLeadingBom = true,
                    onTitleCandidate = { trimmed, lineLenWithNewline, startChar, _ ->
                        writer.flush()
                        titleMarks.add(
                            ChapterMark(
                                title = trimmed,
                                titleLineChars = lineLenWithNewline,
                                startChar = startChar,
                                startByte = countingOut.count,
                                isVolume = ChapterDetector.isVolumeTitle(trimmed)
                            )
                        )
                    },
                    onNormalizedPiece = { piece, _, emptyLineStreak ->
                        writer.write(piece)
                        writer.flush()
                        val len = piece.length.toLong()
                        totalChars += len
                        charsSinceLastFallback += len
                        if (charsSinceLastFallback >= FALLBACK_CHUNK_CHARS && emptyLineStreak == 0) {
                            fallbackPoints.add(SplitPoint(totalChars.toInt(), countingOut.count))
                            charsSinceLastFallback = 0L
                        }
                    }
                )
                scanRawStream(rawIn, charset, normalizer)
                writer.flush()
            }
        }

        val totalBytes = countingOut.count
        val detection = ChapterDetector.detectMarks(titleMarks, totalChars.toInt())
        val slices = if (detection.marks.isNotEmpty()) {
            ChapterSplitter.slicesFromMarks(detection.marks, totalChars.toInt(), totalBytes)
        } else {
            buildFallbackSlicesFromPoints(fallbackPoints, totalChars.toInt(), totalBytes)
        }

        return StreamResult(
            charset = charset.name(),
            totalChars = totalChars,
            totalBytes = totalBytes,
            ruleId = detection.rule?.id,
            regex = null,
            slices = slices
        )
    }

    private fun resolveCharset(sampleBytes: ByteArray, manualEncoding: String?, isPrefix: Boolean): Charset {
        val decodedSample = TextCodec.decode(sampleBytes, manual = manualEncoding, isPrefix = isPrefix)
        return charsetForNameOrUtf8(decodedSample.charsetName, sampleBytes)
    }

    private fun charsetForNameOrUtf8(name: String?, sample: ByteArray): Charset {
        if (name.isNullOrBlank()) return Charsets.UTF_8
        if (name.equals("UTF-16", ignoreCase = true)) {
            val isLe = sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()
            return if (isLe) Charsets.UTF_16LE else Charsets.UTF_16BE
        }
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private class LineNormalizer(
        stripLeadingBom: Boolean,
        startsInsideLine: Boolean = false,
        private val onTitleCandidate: ((trimmed: String, lineLenWithNewline: Int, startChar: Int, rawLineStartByte: Long) -> Unit)?,
        private val onNormalizedPiece: (piece: String, rawEndByte: Long, emptyLineStreak: Int) -> Unit
    ) {
        private var isFirstChar = stripLeadingBom
        private var emptyLineStreak = 0
        private var isLineContinuation = startsInsideLine
        private var emittedChars = 0

        fun onInternalChunk(rawChunk: String, rawEndByte: Long) {
            if (rawChunk.isEmpty()) return
            isLineContinuation = true
            var text = rawChunk
            if (isFirstChar) {
                isFirstChar = false
                if (text.startsWith('\uFEFF')) {
                    text = text.substring(1)
                }
            }
            text = text.replace("\u0000", "")
            if (text.isEmpty()) return
            emptyLineStreak = 0
            emittedChars += text.length
            onNormalizedPiece(text, rawEndByte, 0)
        }

        fun onNaturalLine(rawLine: String, rawLineStartByte: Long, rawLineEndByte: Long) {
            val wasContinuation = isLineContinuation
            isLineContinuation = false

            var line = rawLine
            if (isFirstChar) {
                isFirstChar = false
                if (line.isNotEmpty() && line[0] == '\uFEFF') {
                    line = line.substring(1)
                }
            }

            line = line.replace("\u0000", "").trimEnd(' ', '\t', '\u00A0', '\u3000')

            if (line.isEmpty() && !wasContinuation) {
                emptyLineStreak++
                if (emptyLineStreak > 2) return
            } else {
                emptyLineStreak = 0
            }

            if (!wasContinuation && onTitleCandidate != null) {
                val trimmed = line.trim()
                if (ChapterDetector.isTitleCandidate(trimmed)) {
                    onTitleCandidate.invoke(trimmed, line.length + 1, emittedChars, rawLineStartByte)
                }
            }

            val withNewline = "$line\n"
            emittedChars += withNewline.length
            onNormalizedPiece(withNewline, rawLineEndByte, emptyLineStreak)
        }
    }

    private fun scanRawStream(
        input: InputStream,
        charset: Charset,
        normalizer: LineNormalizer,
        maxRawBytes: Long = Long.MAX_VALUE,
        endsInsideLine: Boolean = false
    ): Long {
        val scanner = RawChunkScanner(charset, normalizer)
        val readBuf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(readBuf)
            if (n < 0) break
            total += n
            if (total > maxRawBytes) {
                throw IllegalArgumentException("文件超过 ${maxRawBytes / 1024 / 1024}MB 上限")
            }
            scanner.feed(readBuf, n)
        }
        return scanner.finish(endsInsideLine)
    }

    private fun newReplacingDecoder(charset: Charset): CharsetDecoder =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private fun decodeCompleteBytes(bytes: ByteArray, length: Int, charset: Charset): String {
        if (length <= 0) return ""
        return newReplacingDecoder(charset).decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    }

    /**
     * 共享的块级行扫描状态机：[scanRawStream] 与 [NormalizedStreamReader] 共用同一份实现，
     * 精确追踪每个自然行及超长行分块的原始字节区间，并保证超长行在任何编码输入下均单调推进。
     */
    private class RawChunkScanner(
        private val charset: Charset,
        private val normalizer: LineNormalizer
    ) {
        private val isUtf16: Boolean
        private val littleEndian: Boolean
        private var lineBuf = ByteArray(1024)
        private var lineLen = 0
        private var lineStartByte = 0L
        private var streamPos = 0L
        private var seenCR = false
        private var crMarkByte = 0L
        private var hasPendingUtf16Byte = false
        private var pendingUtf16Byte: Byte = 0

        init {
            val upper = charset.name().uppercase()
            isUtf16 = upper == "UTF-16LE" || upper == "UTF-16BE"
            littleEndian = upper == "UTF-16LE"
        }

        fun feed(chunk: ByteArray, length: Int) {
            if (length <= 0) return
            if (isUtf16) feedUtf16(chunk, length) else feedByteDelimited(chunk, length)
        }

        fun finish(endsInsideLine: Boolean = false): Long {
            if (isUtf16) {
                if (seenCR) {
                    seenCR = false
                    val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                    lineLen = 0
                    normalizer.onNaturalLine(lineText, lineStartByte, crMarkByte)
                    lineStartByte = crMarkByte
                }
                if (hasPendingUtf16Byte) {
                    hasPendingUtf16Byte = false
                    ensureLineCapacity(1)
                    lineBuf[lineLen++] = pendingUtf16Byte
                }
                if (lineLen > 0) {
                    val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                    lineLen = 0
                    if (endsInsideLine) normalizer.onInternalChunk(lineText, streamPos)
                    else normalizer.onNaturalLine(lineText, lineStartByte, streamPos)
                    lineStartByte = streamPos
                }
            } else {
                if (seenCR) {
                    seenCR = false
                    val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                    lineLen = 0
                    val lineEndByte = crMarkByte + 1L
                    normalizer.onNaturalLine(lineText, lineStartByte, lineEndByte)
                    lineStartByte = lineEndByte
                } else if (lineLen > 0) {
                    val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                    lineLen = 0
                    if (endsInsideLine) normalizer.onInternalChunk(lineText, streamPos)
                    else normalizer.onNaturalLine(lineText, lineStartByte, streamPos)
                    lineStartByte = streamPos
                }
            }
            return streamPos
        }

        private fun ensureLineCapacity(additional: Int) {
            if (lineLen + additional > lineBuf.size) {
                var nextSize = lineBuf.size * 2
                while (nextSize < lineLen + additional) nextSize *= 2
                lineBuf = lineBuf.copyOf(nextSize)
            }
        }

        private fun feedByteDelimited(chunk: ByteArray, length: Int) {
            for (i in 0 until length) {
                val b = chunk[i]
                val bPos = streamPos++

                if (seenCR) {
                    seenCR = false
                    if (b == 0x0A.toByte()) {
                        val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                        lineLen = 0
                        val lineEndByte = bPos + 1L
                        normalizer.onNaturalLine(lineText, lineStartByte, lineEndByte)
                        lineStartByte = lineEndByte
                        continue
                    } else {
                        val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                        lineLen = 0
                        val lineEndByte = crMarkByte + 1L
                        normalizer.onNaturalLine(lineText, lineStartByte, lineEndByte)
                        lineStartByte = lineEndByte
                    }
                }

                if (b == 0x0D.toByte()) {
                    seenCR = true
                    crMarkByte = bPos
                    continue
                }
                if (b == 0x0A.toByte()) {
                    val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                    lineLen = 0
                    val lineEndByte = bPos + 1L
                    normalizer.onNaturalLine(lineText, lineStartByte, lineEndByte)
                    lineStartByte = lineEndByte
                    continue
                }

                ensureLineCapacity(1)
                lineBuf[lineLen++] = b
                if (lineLen >= MAX_LINE_BYTES) {
                    flushLongByteDelimitedLine()
                }
            }
        }

        private fun flushLongByteDelimitedLine() {
            val decoder = newReplacingDecoder(charset)
            val inBuf = ByteBuffer.wrap(lineBuf, 0, lineLen)
            val outBuf = CharBuffer.allocate(MAX_LINE_CHARS)
            val res: CoderResult = decoder.decode(inBuf, outBuf, false)
            var consumed = inBuf.position()
            var text: String? = null
            if (consumed > 0 && outBuf.position() > 0) {
                text = String(outBuf.array(), 0, outBuf.position())
            } else if (res.isOverflow) {
                val bigger = CharBuffer.allocate(MAX_LINE_CHARS + 8)
                decoder.decode(inBuf, bigger, false)
                consumed = inBuf.position()
                if (consumed > 0 && bigger.position() > 0) {
                    text = String(bigger.array(), 0, bigger.position())
                }
            }
            if (consumed <= 0) {
                // 极端损坏序列兜底：强制推进整个已满块，杜绝单字节循环重解码退化为 O(N²)
                text = decodeCompleteBytes(lineBuf, lineLen, charset)
                consumed = lineLen
            }
            val chunkEndByte = lineStartByte + consumed
            val remaining = lineLen - consumed
            if (remaining > 0) {
                System.arraycopy(lineBuf, consumed, lineBuf, 0, remaining)
            }
            lineLen = remaining
            lineStartByte = chunkEndByte
            if (!text.isNullOrEmpty()) {
                normalizer.onInternalChunk(text, chunkEndByte)
            }
        }

        private fun feedUtf16(chunk: ByteArray, length: Int) {
            var idx = 0
            while (idx < length) {
                val b0: Byte
                val b1: Byte
                if (hasPendingUtf16Byte) {
                    b0 = pendingUtf16Byte
                    b1 = chunk[idx++]
                    hasPendingUtf16Byte = false
                    streamPos += 1L
                } else if (idx + 1 < length) {
                    b0 = chunk[idx]
                    b1 = chunk[idx + 1]
                    idx += 2
                    streamPos += 2L
                } else {
                    pendingUtf16Byte = chunk[idx++]
                    hasPendingUtf16Byte = true
                    streamPos += 1L
                    break
                }

                val u0 = b0.toInt() and 0xFF
                val u1 = b1.toInt() and 0xFF
                val unit = if (littleEndian) (u0 or (u1 shl 8)) else ((u0 shl 8) or u1)

                if (seenCR) {
                    seenCR = false
                    if (unit == 0x000A) {
                        val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                        lineLen = 0
                        normalizer.onNaturalLine(lineText, lineStartByte, streamPos)
                        lineStartByte = streamPos
                        continue
                    } else {
                        val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                        lineLen = 0
                        normalizer.onNaturalLine(lineText, lineStartByte, crMarkByte)
                        lineStartByte = crMarkByte
                    }
                }

                if (unit == 0x000D) {
                    seenCR = true
                    crMarkByte = streamPos
                    continue
                }
                if (unit == 0x000A) {
                    val lineText = decodeCompleteBytes(lineBuf, lineLen, charset)
                    lineLen = 0
                    normalizer.onNaturalLine(lineText, lineStartByte, streamPos)
                    lineStartByte = streamPos
                    continue
                }

                ensureLineCapacity(2)
                lineBuf[lineLen++] = b0
                lineBuf[lineLen++] = b1
                if (lineLen >= MAX_LINE_CHARS * 2) {
                    val lastU0 = lineBuf[lineLen - 2].toInt() and 0xFF
                    val lastU1 = lineBuf[lineLen - 1].toInt() and 0xFF
                    val lastUnit = if (littleEndian) (lastU0 or (lastU1 shl 8)) else ((lastU0 shl 8) or lastU1)
                    val cutBytes = if (lastUnit in 0xD800..0xDBFF && lineLen > 2) lineLen - 2 else lineLen
                    val chunkText = decodeCompleteBytes(lineBuf, cutBytes, charset)
                    val chunkEndByte = lineStartByte + cutBytes
                    val remaining = lineLen - cutBytes
                    if (remaining > 0) {
                        System.arraycopy(lineBuf, cutBytes, lineBuf, 0, remaining)
                    }
                    lineLen = remaining
                    lineStartByte = chunkEndByte
                    normalizer.onInternalChunk(chunkText, chunkEndByte)
                }
            }
        }
    }

    /**
     * 按块拉取的流式归一化 [Reader]，复用 [RawChunkScanner]，避免一次性把整本书加载到内存。
     */
    private class NormalizedStreamReader(
        private val rawInput: InputStream,
        explicitCharsetName: String?
    ) : Reader() {
        private val stream: InputStream
        private val textBuf = StringBuilder(4096)
        private var textPos = 0
        private var eof = false
        private var closed = false
        private val byteChunk = ByteArray(8192)
        private val scanner: RawChunkScanner

        init {
            val charset: Charset
            if (!explicitCharsetName.isNullOrBlank() && !explicitCharsetName.equals("UTF-16", ignoreCase = true)) {
                charset = runCatching { Charset.forName(explicitCharsetName) }.getOrDefault(Charsets.UTF_8)
                stream = rawInput
            } else {
                val sample = ByteArray(SAMPLE_BYTES)
                var sampleLen = 0
                while (sampleLen < SAMPLE_BYTES) {
                    val n = rawInput.read(sample, sampleLen, SAMPLE_BYTES - sampleLen)
                    if (n < 0) break
                    sampleLen += n
                }
                val head = if (sampleLen == SAMPLE_BYTES) sample else sample.copyOf(sampleLen)
                charset = if (!explicitCharsetName.isNullOrBlank()) {
                    charsetForNameOrUtf8(explicitCharsetName, head)
                } else {
                    resolveCharset(head, manualEncoding = null, isPrefix = sampleLen == SAMPLE_BYTES)
                }
                stream = SequenceInputStream(ByteArrayInputStream(head), rawInput)
            }
            val normalizer = LineNormalizer(
                stripLeadingBom = true,
                onTitleCandidate = null,
                onNormalizedPiece = { piece, _, _ -> textBuf.append(piece) }
            )
            scanner = RawChunkScanner(charset, normalizer)
        }

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")
            if (len == 0) return 0
            while (textPos >= textBuf.length && !eof) {
                textBuf.setLength(0)
                textPos = 0
                val n = stream.read(byteChunk)
                if (n < 0) {
                    eof = true
                    scanner.finish()
                } else {
                    scanner.feed(byteChunk, n)
                }
            }
            if (textPos >= textBuf.length) return -1
            val available = textBuf.length - textPos
            val toCopy = minOf(len, available)
            textBuf.getChars(textPos, textPos + toCopy, cbuf, off)
            textPos += toCopy
            if (textPos >= textBuf.length) {
                textBuf.setLength(0)
                textPos = 0
            }
            return toCopy
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { stream.close() }
            runCatching { rawInput.close() }
        }
    }

    private fun buildFallbackSlicesFromPoints(
        points: List<SplitPoint>,
        totalChars: Int,
        totalBytes: Long
    ): List<ChapterSlice> {
        if (totalChars <= 0) return emptyList()
        val distinctPoints = points.toMutableList()
        if (distinctPoints.last().charOffset < totalChars) {
            distinctPoints.add(SplitPoint(totalChars, totalBytes))
        }

        val slices = ArrayList<ChapterSlice>(distinctPoints.size)
        var sliceIdx = 1
        for (i in 0 until distinctPoints.size - 1) {
            val pStart = distinctPoints[i]
            val pEnd = distinctPoints[i + 1]
            if (pEnd.charOffset <= pStart.charOffset) continue
            slices.add(
                ChapterSlice(
                    title = "第 ${sliceIdx++} 节",
                    titleLineChars = 0,
                    startChar = pStart.charOffset,
                    endChar = pEnd.charOffset,
                    startByte = pStart.byteOffset,
                    endByte = pEnd.byteOffset,
                    isVolume = false
                )
            )
        }
        return slices
    }
}

private class CountingOutputStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray) {
        out.write(b)
        count += b.size
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        out.close()
    }
}
