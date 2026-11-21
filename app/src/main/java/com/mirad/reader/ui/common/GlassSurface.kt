package com.mirad.reader.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mirad.reader.ui.design.Elevation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

/** Preview overrides only: neither value is persisted or exposed in the product. */
val LocalGlassLegacy = staticCompositionLocalOf { false }
val LocalGlassForceFallback = staticCompositionLocalOf { false }
val LocalGlassLensEnabled = staticCompositionLocalOf { true }

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

/**
 * 显式标记当前子树是否位于真正激活且处于半透明渲染态的玻璃容器（GlassSurface、玻璃 Sheet / Dialog / Menu）内部。
 * 普通实色正文页保持默认 false，避免实色页面上的次级控件误用玻璃半透明样式。
 */
internal val LocalOnGlassSurface = staticCompositionLocalOf { false }

/**
 * 校验当前玻璃容器是否满足：硬件加速已开启、背景源渲染就绪且当前材质不透明度 < 0.95f（非实心）。
 */
@Composable
internal fun rememberIsTranslucentGlassContainer(style: GlassStyle): Boolean {
    if (LocalGlassLegacy.current) return false
    val appearance = LocalGlassAppearance.current
    if (!appearance.enabled || appearance.transparencyPercent <= 0) return false
    val view = LocalView.current
    if (!view.isHardwareAccelerated) return false

    val hardware = LocalHardwareGlassBackdrop.current
    val backdropReady = if (hardware != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hardware.renderingActive && hardware.contentReady
    } else {
        val source = LocalGlassBackdrop.current
        source?.enabled == true && (
            source.recorded ||
                source.image != null ||
                source.sharedSnapshot != null
        )
    }
    if (!backdropReady) return false

    val isDark = LocalAppDarkTheme.current ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    val opacity = uniformGlassOpacity(appearance, isDark, available = true, style = style)
    return opacity < 0.95f
}

private class ComposeRenderEffectCache {
    var lastPlatformEffect: android.graphics.RenderEffect? = null
    var lastBlurPx: Float = -1f
    var cachedComposeEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun resolve(
        platformEffect: android.graphics.RenderEffect?,
        blurPx: Float
    ): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && platformEffect != null) {
            if (platformEffect !== lastPlatformEffect || cachedComposeEffect == null) {
                lastPlatformEffect = platformEffect
                lastBlurPx = -1f
                cachedComposeEffect = wrapPlatformRenderEffect(platformEffect)
            }
            return cachedComposeEffect
        }
        if (blurPx > 0f) {
            if (lastPlatformEffect != null || blurPx != lastBlurPx || cachedComposeEffect == null) {
                lastPlatformEffect = null
                lastBlurPx = blurPx
                cachedComposeEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
            }
            return cachedComposeEffect
        }
        lastPlatformEffect = null
        lastBlurPx = -1f
        cachedComposeEffect = null
        return null
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun wrapPlatformRenderEffect(
    effect: android.graphics.RenderEffect
): androidx.compose.ui.graphics.RenderEffect = effect.asComposeRenderEffect()

enum class GlassStyle { Toolbar, Panel }

@Composable
fun GlassBackdropHost(content: @Composable () -> Unit) {
    val source = remember { GlassBackdropState() }
    val hostScope = rememberCoroutineScope()
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appearance = LocalGlassAppearance.current
    val legacy = LocalGlassLegacy.current
    val capabilities = rememberGlassSystemCapabilities()
    val forceFallback = LocalGlassForceFallback.current

    val blurAllowed = glassBlurAllowed(
        apiLevel = Build.VERSION.SDK_INT,
        powerSave = capabilities.powerSave,
        forceFallback = forceFallback,
        reducedTransparency = capabilities.reducedTransparency
    ) && !legacy
    val activePercent = appearance.transparencyPercent

    SideEffect {
        source.owner = view
        source.hostScope = hostScope
        source.enabled = blurAllowed
        source.activeTransparencyPercent = activePercent
    }

    // R4：当透明度切为 0% 或能力降级时立即停止并清空；当从 0% 恢复为非零时重新捕获最新背景
    LaunchedEffect(blurAllowed, activePercent) {
        if (!blurAllowed || activePercent <= 0) {
            source.clearAndStop()
        } else {
            view.invalidate()
            source.notifySourceChanged(activePercent, force = true)
        }
    }

    // R4：生命周期进入后台 (ON_STOP) 时主动停止并释放快照，回到前台 (ON_START) 时按需恢复
    DisposableEffect(lifecycle, source) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> source.clearAndStop()
                Lifecycle.Event.ON_START -> {
                    if (source.enabled && source.activeTransparencyPercent > 0) {
                        view.invalidate()
                        source.notifySourceChanged(source.activeTransparencyPercent, force = true)
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            source.clearAndStop()
        }
    }

    CompositionLocalProvider(
        LocalGlassBackdrop provides source,
        LocalGlassReducedMotion provides capabilities.reducedMotion,
        content = content
    )
}

/** Apply only to the content plane, never to a parent containing glass controls. */
fun Modifier.glassBackdropSource(): Modifier = composed {
    val source = LocalGlassBackdrop.current ?: return@composed this
    val appearance = LocalGlassAppearance.current
    val legacy = LocalGlassLegacy.current
    val contentLayer = rememberGraphicsLayer()
    val boundedSampleLayer = rememberGraphicsLayer()
    val baseColor = MaterialTheme.colorScheme.background

    DisposableEffect(source, contentLayer, boundedSampleLayer) {
        source.layer = contentLayer
        source.sampleLayer = boundedSampleLayer
        onDispose {
            if (source.layer === contentLayer) {
                source.layer = null
                source.sampleLayer = null
                source.sampleLayerReady = false
                source.recorded = false
                source.clearAndStop()
            }
        }
    }

    this.onGloballyPositioned { coordinates ->
        val newOrigin = coordinates.positionOnScreen()
        val newSize = coordinates.size
        if (source.origin != newOrigin) source.origin = newOrigin
        if (source.size != newSize) source.size = newSize
    }.drawWithContent {
        val percent = appearance.transparencyPercent
        val active = source.enabled && !legacy && percent > 0
        if (!active) {
            drawContent()
            return@drawWithContent
        }

        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) {
            drawContent()
            return@drawWithContent
        }

        contentLayer.record {
            drawRect(baseColor)
            this@drawWithContent.drawContent()
        }

        // R3：在读回前直接录制到已限制尺寸（<=25% 且最长边 <=640px）的离屏 GraphicsLayer
        val dims = glassSnapshotDimensions(w, h)
        if (dims != null) {
            val (dw, dh) = dims
            boundedSampleLayer.record(size = IntSize(dw, dh)) {
                scale(
                    scaleX = dw.toFloat() / w.toFloat(),
                    scaleY = dh.toFloat() / h.toFloat(),
                    pivot = Offset.Zero
                ) {
                    drawLayer(contentLayer)
                }
            }
            source.onSourceLayerRecorded(w, h, percent)
        }

        drawLayer(contentLayer)
    }
}

/** The effect belongs exclusively to a background layer; children are drawn afterwards. */
fun Modifier.glassBackground(
    style: GlassStyle = GlassStyle.Toolbar,
    shape: Shape = RoundedCornerShape(22.dp),
    role: GlassLensRole = style.defaultLensRole(),
    flatEdges: GlassFlatEdges = GlassFlatEdges.None,
    press: Float = 0f
): Modifier = composed {
    val hardware = LocalHardwareGlassBackdrop.current
    if (hardware != null && Build.VERSION.SDK_INT >= 31) {
        return@composed this.hardwareGlassBackground(hardware, style, shape, role, flatEdges, press)
    }
    val source = LocalGlassBackdrop.current
    val view = LocalView.current
    val legacy = LocalGlassLegacy.current
    val lensEnabled = LocalGlassLensEnabled.current
    val appearance = LocalGlassAppearance.current
    val color = MaterialTheme.colorScheme.surface
    val dark = color.luminance() < .5f
    val background = rememberGraphicsLayer()
    val effectCache = remember { ComposeRenderEffectCache() }
    val lensEngine = remember { if (Build.VERSION.SDK_INT >= 33) LiquidGlassLensEngine() else null }
    DisposableEffect(lensEngine) {
        onDispose {
            if (Build.VERSION.SDK_INT >= 33) {
                lensEngine?.clear()
            }
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var glassSize by remember { mutableStateOf(IntSize.Zero) }
    var separateWindow by remember { mutableStateOf(false) }
    val enabled = source?.enabled == true && !legacy
    val percent = appearance.transparencyPercent
    val reducedMotion = LocalGlassReducedMotion.current
    val lightDirState = LocalGlassLightDirection.current

    val canBlur = enabled && view.isHardwareAccelerated && percent > 0
    val clarity = appearance.clarityPercent
    val targetBlurValue = remember(style, clarity, canBlur) {
        if (!canBlur) 0f else liquidBlurDp(style, clarity)
    }

    // 背景内容与是否模糊独立；同一容器始终只使用一层统一底色，由公共角色策略决定浓度。
    val targetOpacity = if (legacy) 1f else uniformGlassOpacity(appearance, dark, canBlur, style)
    val animatedOpacity by animateFloatAsState(
        targetValue = targetOpacity,
        animationSpec = if (!appearance.enabled || !canBlur || reducedMotion) snap() else tween(180),
        label = "glassUniformTint"
    )
    val animatedBlur by animateDpAsState(
        targetValue = if (legacy || !appearance.enabled || !canBlur) 0.dp else targetBlurValue.dp,
        animationSpec = if (!appearance.enabled || !canBlur || reducedMotion) snap() else tween(180),
        label = "glassBlur"
    )
    SideEffect {
        source?.apply {
            lastComputedAmbientOpacity = targetOpacity
            lastComputedPeakOpacity = targetOpacity
            lastComputedBlurDp = targetBlurValue
            lastProtectedCellCount = 0
        }
    }
    this
        .onGloballyPositioned { coordinates ->
            val newOrigin = coordinates.positionOnScreen()
            val newSize = coordinates.size
            val isSeparate = source?.owner?.rootView !== view.rootView
            if (origin != newOrigin) origin = newOrigin
            if (glassSize != newSize) glassSize = newSize
            if (separateWindow != isSeparate) separateWindow = isSeparate
        }.clip(shape).drawWithContent {
            val bitmap = if (separateWindow) (source?.image ?: source?.sharedSnapshot) else source?.image
            val live = source?.layer?.takeIf { !separateWindow && source.sampleLayerReady }
            val blurPx = animatedBlur.toPx()
            val hasBackdrop = canBlur && (bitmap != null || live != null)
            val outline = shape.createOutline(size, layoutDirection, this)
            val opacity = if (hasBackdrop) animatedOpacity else if (!appearance.enabled || legacy) 1f else .94f
            var lensApplied = false

            if (hasBackdrop &&
                source.size.width > 0 &&
                source.size.height > 0 &&
                size.width > 0f &&
                size.height > 0f
            ) {
                val padding = liquidMaterialPaddingPx(blurPx)
                val recordW = ceil(size.width).toInt() + padding * 2
                val recordH = ceil(size.height).toInt() + padding * 2
                if (recordW > 0 && recordH > 0) {
                    val delta = origin - source.origin
                    val lightDir = lightDirState.value
                    val lensEffect = if (
                        Build.VERSION.SDK_INT >= 33 &&
                        lensEnabled &&
                        lensEngine != null &&
                        LiquidGlassLensEngine.isAvailable()
                    ) {
                        extractGlassCornerRadii(outline)?.let { corners ->
                            resolveGlassLensUniforms(
                                widthPx = size.width,
                                heightPx = size.height,
                                density = density,
                                paddingPx = padding,
                                blurRadiusPx = blurPx,
                                corners = corners,
                                role = role,
                                flatEdges = flatEdges,
                                clarityPercent = clarity,
                                tintR = color.red,
                                tintG = color.green,
                                tintB = color.blue,
                                tintA = opacity,
                                press = press,
                                lightX = lightDir.x,
                                lightY = lightDir.y
                            )?.let { lensEngine.effect(it) }
                        }
                    } else {
                        null
                    }
                    lensApplied = lensEffect != null
                    val targetEffect = effectCache.resolve(lensEffect, blurPx)
                    if (background.renderEffect !== targetEffect) {
                        background.renderEffect = targetEffect
                    }
                    background.record(size = IntSize(recordW, recordH)) {
                        translate(padding - delta.x, padding - delta.y) {
                            if (bitmap != null) {
                                drawImage(bitmap, dstOffset = IntOffset.Zero, dstSize = source.size)
                            } else if (live != null) {
                                drawLayer(live)
                            }
                        }
                    }
                    translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(background) }
                }
            }

            if (!lensApplied) {
                drawRect(color.copy(alpha = opacity))
                if (hasBackdrop && appearance.enabled && !legacy) {
                    drawFallbackDualLobeRim(outline, dark, opacity, flatEdges)
                }
            }
            drawContent()
        }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Toolbar,
    shape: Shape = MaterialTheme.shapes.large,
    shadowElevation: Dp = 0.dp,
    role: GlassLensRole = style.defaultLensRole(),
    flatEdges: GlassFlatEdges = GlassFlatEdges.None,
    press: Float = 0f,
    content: @Composable BoxScope.() -> Unit
) {
    val onGlass = rememberIsTranslucentGlassContainer(style)
    Surface(
        modifier = modifier.shadow(shadowElevation, shape, clip = false).glassBackground(style, shape, role, flatEdges, press),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        CompositionLocalProvider(LocalOnGlassSurface provides onGlass) {
            Box(content = content)
        }
    }
}

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ResetEdgeBounceOnOverlay(expanded)
    if (LocalGlassOverlayHost.current != null) {
        GlassPortalMenu(expanded, onDismissRequest, modifier, content)
        return
    }
    val shape = MaterialTheme.shapes.large
    val onGlass = rememberIsTranslucentGlassContainer(GlassStyle.Panel)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.shadow(Elevation.menu, shape, clip = false).glassBackground(GlassStyle.Panel, shape),
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = {
            CompositionLocalProvider(LocalOnGlassSurface provides onGlass) {
                content()
            }
        }
    )
}
