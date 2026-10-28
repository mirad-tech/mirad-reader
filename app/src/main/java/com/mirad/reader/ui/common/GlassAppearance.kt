package com.mirad.reader.ui.common

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
/**
 * 玻璃外观。
 *
 * 用户可见语义已从"透明度百分比"改为两个独立控制：
 * - [enabled]：磨砂玻璃开关。关闭后走完全不透明的稳定表面，不再做模糊、折射与背景分析。
 * - [clarityPercent]：清晰度，0 = 最朦胧，100 = 最清晰；它实际改变模糊半径，两端都不承担"关闭"含义。
 *
 * [transparencyPercent] 只是给旧版 APK 的兼容投影（关闭写 0，开启按清晰度单调投影 1..100）。
 * 旧值控制的是遮罩浓度，新值控制的是模糊量，两者视觉不保证严格相等，新记录才是事实源。
 */
data class GlassAppearance(
    val enabled: Boolean = true,
    val clarityPercent: Int = DEFAULT_CLARITY
) {
    /** 兼容投影：只用于写回旧键，不得反向覆盖新清晰度。 */
    val transparencyPercent: Int
        get() = if (!enabled) 0 else clarityPercent.coerceIn(MIN_CLARITY, MAX_CLARITY).coerceAtLeast(1)

    val transparencyFraction: Float get() = transparencyPercent / 100f

    fun normalized(): GlassAppearance = GlassAppearance(enabled, clarityPercent.coerceIn(MIN_CLARITY, MAX_CLARITY))

    companion object {
        const val MIN_CLARITY = 0
        const val MAX_CLARITY = 100

        /** 校准点：仓库现有观感对应的清晰度，改造后默认值保持同一档。 */
        const val DEFAULT_CLARITY = 60
        const val DEFAULT_PERCENT = DEFAULT_CLARITY

        val DEFAULT = GlassAppearance(enabled = true, clarityPercent = DEFAULT_CLARITY)

        /** 旧键迁移：0 → 关闭（清晰度取校准默认），>0 → 开启并把旧值当作清晰度初值。 */
        fun fromLegacyPercent(raw: String?): GlassAppearance {
            val parsed = raw?.toIntOrNull() ?: return DEFAULT
            return if (parsed <= 0) {
                GlassAppearance(enabled = false, clarityPercent = DEFAULT_CLARITY)
            } else {
                GlassAppearance(enabled = true, clarityPercent = parsed.coerceIn(1, MAX_CLARITY))
            }
        }

        /** 兼容旧调用：把百分比当成清晰度读（仅用于历史测试与预览参数）。 */
        fun parse(raw: String?): GlassAppearance = fromLegacyPercent(raw)
    }
}

/** 玻璃外观的版本化记录（偏好表 JSON）。字段缺失或损坏都回退默认，且不触发旧值重新迁移。 */
@kotlinx.serialization.Serializable
private data class GlassAppearanceRecord(
    val enabled: Boolean = true,
    val clarityPercent: Int = GlassAppearance.DEFAULT_CLARITY
)

internal object GlassAppearanceJson {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(raw: String?): GlassAppearance {
        if (raw.isNullOrBlank()) return GlassAppearance.DEFAULT
        return runCatching {
            val record = json.decodeFromString(GlassAppearanceRecord.serializer(), raw)
            GlassAppearance(record.enabled, record.clarityPercent).normalized()
        }.getOrDefault(GlassAppearance.DEFAULT)
    }

    fun encode(appearance: GlassAppearance): String =
        json.encodeToString(
            GlassAppearanceRecord.serializer(),
            GlassAppearanceRecord(appearance.enabled, appearance.normalized().clarityPercent)
        )
}

interface GlassAppearanceController {
    val saveFailed: Boolean get() = false
    fun update(appearance: GlassAppearance)
    fun commit()
    fun reset()
    fun retrySave() = commit()
}

val LocalGlassAppearance = staticCompositionLocalOf { GlassAppearance.DEFAULT }
val LocalGlassSaveFailed = staticCompositionLocalOf { false }
val LocalGlassAppearanceController = staticCompositionLocalOf<GlassAppearanceController> {
    object : GlassAppearanceController {
        override fun update(appearance: GlassAppearance) {}
        override fun commit() {}
        override fun reset() {}
    }
}
