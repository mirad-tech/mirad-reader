package com.mirad.reader.tts

/**
 * 把章节正文切成适合朗读的句子，并保留在原文本中的偏移（偏移与传入文本严格对应）。
 *
 * 纯 Kotlin，便于单元测试。
 */
object SentenceSplitter {

    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 110
    const val MAX_MERGE_LENGTH = 200

    data class Sentence(val text: String, val start: Int, val end: Int)

    /** 句末标点（换行也算断句）。 */
    private const val TERMINATORS = "。！？；…!?;"

    /** 句末标点之后可能紧跟的收尾符号。 */
    private const val TRAILING = "\u0022\u0027\u201D\u2019\u300D\u300F\uFF09\uFF09)"

    fun split(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()
        val raw = ArrayList<Sentence>()
        var index = 0
        var start = 0
        val buffer = StringBuilder()

        while (index < text.length) {
            val c = text[index]
            buffer.append(c)
            index++
            val isTerminator = TERMINATORS.indexOf(c) >= 0
            val isNewline = c == '\n'
            val tooLong = buffer.length >= MAX_LENGTH
            if (isTerminator || isNewline || tooLong) {
                if (isTerminator) {
                    while (index < text.length && TRAILING.indexOf(text[index]) >= 0 && buffer.length < MAX_LENGTH) {
                        buffer.append(text[index])
                        index++
                    }
                }
                push(raw, buffer, start)
                start = index
            }
        }
        if (buffer.isNotBlank()) push(raw, buffer, start)

        return mergeShort(raw, text)
    }

    private fun push(out: MutableList<Sentence>, buffer: StringBuilder, start: Int) {
        if (buffer.isEmpty()) return
        val value = buffer.toString()
        buffer.setLength(0)
        val lead = value.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return
        val trail = value.indexOfLast { !it.isWhitespace() } + 1
        if (trail <= lead) return
        val content = value.substring(lead, trail)
        out.add(Sentence(content, start + lead, start + lead + content.length))
    }

    /** 过短的碎片并入上一句，减少朗读时的频繁停顿，但限制合并后的单句总长，防止短句序列无上限膨胀。 */
    private fun mergeShort(sentences: List<Sentence>, source: String): List<Sentence> {
        if (sentences.isEmpty()) return sentences
        val merged = ArrayList<Sentence>(sentences.size)
        for (sentence in sentences) {
            val previous = merged.lastOrNull()
            if (previous != null && (previous.text.length < MIN_LENGTH || sentence.text.length < MIN_LENGTH)) {
                val start = previous.start
                val end = sentence.end.coerceAtMost(source.length)
                if (end > start && (end - start) <= MAX_MERGE_LENGTH) {
                    merged[merged.size - 1] = Sentence(source.substring(start, end), start, end)
                    continue
                }
            }
            merged.add(sentence)
        }
        return merged
    }
}
