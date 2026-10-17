package com.mirad.reader.core.text

/**
 * 分章规则：一组正则，按行匹配章节标题。
 */
class ChapterRule(
    val id: String,
    val name: String,
    patterns: List<String>
) {
    private val regexes: List<Regex> = patterns.map { Regex(it) }

    /** 是否为章节标题行（同时限制长度，避免把正文段落误判为标题）。 */
    fun matches(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TITLE_CHARS) return false
        return regexes.any { it.matches(trimmed) }
    }

    companion object {
        /** 章节标题一般不超此长度。 */
        const val MAX_TITLE_CHARS = 80
    }
}

/** 内置分章规则集。 */
object ChapterRules {

    /** 不使用分章，按字数兜底切分。 */
    const val NONE = "none"
    const val CN_GENERIC = "cn_generic"
    const val CN_VOLUME_ONLY = "cn_volume"
    const val CN_WITH_BRACKET = "cn_bracket"
    const val NUMBERED = "numbered"
    const val ENGLISH = "english"
    const val SPECIAL = "special"

    private const val CN_NUM = "[0-9０-９零〇一二三四五六七八九十百千万两]+"

    val CN_GENERIC_RULE = ChapterRule(
        id = CN_GENERIC,
        name = "中文章节（第X章/节/回）",
        patterns = listOf(
            "^第\\s*$CN_NUM\\s*[章节節回][\\s\\p{Punct}]*.{0,70}$",
            "^第\\s*$CN_NUM\\s*[章节節回]\\s*.{0,70}$",
            "^(正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外)[\\s\\p{Punct}]*.{0,70}$"
        )
    )

    val CN_VOLUME_RULE = ChapterRule(
        id = CN_VOLUME_ONLY,
        name = "分卷（第X卷/部/篇）",
        patterns = listOf(
            "^第\\s*$CN_NUM\\s*[卷部篇集][\\s\\p{Punct}]*.{0,70}$"
        )
    )

    val CN_BRACKET_RULE = ChapterRule(
        id = CN_WITH_BRACKET,
        name = "带括号章节（【第X章】）",
        patterns = listOf(
            "^[【〖\\[]\\s*第\\s*$CN_NUM\\s*[章节節回卷][^】〗\\]]{0,70}[】〗\\]].{0,70}$",
            "^[【〖\\[]\\s*$CN_NUM\\s*[】〗\\]].{0,70}$",
            // 【后记】/【番外】这类不带编号的括号标题：短、独占一行才算
            "^[【〖\\[]\\s*(正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|外传)\\s*[】〗\\]].{0,30}$"
        )
    )

    val NUMBERED_RULE = ChapterRule(
        id = NUMBERED,
        name = "数字编号（1. / 1、/ 1 标题）",
        patterns = listOf(
            "^\\d{1,4}\\s*[.、,，:：]\\s*\\S.{0,70}$",
            // 数字 + 空格 + 短标题（"1 初见"）。必须限长，否则"2019 年他在城南…"这类正文会被误收。
            "^\\d{1,4}\\s+\\S.{0,20}$",
            "^\\d{1,4}\\s*$"
        )
    )

    val ENGLISH_RULE = ChapterRule(
        id = ENGLISH,
        name = "英文章节（Chapter N）",
        patterns = listOf(
            "^(?i:chapter|part|book|section)\\s+([0-9]+|[ivxlcdm]+)\\b.{0,70}$"
        )
    )

    val SPECIAL_RULE = ChapterRule(
        id = SPECIAL,
        name = "特殊标记（正文/序章/楔子）",
        patterns = listOf(
            "^(正文|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|外传)[\\s\\p{Punct}]*.{0,70}$"
        )
    )

    val ALL: List<ChapterRule> = listOf(
        CN_GENERIC_RULE,
        CN_BRACKET_RULE,
        CN_VOLUME_RULE,
        NUMBERED_RULE,
        ENGLISH_RULE,
        SPECIAL_RULE
    )

    fun byId(id: String?): ChapterRule? = ALL.firstOrNull { it.id == id }

    /** 用户自定义正则规则。 */
    fun custom(regex: String): ChapterRule? =
        runCatching { ChapterRule("custom", "自定义正则", listOf(regex)) }.getOrNull()

    /**
     * 自动挑选分章规则：委托给 [ChapterDetector]，会做伪标题过滤与密度/序号校验，
     * 不再单纯比命中次数。
     */
    fun autoDetect(text: String, rules: List<ChapterRule> = ALL): Pair<ChapterRule?, Int> {
        val detection = ChapterDetector.detect(text, rules)
        return detection.rule to detection.hits
    }
}
