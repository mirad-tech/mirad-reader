package com.mirad.reader.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 间距令牌：全项目只引用这里的值，避免每个页面各自写魔法数字。
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

/**
 * 尺寸令牌。
 */
object Sizes {
    /** 内容区左右边距。 */
    val screenPadding = Spacing.lg

    /** 卡片内边距。 */
    val cardPaddingH = 14.dp
    val cardPaddingV = 12.dp

    /** 网格封面比例（宽/高）。 */
    const val coverAspect = 0.72f

    /** 列表行最小高度，保证触控目标够大。 */
    val rowMinHeight = 56.dp

    /** 设置首页图标底的尺寸。 */
    val settingsIconTile = 36.dp

    /** 弹层内容最大高度占比。 */
    const val sheetMaxHeightFraction = 0.85f

    /** 书籍封面（列表/操作面板）。 */
    val coverSmallWidth = 44.dp
    val coverSmallHeight = 60.dp
    val coverCorner = 8.dp
}

/** 浮动栏位于分组卡片和封面之上。 */
object Elevation {
    val bookCover = 3.dp
    val settingsGroup = 4.dp
    val floatingBar = 8.dp
}

@Immutable
data class CoverTone(val container: Color, val onContainer: Color)

/**
 * Material3 配色之外的语义色：搜索命中、朗读高亮、笔记高亮、封面遮罩等。
 * 放在 CompositionLocal 里，避免这些颜色散落在各个页面硬编码。
 */
@Immutable
data class MiradColors(
    val searchHighlight: Color,
    val ttsHighlight: Color,
    val noteHighlight: Color,
    val coverScrim: Color,
    val chartEmpty: Color,
    val selectionCheck: Color,
    /** 深色色块上的勾选颜色（对比色，不随主题走）。 */
    val checkOnDarkSwatch: Color,
    val coverTones: List<CoverTone>
)

val LightMiradColors = MiradColors(
    searchHighlight = Color(0x66D98324),
    ttsHighlight = Color(0x4DFFB35C),
    noteHighlight = Color(0x66F2C14E),
    coverScrim = Color(0x38000000),
    chartEmpty = Color(0x1F2F6B58),
    selectionCheck = Color(0xFF2F6B58),
    checkOnDarkSwatch = Color(0xFFFFFFFF),
    coverTones = listOf(0xFF2F6B58, 0xFF3E5C76, 0xFF8A5A3B, 0xFF8C3F3F, 0xFF63506E, 0xFF4A5A52)
        .map { CoverTone(Color(it), Color(0xFFFCFBF7)) }
)

val DarkMiradColors = MiradColors(
    searchHighlight = Color(0x66E0A458),
    ttsHighlight = Color(0x4DFFB35C),
    noteHighlight = Color(0x66E0C08A),
    coverScrim = Color(0x59000000),
    chartEmpty = Color(0x338FD3BC),
    selectionCheck = Color(0xFF9BD3BB),
    checkOnDarkSwatch = Color(0xFFFFFFFF),
    coverTones = listOf(0xFF1D5B45, 0xFF2C4458, 0xFF6B4429, 0xFF6E2F2F, 0xFF4B3B56, 0xFF37443D)
        .map { CoverTone(Color(it), Color(0xFFE3E6E2)) }
)

val LocalMiradColors = staticCompositionLocalOf { LightMiradColors }
