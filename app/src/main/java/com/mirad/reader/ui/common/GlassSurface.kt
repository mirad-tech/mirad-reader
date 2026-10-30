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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

/** Preview overrides only: neither value is persisted or exposed in the product. */
val LocalGlassLegacy = staticCompositionLocalOf { false }
val LocalGlassForceFallback = staticCompositionLocalOf { false }

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

enum class GlassStyle { Toolbar, Panel }

@Composable
fun GlassBackdropHost(content: @Composable () -> Unit) {
    val source = remember { GlassBackdropState() }
    val hostScope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appearance = LocalGlassAppearance.current
    val legacy = LocalGlassLegacy.current
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var powerSave by remember { mutableStateOf(power.isPowerSaveMode) }
    val forceFallback = LocalGlassForceFallback.current

    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                powerSave = power.isPowerSaveMode
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val blurAllowed = glassBlurAllowed(Build.VERSION.SDK_INT, powerSave, forceFallback) && !legacy
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
            source.clearAndStop(if (activePercent <= 0) "transparency_zero" else "blur_disabled")
        } else {
            view.invalidate()
            source.notifySourceChanged(activePercent, force = true)
        }
    }

    // R4：生命周期进入后台 (ON_STOP) 时主动停止并释放快照，回到前台 (ON_START) 时按需恢复
    DisposableEffect(lifecycle, source) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> source.clearAndStop("lifecycle_on_stop")
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
            source.clearAndStop("host_disposed")
        }
    }

    CompositionLocalProvider(LocalGlassBackdrop provides source, content = content)
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
                source.clearAndStop("source_disposed")
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
    minimumTintAlpha: Float = 0f,
    shadowElevation: Dp? = null
): Modifier = composed {
    val hardware = LocalHardwareGlassBackdrop.current
    if (hardware != null && Build.VERSION.SDK_INT >= 31) {
        return@composed this.hardwareGlassBackground(hardware, style, shape, minimumTintAlpha, shadowElevation)
    }
    val source = LocalGlassBackdrop.current
    val view = LocalView.current
    val legacy = LocalGlassLegacy.current
    val appearance = LocalGlassAppearance.current
    val color = MaterialTheme.colorScheme.surface
    val dark = color.luminance() < .5f
    val background = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var glassSize by remember { mutableStateOf(IntSize.Zero) }
    var separateWindow by remember { mutableStateOf(false) }
    val enabled = source?.enabled == true && !legacy
    val edgeTexture = LocalGlassEdgeTexture.current
    val percent = appearance.transparencyPercent

    // 局部背景特征分析：最多 5 次/秒（200ms 节流），仅在采样位图或组件几何实际变化后执行并缓存
    var cachedMetrics by remember { mutableStateOf<BackdropMetrics?>(null) }
    var lastAnalysisNanos by remember { mutableLongStateOf(0L) }
    var lastAnalyzedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastAnalyzedOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var lastAnalyzedSize by remember { mutableStateOf(IntSize.Zero) }

    val currentAnalysisBitmap = if (enabled && percent > 0) source?.analysisBitmap else null

    LaunchedEffect(currentAnalysisBitmap, origin, glassSize, percent, enabled) {
        if (!enabled || percent <= 0) {
            cachedMetrics = BackdropMetrics.NEUTRAL
            lastAnalyzedBitmap = null
            return@LaunchedEffect
        }
        val bmp = currentAnalysisBitmap
        if (bmp != null && !bmp.isRecycled && source.size.width > 0 && source.size.height > 0 && glassSize.width > 0 && glassSize.height > 0) {
            if (bmp === lastAnalyzedBitmap && origin == lastAnalyzedOrigin && glassSize == lastAnalyzedSize && cachedMetrics != null) {
                return@LaunchedEffect
            }
            val now = System.nanoTime()
            val elapsed = now - lastAnalysisNanos
            if (lastAnalysisNanos != 0L && elapsed < 200_000_000L) {
                delay(max(1L, (200_000_000L - elapsed + 999_999L) / 1_000_000L))
            }
            if (bmp.isRecycled || source.size.width <= 0 || source.size.height <= 0) return@LaunchedEffect
            val delta = origin - source.origin
            val scaleX = bmp.width.toFloat() / source.size.width
            val scaleY = bmp.height.toFloat() / source.size.height
            val rect = Rect(
                delta.x * scaleX,
                delta.y * scaleY,
                (delta.x + glassSize.width) * scaleX,
                (delta.y + glassSize.height) * scaleY
            )
            cachedMetrics = analyzeLocalBackdrop(bmp, rect)
            source.analysisCount++
            lastAnalysisNanos = System.nanoTime()
            lastAnalyzedBitmap = bmp
            lastAnalyzedOrigin = origin
            lastAnalyzedSize = glassSize
        }
    }

    val canBlur = enabled && view.isHardwareAccelerated && percent > 0
    val clarity = appearance.clarityPercent
    val rawParams = remember(percent, clarity, style, dark, canBlur, cachedMetrics) {
        computeGlassParams(
            transparencyPercent = percent,
            style = style,
            isDark = dark,
            canBlur = canBlur,
            metrics = cachedMetrics,
            clarityPercent = clarity
        )
    }

    // 背景内容与是否模糊独立；同一容器始终只使用一层统一底色。
    val targetOpacity = max(
        if (legacy) 1f else uniformGlassOpacity(appearance, dark, canBlur),
        minimumTintAlpha.coerceIn(0f, 1f)
    )
    val animatedOpacity by animateFloatAsState(
        targetValue = targetOpacity,
        animationSpec = if (!appearance.enabled || !canBlur) snap() else tween(180),
        label = "glassUniformTint"
    )
    val animatedBlur by animateDpAsState(
        targetValue = if (legacy || !appearance.enabled || !canBlur) 0.dp else rawParams.blurRadius,
        animationSpec = if (!appearance.enabled || !canBlur) snap() else tween(180),
        label = "glassBlur"
    )
    SideEffect {
        source?.apply {
            lastComputedAmbientOpacity = targetOpacity
            lastComputedPeakOpacity = targetOpacity
            lastComputedBlurDp = rawParams.blurRadius.value
            lastComputedHighlightAlpha = 0f
            lastProtectedCellCount = 0
        }
    }
    this.shadow(shadowElevation ?: if (legacy) 0.dp else if (style == GlassStyle.Panel) 4.dp else 2.dp, shape, clip = false)
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

            if (hasBackdrop && source.size.width > 0 && source.size.height > 0) {
                val padding = ceil(blurPx * 2).toInt()
                val delta = origin - source.origin
                background.renderEffect = if (blurPx > 0f) BlurEffect(blurPx, blurPx, TileMode.Clamp) else null
                background.record(size = IntSize(size.width.toInt() + padding * 2, size.height.toInt() + padding * 2)) {
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

            val opacity = if (hasBackdrop) animatedOpacity else if (!appearance.enabled || legacy) 1f else .94f
            drawRect(color.copy(alpha = opacity))
            if (!legacy && edgeTexture && appearance.enabled) {
                drawGlassEdgeTexture(
                    outline = shape.createOutline(size, layoutDirection, this),
                    surface = color,
                    clarity = clarity,
                    tintAlpha = opacity,
                    topSheen = true
                )
            }
            // 所有材质、边缘均在前景之前完成，文字和图标不被染色。
            drawContent()
        }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Toolbar,
    shape: Shape = MaterialTheme.shapes.large,
    minimumTintAlpha: Float = 0f,
    shadowElevation: Dp? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier.glassBackground(style, shape, minimumTintAlpha, shadowElevation),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(content = content)
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.glassBackground(GlassStyle.Panel, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}
