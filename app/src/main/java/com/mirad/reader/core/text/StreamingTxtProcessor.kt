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
 * 1. 严格有界内存：采样阶段只读前 64KB 字符，转码阶段单行设置安全截断上限，即便 64MB 单行文件也不产生 OOM；
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
    private const val SAMPLE_CHARS = 64 * 1024
    private const val MAX_LINE_CHARS = 8192
    private const val PREFACE_TITLE = "前言"
    private const val FALLBACK_CHUNK_CHARS = 3000

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

        // 2. 采样探测分章规则（严格只读前 64KB 字符，避免无换行长行导致内存膨胀）
        val sampleCharBuffer = CharArray(SAMPLE_CHARS)
        var sampleLen = 0
        InputStreamReader(FileInputStream(sourceFile), charset).use { reader ->
            while (sampleLen < sampleCharBuffer.size) {
                val read = reader.read(sampleCharBuffer, sampleLen, sampleCharBuffer.size - sampleLen)
                if (read <= 0) break
                sampleLen += read
            }
        }
        val sampleText = String(sampleCharBuffer, 0, sampleLen)
        val detection = ChapterDetector.detect(sampleText)
        val effectiveRule = detection.rule

        // 3. 流式清洗、转码写入并记录真实切片点
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
                        if (trimmed.isNotEmpty() && trimmed.length <= ChapterRule.MAX_TITLE_CHARS) {
                            val isVolume = ChapterRules.CN_VOLUME_RULE.matches(trimmed)
                            val isVolumeOnly = isVolume && !Regex("[章节節回]").containsMatchIn(trimmed)
                            val matched = (effectiveRule != null && effectiveRule.matches(trimmed)) ||
                                ChapterRules.CN_GENERIC_RULE.matches(trimmed) ||
                                ChapterRules.CN_BRACKET_RULE.matches(trimmed) ||
                                (effectiveRule?.id != ChapterRules.NUMBERED && isVolume)

                            if (matched && plausible(trimmed, effectiveRule?.id == ChapterRules.NUMBERED)) {
                                writer.flush()
                                titleMarks.add(
                                    ChapterMark(
                                        title = trimmed,
                                        titleLineChars = line.length + 1,
                                        startChar = totalChars.toInt(),
                                        startByte = countingOut.count,
                                        isVolume = isVolumeOnly
                                    )
                                )
                            }
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

        // 4. 构建真实无误差切片
        // 与「重新分章」同口径的整体可信度校验：全文件匹配出的标题序号若不满足
        // 单调性（常见于网络文拼凑稿），弃用标题分章、退回篇幅兜底分节，
        // 否则同一本书导入与重新分章会给出两套不同的章节划分。
        val marksTrusted = titleMarks.isNotEmpty() && marksAreTrustworthy(titleMarks)
        val slices = if (marksTrusted) {
            buildSlicesFromMarks(titleMarks, totalChars.toInt(), totalBytes)
        } else {
            buildFallbackSlicesFromPoints(fallbackPoints, totalChars.toInt(), totalBytes)
        }

        return StreamResult(
            charset = charset.name(),
            totalChars = totalChars,
            totalBytes = totalBytes,
            ruleId = if (marksTrusted) effectiveRule?.id else null,
            regex = null,
            slices = slices
        )
    }

    /** 与 ChapterDetector.detect 相同的分组单调性判据，共享实现保证口径一致。 */
    private fun marksAreTrustworthy(marks: List<ChapterMark>): Boolean {
        val groups = mutableListOf<MutableList<Int>>(mutableListOf())
        marks.forEach { mark ->
            if (mark.isVolume) groups.add(mutableListOf())
            else ChapterDetector.chapterNumber(mark.title)?.let { groups.last().add(it) }
        }
        return groups.none { !ChapterDetector.isMonotonic(it) }
    }

    private fun buildSlicesFromMarks(
        marks: List<ChapterMark>,
        totalChars: Int,
        totalBytes: Long
    ): List<ChapterSlice> {
        val slices = ArrayList<ChapterSlice>(marks.size + 1)
        if (marks.first().startChar > 0) {
            slices.add(
                ChapterSlice(
                    title = PREFACE_TITLE,
                    titleLineChars = 0,
                    startChar = 0,
                    endChar = marks.first().startChar,
                    startByte = 0L,
                    endByte = marks.first().startByte,
                    isVolume = false
                )
            )
        }

        for (i in marks.indices) {
            val mark = marks[i]
            val next = marks.getOrNull(i + 1)
            val endChar = next?.startChar ?: totalChars
            val endByte = next?.startByte ?: totalBytes
            if (endChar <= mark.startChar) continue
            slices.add(
                ChapterSlice(
                    title = mark.title,
                    titleLineChars = mark.titleLineChars,
                    startChar = mark.startChar,
                    endChar = endChar,
                    startByte = mark.startByte,
                    endByte = endByte,
                    isVolume = mark.isVolume
                )
            )
        }
        return slices
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

    private fun plausible(title: String, weak: Boolean): Boolean {
        if (title.length > ChapterRule.MAX_TITLE_CHARS) return false
        if (weak) {
            if (Regex("^[0-9]+[.][0-9]").containsMatchIn(title)) return false
            if (title.any { it in "。！？；!?;，," }) return false
        }
        if (Regex("^第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[章节節回卷部篇集](的|里|中|已经|说到|讲的是)").containsMatchIn(title)) return false
        if (Regex("^(正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|外传)(的|里|中|已经|是)").containsMatchIn(title)) return false
        return true
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
