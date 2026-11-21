package com.mirad.reader.ui.common

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 系统级玻璃无障碍与省电信号（参考 Liquid-Glass-Android GlassAccessibility）：
 * - [powerSave]：系统省电模式
 * - [reducedTransparency]：系统「高对比度文字」(high_text_contrast_enabled)，开启时自动退化为高可读不透明表面
 * - [reducedMotion]：系统「移除动画」(areAnimatorsEnabled / animator_duration_scale == 0)，开启时跳过液态弹性与传感器动效
 */
@Immutable
data class GlassSystemCapabilities(
    val powerSave: Boolean = false,
    val reducedTransparency: Boolean = false,
    val reducedMotion: Boolean = false
)

val LocalGlassReducedMotion = staticCompositionLocalOf { false }

internal fun queryReducedTransparency(context: Context): Boolean = runCatching {
    Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
}.getOrDefault(false)

internal fun queryReducedMotion(context: Context): Boolean = runCatching {
    if (!ValueAnimator.areAnimatorsEnabled()) {
        true
    } else {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) <= 0.001f
    }
}.getOrDefault(false)

internal fun queryPowerSaveMode(context: Context): Boolean = runCatching {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isPowerSaveMode == true
}.getOrDefault(false)

@Composable
internal fun rememberGlassSystemCapabilities(): GlassSystemCapabilities {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var capabilities by remember(context) {
        mutableStateOf(
            GlassSystemCapabilities(
                powerSave = queryPowerSaveMode(context),
                reducedTransparency = queryReducedTransparency(context),
                reducedMotion = queryReducedMotion(context)
            )
        )
    }

    fun refresh() {
        val next = GlassSystemCapabilities(
            powerSave = queryPowerSaveMode(context),
            reducedTransparency = queryReducedTransparency(context),
            reducedMotion = queryReducedMotion(context)
        )
        if (capabilities != next) {
            capabilities = next
        }
    }

    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refresh()
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycle.addObserver(observer)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }

    return capabilities
}
