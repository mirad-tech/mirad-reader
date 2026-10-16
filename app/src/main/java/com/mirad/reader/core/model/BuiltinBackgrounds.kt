package com.mirad.reader.core.model

/**
 * 内置阅读背景。素材放在 assets/backgrounds 下，URI 用 builtin:<key> 表示。
 *
 * 之所以用图片而不是纯色：低对比纹理能减少大面积纯色带来的眩光，长时间阅读更舒服。
 */
data class BuiltinBackground(
    val key: String,
    val label: String,
    /** assets 内的相对路径。 */
    val assetName: String,
    val isDark: Boolean,
    /** 选中该背景时建议同时切换到的主题，避免深色底配深色字。 */
    val suggestedTheme: String
) {
    val uri: String get() = BuiltinBackgrounds.SCHEME + key
}

/** 内置背景注册表。 */
object BuiltinBackgrounds {

    const val SCHEME = "builtin:"

    const val PAPER = "paper"
    const val PARCHMENT = "parchment"
    const val LINEN = "linen"
    const val GRAPH = "graph"
    const val DAWN = "dawn"
    const val GREEN = "green"
    const val NIGHT = "night"

    val ALL: List<BuiltinBackground> = listOf(
        BuiltinBackground(PAPER, "纸纹", "backgrounds/paper.jpg", false, ThemeKey.PAPER),
        BuiltinBackground(PARCHMENT, "羊皮纸", "backgrounds/parchment.jpg", false, ThemeKey.SEPIA),
        BuiltinBackground(LINEN, "亚麻布", "backgrounds/linen.jpg", false, ThemeKey.PAPER),
        BuiltinBackground(GRAPH, "淡格纸", "backgrounds/graph.jpg", false, ThemeKey.LIGHT),
        BuiltinBackground(DAWN, "柔光", "backgrounds/dawn.jpg", false, ThemeKey.LIGHT),
        BuiltinBackground(GREEN, "护眼绿", "backgrounds/green.jpg", false, ThemeKey.GREEN),
        BuiltinBackground(NIGHT, "夜间暗纹", "backgrounds/night.jpg", true, ThemeKey.DARK)
    )

    fun byKey(key: String): BuiltinBackground? = ALL.firstOrNull { it.key == key }

    /** 从配置里的 URI 还原内置背景；不是内置背景则返回 null。 */
    fun fromUri(uri: String?): BuiltinBackground? {
        if (uri.isNullOrBlank() || !uri.startsWith(SCHEME)) return null
        return byKey(uri.removePrefix(SCHEME))
    }

    fun assetNames(): List<String> = ALL.map { it.assetName }

    /** 内置背景素材的目录（用于校验素材是否齐全）。 */
    const val ASSET_DIR = "backgrounds"
}
