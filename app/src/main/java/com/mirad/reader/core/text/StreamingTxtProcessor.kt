package com.mirad.reader.core.text

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset

/**
 * 大文件 TXT 极速流式转码、分章与内存优化处理器。
 *
 * 核心设计：
 * 1. 严格有界内存：编码采样只读前 64KB 字节，转码阶段限制单行缓冲，即便长行也不整行读入内存；
 * 2. 真实边界记录：无论是有标题分章还是无标题兜底分节，切片点均在流式写入目标文件时实时捕获确切的 (charOffset, byteOffset)，
 *    杜绝任何除法估算，100% 保证 UTF-8 字符边界对齐，绝不截断多字节编码。
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
    private const val FALLBACK_CHUNK_CHARS = ChapterSplitter.FALLBACK_CHUNK_CHARS

    private data class SplitPoint(val charOffset: Int, val byteOffset: Long)

    fun process(
        sourceFile: File,
        targetContentFile: File,
        manualEncoding: String? = null
    ): StreamResult {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            throw IllegalArgumentException("TXT 源文件为空或不存在: ${sourceFile.absolutePath}")
        }

        // 1. 采样探测编码（严格只读前 64KB 字节；循环读满，单次 read 可能短读）
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
        val decodedSample = TextCodec.decode(sampleBytes, manual = manualEncoding, isPrefix = isPrefix)
        val charset = runCatching { Charset.forName(decodedSample.charsetName) }.getOrDefault(Charsets.UTF_8)

        // 2. 流式清洗、转码写入并记录全书标题候选；不让前 64KB 决定后续章节类型。
        val titleMarks = mutableListOf<ChapterMark>()
        val fallbackPoints = mutableListOf<SplitPoint>()
        // 初始切片点 (0, 0)
        fallbackPoints.add(SplitPoint(0, 0L))

        var totalChars = 0L
        var charsSinceLastFallback = 0L

        val parentDir = targetContentFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val countingOut = CountingOutputStream(java.io.FileOutputStream(targetContentFile))
        java.io.InputStreamReader(java.io.FileInputStream(sourceFile), charset).use { reader ->
            java.io.OutputStreamWriter(countingOut, Charsets.UTF_8).use { writer ->
                val lineBuf = StringBuilder(512)
                var isFirstChar = true
                var emptyLineStreak = 0
                var isLineContinuation = false

                // 刷新超长行内部 chunk（保持正文无损：不追加伪换行，不 trim 空格，不匹配标题）
                fun flushInternalChunk(chunk: String) {
                    if (chunk.isEmpty()) return
                    isLineContinuation = true
                    var text = chunk
                    if (isFirstChar) {
                        isFirstChar = false
                        if (text.startsWith('\uFEFF')) {
                            text = text.substring(1)
                        }
                    }
                    text = text.replace("\u0000", "")
                    if (text.isEmpty()) return

                    writer.write(text)
                    writer.flush()
                    val chunkChars = text.length.toLong()
                    totalChars += chunkChars
                    charsSinceLastFallback += chunkChars

                    if (charsSinceLastFallback >= FALLBACK_CHUNK_CHARS) {
                        fallbackPoints.add(SplitPoint(totalChars.toInt(), countingOut.count))
                        charsSinceLastFallback = 0L
                    }
                }

                // 处理真实的自然行结束（包含换行符 \n）
                fun processNaturalLine(rawLine: String) {
                    val wasContinuation = isLineContinuation
                    isLineContinuation = false

                    var line = rawLine
                    if (isFirstChar) {
                        isFirstChar = false
                        if (line.isNotEmpty() && line[0] == '\uFEFF') {
                            line = line.substring(1)
                        }
                    }

                    // 清洗 NUL 与行尾空白
                    line = line.replace("\u0000", "").trimEnd(' ', '\t', '\u00A0', '\u3000')

                    if (line.isEmpty() && !wasContinuation) {
                        emptyLineStreak++
                        if (emptyLineStreak > 2) return
                    } else {
                        emptyLineStreak = 0
                    }

                    // 章节标题匹配（超长截断延续行绝不作为标题）
                    if (!wasContinuation) {
                        val trimmed = line.trim()
                        if (ChapterDetector.isTitleCandidate(trimmed)) {
                            writer.flush()
                            titleMarks.add(
                                ChapterMark(
                                    title = trimmed,
                                    titleLineChars = line.length + 1,
                                    startChar = totalChars.toInt(),
                                    startByte = countingOut.count,
                                    isVolume = ChapterDetector.isVolumeTitle(trimmed)
                                )
                            )
                        }
                    }

                    writer.write(line)
                    writer.write("\n")
                    writer.flush()

                    val lineChars = line.length + 1L
                    totalChars += lineChars
                    charsSinceLastFallback += lineChars

                    // 兜底分节真实边界捕获
                    if (charsSinceLastFallback >= FALLBACK_CHUNK_CHARS) {
                        fallbackPoints.add(SplitPoint(totalChars.toInt(), countingOut.count))
                        charsSinceLastFallback = 0L
                    }
                }

                var seenCR = false
                var chInt = reader.read()
                while (chInt != -1) {
                    val ch = chInt.toChar()

                    // 1. 处理上一个字符是 \r 的状态
                    if (seenCR) {
                        seenCR = false
                        if (ch == '\n') {
                            // \r\n (CRLF) 换行
                            processNaturalLine(lineBuf.toString())
                            lineBuf.setLength(0)
                            chInt = reader.read()
                            continue
                        } else {
                            // 独立的 \r (CR) 换行：先提交已缓冲行，当前字符继续往下流转
                            processNaturalLine(lineBuf.toString())
                            lineBuf.setLength(0)
                        }
                    }

                    // 2. 处理当前字符换行
                    if (ch == '\r') {
                        seenCR = true
                        chInt = reader.read()
                        continue
                    } else if (ch == '\n') {
                        processNaturalLine(lineBuf.toString())
                        lineBuf.setLength(0)
                        chInt = reader.read()
                        continue
                    }

                    // 3. 处理代理项或普通字符
                    if (Character.isHighSurrogate(ch)) {
                        val next = reader.read()
                        if (next != -1) {
                            val nextCh = next.toChar()
                            if (Character.isLowSurrogate(nextCh)) {
                                lineBuf.append(ch)
                                lineBuf.append(nextCh)
                                chInt = reader.read()
                            } else {
                                lineBuf.append(ch)
                                chInt = next // 下一轮循环继续处理该非低代理项字符
                            }
                        } else {
                            lineBuf.append(ch)
                            chInt = -1
                        }
                    } else {
                        lineBuf.append(ch)
                        chInt = reader.read()
                    }

                    // 4. 检查超长行有界内存防御（绝不拆开结尾的高代理项）
                    if (lineBuf.length >= MAX_LINE_CHARS) {
                        val cutLen = if (Character.isHighSurrogate(lineBuf.last())) lineBuf.length - 1 else lineBuf.length
                        if (cutLen > 0) {
                            val chunk = lineBuf.substring(0, cutLen)
                            val remaining = lineBuf.substring(cutLen)
                            lineBuf.setLength(0)
                            lineBuf.append(remaining)
                            flushInternalChunk(chunk)
                        }
                    }
                }

                // 处理末尾悬挂的 CR 或未结束文本
                if (seenCR) {
                    processNaturalLine(lineBuf.toString())
                    lineBuf.setLength(0)
                } else if (lineBuf.isNotEmpty()) {
                    processNaturalLine(lineBuf.toString())
                    lineBuf.setLength(0)
                }

                writer.flush()
            }
        }

        val totalBytes = countingOut.count

        // 3. 与重新分章共用规则优先级、强标题合并、弱数字密度和全书序号校验。
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

    private fun buildFallbackSlicesFromPoints(
        points: List<SplitPoint>,
        totalChars: Int,
        totalBytes: Long
    ): List<ChapterSlice> {
        if (totalChars <= 0) return emptyList()
        // 保证末尾包含终点
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
