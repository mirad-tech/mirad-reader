package com.mirad.reader.core.text

/** 分章判定结果，供界面展示"为什么这样分"。 */
data class ChapterDetection(
    val rule: ChapterRule?,
    val hits: Int,
    val avgChars: Int,
    val titlesPreview: List<String>,
    val reason: String
)

/**
 * 章节标记判定。
 *
 * 旧实现是"命中次数最多者胜"，于是数字规则（`^\d{1,4}$`、"1、xxx"）总能靠正文里的
 * "3.5公里""1、他走进屋子"之类把命中数刷上去，把一本书切得稀碎。
 *
 * 现在改为：先做伪标题过滤，再做硬校验（数量/密度/序号单调），最后按"规则强度"择优。
 */
object ChapterDetector {

    private const val MIN_CHAPTERS = 3
    /** 平均每章至少这么多字，否则视为误判（数字列表式假章节的典型特征）。 */
    private const val MIN_AVG_CHARS = 300
    private const val MAX_TITLE_CHARS = 20

    /** 句末标点：出现在标题行里基本可断定是正文而非标题。 */
    private val SENTENCE_PUNCT = charArrayOf('。', '！', '？', '；', '!', '?', ';', '，', '、', ',')

    /**
     * 关键词之后紧跟这些字，说明是正文在"接着往下说"（如"第一章的内容我已经看过了"）。
     */
    private const val CONTINUATION_CHARS = "的了是在中里上下我你他她它们就都也又还而但因为所以对从向把被让使会能要想说道问答看见听到去来走过着得地很太更最不没无有一二三四五"

    /** 关键词锚定的规则优先级更高；数字编号规则最弱，卷规则仅在别无选择时用。 */
    private val STRONG_ORDER = listOf(
        ChapterRules.CN_GENERIC,
        ChapterRules.CN_WITH_BRACKET,
        ChapterRules.SPECIAL,
        ChapterRules.ENGLISH
    )
    private val WEAK_ORDER = listOf(ChapterRules.NUMBERED)
    private val LAST_RESORT = listOf(ChapterRules.CN_VOLUME_ONLY)

    fun detect(text: String, rules: List<ChapterRule> = ChapterRules.ALL): ChapterDetection {
        if (text.isBlank()) return ChapterDetection(null, 0, 0, emptyList(), "正文为空")

        val lines = text.lineSequence().toList()
        val ordered = orderRules(rules)

        // 校验不通过的规则一律不采用（否则数字噪声又能蒙混过关）；
        // 但把最有希望的那条的原因记录下来，界面可以解释"为什么按篇幅分节"。
        var rejectedNote: String? = null

        for (rule in ordered) {
            val probe = probe(rule, lines, text.length)
            if (!probe.valid) {
                if (probe.hits >= MIN_CHAPTERS && rejectedNote == null) rejectedNote = probe.note
                continue
            }
            return ChapterDetection(
                rule = rule,
                hits = probe.hits,
                avgChars = probe.avgChars,
                titlesPreview = probe.titles,
                reason = "${rule.name}：${probe.hits} 章，平均 ${probe.avgChars} 字"
            )
        }

        return ChapterDetection(
            rule = null,
            hits = 0,
            avgChars = 0,
            titlesPreview = emptyList(),
            reason = rejectedNote?.let { "没有可靠的章节标记（$it），按篇幅自动分节" }
                ?: "没有找到可靠的章节标记，按篇幅自动分节"
        )
    }

    private fun orderRules(rules: List<ChapterRule>): List<ChapterRule> {
        val byId = rules.associateBy { it.id }
        val ordered = ArrayList<ChapterRule>(rules.size)
        (STRONG_ORDER + WEAK_ORDER + LAST_RESORT).forEach { id ->
            byId[id]?.let { ordered.add(it) }
        }
        rules.forEach { if (ordered.none { it.id == it.id }) ordered.add(it) }
        return ordered
    }

    private class Probe(
        val rule: ChapterRule,
        val hits: Int,
        val avgChars: Int,
        val titles: List<String>,
        val valid: Boolean,
        val note: String
    )

    private fun probe(rule: ChapterRule, lines: List<String>, totalChars: Int): Probe {
        val titles = ArrayList<String>(16)
        val numbers = ArrayList<Int>(16)
        var hits = 0
        var rejected = 0

        for (index in lines.indices) {
            val raw = lines[index]
            if (raw.isBlank()) continue
            if (!rule.matches(raw)) continue
            val title = raw.trim()
            if (!isPlausibleTitle(title)) {
                rejected++
                continue
            }
            hits++
            if (titles.size < 5) titles.add(title)
            chapterNumber(title)?.let { numbers.add(it) }
        }

        if (hits == 0) {
            return Probe(rule, 0, 0, titles, valid = false, note = "${rule.name}：无命中")
        }
        val avg = totalChars / hits
        val monotonic = isMonotonic(numbers)
        val tooManyRejected = rejected > hits
        val valid = hits >= MIN_CHAPTERS && avg >= MIN_AVG_CHARS && monotonic && !tooManyRejected
        val note = buildString {
            append(rule.name).append("：命中 ").append(hits).append(" 处")
            if (avg < MIN_AVG_CHARS) append("，平均仅 ").append(avg).append(" 字（疑似误判）")
            if (!monotonic) append("，序号不递增（疑似误判）")
            if (tooManyRejected) append("，多数行不像标题")
        }
        return Probe(rule, hits, avg, titles, valid, note)
    }

    /** 伪标题过滤：标题短、无句读、关键词后面不是"接着说话"。 */
    private fun isPlausibleTitle(title: String): Boolean {
        if (title.length > MAX_TITLE_CHARS) return false
        for (ch in title) {
            if (SENTENCE_PUNCT.contains(ch)) return false
        }
        val tail = tailAfterKeyword(title)
        if (tail.isNotEmpty() && CONTINUATION_CHARS.indexOf(tail[0]) >= 0) return false
        return true
    }

    /** 去掉开头的"第X章/1./Chapter N"等标记与标点后的剩余文本。 */
    private fun tailAfterKeyword(title: String): String {
        var text = title
        text = text.replace(Regex("^[【〖\\[「『]"), "")
        text = text.replace(Regex("^(?i)(chapter|part|book|section)\\s+[0-9ivxlcdm]+"), "")
        text = text.replace(Regex("^第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[章节節回卷部篇集]"), "")
        text = text.replace(Regex("^[0-9０-９]{1,4}\\s*[.、,，:：]"), "")
        text = text.replace(Regex("^[【〖\\[「『]?\\s*(正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|外传)"), "")
        return text.trimStart(' ', '\t', '　', '：', ':', '.', '、', '-', '—', '】', '〗', ']', '」', '』', '）', ')')
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
