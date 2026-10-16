package com.mirad.reader.core.text

/**
 * 一章的定位信息。[startChar, endChar) 覆盖归一化正文的连续区间，[startByte, endByte) 为对应
 * UTF-8 字节区间，因此章节之间无缝且不重叠。
 */
data class ChapterSlice(
    val title: String,
    /** 标题行（含换行）的字符数；0 表示该章没有独占标题行（如"前言"或字数兜底分节）。 */
    val titleLineChars: Int,
    val startChar: Int,
    val endChar: Int,
    val startByte: Long,
    val endByte: Long,
    val isVolume: Boolean
) {
    val charCount: Int get() = endChar - startChar
}

/**
 * 分章器：单次遍历同时累计字符偏移与 UTF-8 字节偏移。
 */
object ChapterSplitter {

    /** 无可用规则时的兜底分节长度（约 2~3 页，章节列表才不至于只有两三节）。 */
    const val FALLBACK_CHUNK_CHARS = 3000

    private const val PREFACE_TITLE = "前言"

    fun split(
        text: String,
        rule: ChapterRule?,
        fallbackChunkChars: Int = FALLBACK_CHUNK_CHARS
    ): List<ChapterSlice> {
        if (text.isEmpty()) return emptyList()

        data class Mark(val title: String, val titleLineChars: Int, val startChar: Int, val startByte: Long, val isVolume: Boolean)

        val marks = ArrayList<Mark>()
        var charIdx = 0
        var byteIdx = 0L
        while (charIdx < text.length) {
            var nl = text.indexOf('\n', charIdx)
            if (nl < 0) nl = text.length
            val lineEndWithNl = nl + if (nl < text.length) 1 else 0
            val line = text.substring(charIdx, nl)

            val isVolume = ChapterRules.CN_VOLUME_RULE.matches(line)
            val isChapter = (rule != null && rule.matches(line)) ||
                (isVolume && rule?.id != ChapterRules.CN_VOLUME_ONLY)

            if (isChapter && line.isNotBlank()) {
                marks.add(
                    Mark(
                        title = line.trim(),
                        titleLineChars = lineEndWithNl - charIdx,
                        startChar = charIdx,
                        startByte = byteIdx,
                        isVolume = isVolume
                    )
                )
            }

            byteIdx += TextNormalizer.utf8Length(text, charIdx, lineEndWithNl)
            charIdx = lineEndWithNl
        }

        if (marks.isEmpty()) {
            return fallbackChunks(text, fallbackChunkChars)
        }

        val slices = ArrayList<ChapterSlice>(marks.size + 1)
        val totalBytes = byteIdx

        // 第一个标题之前的内容作为"前言"独立成章。
        if (marks.first().startChar > 0 && text.substring(0, marks.first().startChar).isNotBlank()) {
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
            val endChar = next?.startChar ?: text.length
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

    /** 无章节标记时按近似字数在行边界处切分。 */
    private fun fallbackChunks(text: String, chunkChars: Int): List<ChapterSlice> {
        val size = chunkChars.coerceAtLeast(500)
        val slices = ArrayList<ChapterSlice>()
        var chapterNo = 0
        var charIdx = 0
        var byteIdx = 0L
        var chunkStartChar = 0
        var chunkStartByte = 0L
        var chunkCharsAcc = 0

        while (charIdx < text.length) {
            var nl = text.indexOf('\n', charIdx)
            if (nl < 0) nl = text.length
            val lineEndWithNl = nl + if (nl < text.length) 1 else 0
            chunkCharsAcc += lineEndWithNl - charIdx
            byteIdx += TextNormalizer.utf8Length(text, charIdx, lineEndWithNl)
            charIdx = lineEndWithNl

            if (chunkCharsAcc >= size || charIdx >= text.length) {
                chapterNo++
                slices.add(
                    ChapterSlice(
                        title = "第 $chapterNo 节",
                        titleLineChars = 0,
                        startChar = chunkStartChar,
                        endChar = charIdx,
                        startByte = chunkStartByte,
                        endByte = byteIdx,
                        isVolume = false
                    )
                )
                chunkStartChar = charIdx
                chunkStartByte = byteIdx
                chunkCharsAcc = 0
            }
        }
        return slices
    }
}
