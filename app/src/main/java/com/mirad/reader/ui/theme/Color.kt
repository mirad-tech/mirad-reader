package com.mirad.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「纸墨」配色：暖白纸面 + 墨绿主色 + 铜色点缀。
 *
 * 注意：Material3 的 surfaceContainer* 等角色必须一起覆盖，否则会落回默认色板
 * （默认是紫色系），出现"绿主题配紫控件"的割裂感。
 */
object Ink {
    // 主色：墨绿
    val Green10 = Color(0xFF0A2A21)
    val Green20 = Color(0xFF11402F)
    val Green30 = Color(0xFF1D5B45)
    val Green40 = Color(0xFF2F6B58)
    val Green70 = Color(0xFF7FBCA4)
    val Green80 = Color(0xFF9BD3BB)
    val Green90 = Color(0xFFC3E9D9)
    val Green95 = Color(0xFFDCF2E7)

    // 次色：灰绿
    val Sage30 = Color(0xFF2C3A33)
    val Sage40 = Color(0xFF4A5A52)
    val Sage80 = Color(0xFFB4C6BC)
    val Sage90 = Color(0xFFD3E0D8)

    // 点缀：铜色（高亮、强调数字）
    val Bronze40 = Color(0xFF8A6A3B)
    val Bronze80 = Color(0xFFE0C08A)

    // 纸面
    val Paper = Color(0xFFFCFBF7)
    val PaperDim = Color(0xFFF4F1EA)
    val PaperContainer = Color(0xFFEFEBE2)
    val PaperContainerHigh = Color(0xFFE8E4DA)
    val PaperLowest = Color(0xFFFFFFFF)
    val InkText = Color(0xFF191D1B)
    val InkTextMuted = Color(0xFF4C554F)
    val Outline = Color(0xFFB6BCB5)
    val OutlineVariant = Color(0xFFDBD7CD)

    // 夜间
    val Night = Color(0xFF15181A)
    val NightDim = Color(0xFF0F1214)
    val NightContainer = Color(0xFF1F2426)
    val NightContainerHigh = Color(0xFF272D2F)
    val NightLowest = Color(0xFF0B0E0F)
    val NightText = Color(0xFFE3E6E2)
    val NightTextMuted = Color(0xFFB7BFB9)
    val NightOutline = Color(0xFF4C554F)
    val NightOutlineVariant = Color(0xFF2C3230)

    val Error = Color(0xFFB3261E)
    val ErrorDark = Color(0xFFF2B8B5)
}
