package com.mirad.reader.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.mirad.reader.core.model.GlassAppearance

// 玻璃外观模型与持久化编解码位于 core.model.GlassAppearance；这里只保留界面层的控制器与 CompositionLocal。

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
