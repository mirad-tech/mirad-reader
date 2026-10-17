package com.mirad.reader.core.text

data class ChapterMark(val title: String, val titleLineChars: Int, val startChar: Int, val startByte: Long, val isVolume: Boolean)
data class ChapterDetection(
    val rule: ChapterRule?, val hits: Int, val avgChars: Int,
    val titlesPreview: List<String>, val reason: String,
    val marks: List<ChapterMark> = emptyList()
)

object ChapterDetector {
    fun detect(text: String, rules: List<ChapterRule> = ChapterRules.ALL): ChapterDetection {
        val priority = listOf(ChapterRules.CN_GENERIC, ChapterRules.CN_WITH_BRACKET, ChapterRules.ENGLISH,
            ChapterRules.SPECIAL, ChapterRules.NUMBERED, ChapterRules.CN_VOLUME_ONLY)
        val ordered = rules.sortedBy { rule -> priority.indexOf(rule.id).let { if (it < 0) priority.size else it } }
        for (rule in ordered) {
            val marks = scan(text, rule)
            if (marks.isEmpty()) continue
            val weak = rule.id == ChapterRules.NUMBERED
            val spans = marks.mapIndexed { i, mark -> (marks.getOrNull(i + 1)?.startChar ?: text.length) - mark.startChar }
            if (weak) {
                if (marks.size < 3) continue
                // 弱数字标题（如 "1 初见"）真正的判据是"连续编号 + 篇幅大体均匀"，
                // 而不是"每章至少 300 字"：短章书会被那条硬阈值整体否掉，退化成自动分节。
                val numbers = marks.mapNotNull { chapterNumber(it.title) }
                if (numbers.size < 3) continue
                val consecutive = numbers.zipWithNext().count { (a, b) -> b == a + 1 }
                if (consecutive < numbers.size - 1) continue
                val avgSpan = spans.average()
                val maxSpan = spans.maxOrNull() ?: 0
                if (avgSpan < 120 || maxSpan > avgSpan * 4) continue
            }
            val groups = mutableListOf<MutableList<Int>>(mutableListOf())
            marks.forEach { mark ->
                if (mark.isVolume) groups.add(mutableListOf())
                else chapterNumber(mark.title)?.let { groups.last().add(it) }
            }
            if (groups.any { !isMonotonic(it) }) continue
            val avg = text.length / marks.size
            return ChapterDetection(rule, marks.size, avg, marks.take(5).map { it.title },
                "${rule.name}：${marks.size} 章，平均 $avg 字", marks)
        }
        return ChapterDetection(null, 0, 0, emptyList(), "没有找到可靠的章节标记，按篇幅自动分节")
    }

    private fun scan(text: String, rule: ChapterRule): List<ChapterMark> {
        val marks = mutableListOf<ChapterMark>()
        var pos = 0
        var bytes = 0L
        val combineStrong = rule.id in setOf(ChapterRules.CN_GENERIC, ChapterRules.CN_WITH_BRACKET, ChapterRules.ENGLISH, ChapterRules.SPECIAL)
        var ownMatch = false
        while (pos < text.length) {
            val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val next = if (end < text.length) end + 1 else end
            val title = text.substring(pos, end).trim()
            val volume = ChapterRules.CN_VOLUME_RULE.matches(title)
            val volumeOnly = volume && !Regex("[章节節回]").containsMatchIn(title)
            val own = rule.matches(title)
            val matched = own || (combineStrong &&
                (ChapterRules.CN_GENERIC_RULE.matches(title) || ChapterRules.CN_BRACKET_RULE.matches(title) ||
                 ChapterRules.SPECIAL_RULE.matches(title) || ChapterRules.ENGLISH_RULE.matches(title))) || (rule.id != ChapterRules.NUMBERED && volume)
            if (matched && plausible(title, rule.id == ChapterRules.NUMBERED)) {
                marks.add(ChapterMark(title, next - pos, pos, bytes, volumeOnly))
                if (own) ownMatch = true
            }
            bytes += TextNormalizer.utf8Length(text, pos, next)
            pos = next
        }
        return if (!ownMatch) emptyList() else marks
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

    /** 章序号：阿拉伯数字或中文数字，取不到（序章/楔子等）返回 null。 */
    private fun chapterNumber(title: String): Int? {
        Regex("^[^0-9０-９零〇一二三四五六七八九十百千万两]{0,4}([0-9０-９]{1,4})").find(title)?.let {
            return fullWidthToHalf(it.groupValues[1]).toIntOrNull()
        }
        Regex("([零〇一二三四五六七八九十百千万两]{1,8})\\s*[章节節回卷部篇集]").find(title)?.let {
            return chineseToInt(it.groupValues[1])
        }
        return null
    }

    private fun fullWidthToHalf(text: String): String = text.map { ch ->
        if (ch.code in 0xFF10..0xFF19) (ch.code - 0xFF10 + '0'.code).toChar() else ch
    }.joinToString("")

    private fun chineseToInt(text: String): Int? {
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
        var total = 0
        var section = 0
        var current = 0
        for (ch in text) {
            if (digits.containsKey(ch)) {
                current = digits.getValue(ch)
            } else if (units.containsKey(ch)) {
                val unit = units.getValue(ch)
                if (unit == 10000) {
                    section = (section + current) * unit
                    total += section
                    section = 0
                } else {
                    section += (if (current == 0) 1 else current) * unit
                }
                current = 0
            } else {
                return null
            }
        }
        return total + section + current
    }

    /** 序号应大体递增：允许少量缺失/补充，但 80% 以上相邻对应递增。 */
    private fun isMonotonic(numbers: List<Int>): Boolean {
        if (numbers.size < 3) return true
        var increasing = 0
        var pairs = 0
        for (i in 1 until numbers.size) {
            pairs++
            if (numbers[i] >= numbers[i - 1]) increasing++
        }
        return pairs == 0 || increasing.toDouble() / pairs >= 0.8
    }
}
