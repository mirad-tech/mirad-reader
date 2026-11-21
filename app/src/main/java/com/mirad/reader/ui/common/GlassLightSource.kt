package com.mirad.reader.ui.common

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal val DefaultGlassLightOffset = Offset(GLASS_LIGHT_DEFAULT_X, GLASS_LIGHT_DEFAULT_Y)
internal val DefaultGlassLightDirectionState: State<Offset> = mutableStateOf(DefaultGlassLightOffset)

internal val LocalGlassLightDirection = staticCompositionLocalOf<State<Offset>> {
    DefaultGlassLightDirectionState
}

private const val GRAVITY_EARTH = 9.80665f
private const val LIGHT_SMOOTHING = 0.14f
private const val LIGHT_NOTIFY_EPSILON = 0.012f

/**
 * 将传感器重力向量 (gx, gy) 按屏幕旋转重映射为屏幕空间光线行进方向（单位向量），
 * 设备平放时按倾斜度平滑融合回默认 30°/210° 光源，并叠加低通滤波。
 */
internal fun computeSensorLightDirection(
    gx: Float,
    gy: Float,
    rotation: Int = Surface.ROTATION_0,
    prevX: Float = GLASS_LIGHT_DEFAULT_X,
    prevY: Float = GLASS_LIGHT_DEFAULT_Y,
    smoothing: Float = LIGHT_SMOOTHING
): Pair<Float, Float> {
    val safePrevX = if (prevX.isFinite()) prevX else GLASS_LIGHT_DEFAULT_X
    val safePrevY = if (prevY.isFinite()) prevY else GLASS_LIGHT_DEFAULT_Y
    if (!gx.isFinite() || !gy.isFinite()) {
        val len = sqrt(safePrevX * safePrevX + safePrevY * safePrevY)
        return if (len > 1e-4f) {
            (safePrevX / len) to (safePrevY / len)
        } else {
            GLASS_LIGHT_DEFAULT_X to GLASS_LIGHT_DEFAULT_Y
        }
    }

    val ux: Float
    val uy: Float
    when (rotation) {
        Surface.ROTATION_90 -> {
            ux = -gy
            uy = gx
        }
        Surface.ROTATION_180 -> {
            ux = -gx
            uy = -gy
        }
        Surface.ROTATION_270 -> {
            ux = gy
            uy = -gx
        }
        else -> {
            ux = gx
            uy = gy
        }
    }

    val planar = sqrt(ux * ux + uy * uy)
    val tilt = (planar / GRAVITY_EARTH).coerceIn(0f, 1f)

    var tx = GLASS_LIGHT_DEFAULT_X
    var ty = GLASS_LIGHT_DEFAULT_Y
    if (planar > 0.5f) {
        val inv = 1f / planar
        val sx = -ux * inv
        val sy = uy * inv
        tx = sx * tilt + GLASS_LIGHT_DEFAULT_X * (1f - tilt)
        ty = sy * tilt + GLASS_LIGHT_DEFAULT_Y * (1f - tilt)
    }
    val tLen = sqrt(tx * tx + ty * ty)
    if (tLen > 1e-4f) {
        tx /= tLen
        ty /= tLen
    }

    val safeSmoothing = if (smoothing.isFinite()) smoothing else LIGHT_SMOOTHING
    val alpha = safeSmoothing.coerceIn(0.01f, 1f)
    var nx = safePrevX + (tx - safePrevX) * alpha
    var ny = safePrevY + (ty - safePrevY) * alpha
    val nLen = sqrt(nx * nx + ny * ny)
    if (nLen > 1e-4f) {
        nx /= nLen
        ny /= nLen
    } else {
        nx = GLASS_LIGHT_DEFAULT_X
        ny = GLASS_LIGHT_DEFAULT_Y
    }
    return nx to ny
}

/** 量化到 0.01（1/100），静止或微抖时不触发重绘与 RenderEffect 重建。 */
internal fun quantizeLightDirection(x: Float, y: Float): Pair<Float, Float> {
    if (!x.isFinite() || !y.isFinite()) {
        return GLASS_LIGHT_DEFAULT_X to GLASS_LIGHT_DEFAULT_Y
    }
    val qx = (x * 100f).roundToInt() / 100f
    val qy = (y * 100f).roundToInt() / 100f
    return qx to qy
}

/**
 * 仅在 [active] 为 true、API 33+ 且生命周期处于 RESUMED 时注册重力传感器；
 * 返回 [State]，仅在玻璃组件的绘制阶段（`drawWithContent`）读取 `.value`，实现零重组、仅重绘；
 * 进入后台、阅读页、省电模式、移除动画或用户关闭「动态高光」时立即注销，零后台耗电。
 */
@Composable
internal fun rememberGlassLightDirection(active: Boolean): State<Offset> {
    val directionState = remember { mutableStateOf(DefaultGlassLightOffset) }
    if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        if (directionState.value != DefaultGlassLightOffset) {
            directionState.value = DefaultGlassLightOffset
        }
        return directionState
    }
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(context, view, lifecycle, active) {
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var registered = false
        var smoothX = GLASS_LIGHT_DEFAULT_X
        var smoothY = GLASS_LIGHT_DEFAULT_Y
        var lastQuantX = GLASS_LIGHT_DEFAULT_X
        var lastQuantY = GLASS_LIGHT_DEFAULT_Y

        fun resetFilterState() {
            smoothX = GLASS_LIGHT_DEFAULT_X
            smoothY = GLASS_LIGHT_DEFAULT_Y
            lastQuantX = GLASS_LIGHT_DEFAULT_X
            lastQuantY = GLASS_LIGHT_DEFAULT_Y
            directionState.value = DefaultGlassLightOffset
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 2) return
                val gx = event.values[0]
                val gy = event.values[1]
                if (!gx.isFinite() || !gy.isFinite()) return
                val rotation = view.display?.rotation ?: Surface.ROTATION_0
                val (nx, ny) = computeSensorLightDirection(
                    gx = gx,
                    gy = gy,
                    rotation = rotation,
                    prevX = smoothX,
                    prevY = smoothY
                )
                smoothX = nx
                smoothY = ny
                val (qx, qy) = quantizeLightDirection(nx, ny)
                if (abs(qx - lastQuantX) >= LIGHT_NOTIFY_EPSILON || abs(qy - lastQuantY) >= LIGHT_NOTIFY_EPSILON) {
                    lastQuantX = qx
                    lastQuantY = qy
                    directionState.value = Offset(qx, qy)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun updateRegistration() {
            val shouldRegister = active && sensor != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (shouldRegister && !registered) {
                registered = sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) == true
                if (!registered) {
                    resetFilterState()
                }
            } else if (!shouldRegister && registered) {
                sm?.unregisterListener(listener)
                registered = false
                resetFilterState()
            }
        }

        val observer = LifecycleEventObserver { _, _ -> updateRegistration() }
        lifecycle.addObserver(observer)
        updateRegistration()

        onDispose {
            lifecycle.removeObserver(observer)
            if (registered) {
                sm?.unregisterListener(listener)
                registered = false
            }
            resetFilterState()
        }
    }

    return directionState
}
