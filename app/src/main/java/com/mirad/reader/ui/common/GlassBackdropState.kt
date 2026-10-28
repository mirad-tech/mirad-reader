package com.mirad.reader.ui.common

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

internal data class SyntheticSample(
    val width: Int,
    val height: Int,
    val fingerprint: Long
)

@Stable
class GlassBackdropState @JvmOverloads internal constructor(
    private val nowNanos: () -> Long = System::nanoTime,
    private val minIntervalNanos: Long = MIN_SNAPSHOT_INTERVAL_NANOS,
    internal var sampleProvider: (() -> Bitmap?)? = null,
    internal var syntheticSampleProvider: (suspend () -> SyntheticSample?)? = null
) {
    internal var layer: GraphicsLayer? = null
    internal var sampleLayer: GraphicsLayer? = null
    internal var sampleLayerReady: Boolean = false
    internal var owner: android.view.View? = null
    internal var hostScope: CoroutineScope? = null

    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(IntSize.Zero)
    internal var recorded by mutableStateOf(false)
    internal var image by mutableStateOf<ImageBitmap?>(null)
    // 初始保守为 false：能力判定（API/省电/降级）由宿主 SideEffect 在首帧后写入，
    // 避免首帧就绕过闸门录制采样层并发起一次无谓采样。
    internal var enabled by mutableStateOf(false)
    internal var activeTransparencyPercent: Int = GlassAppearance.DEFAULT_PERCENT

    // 事件版本驱动：仅在实际采样或发布新内容时递增，不在 drawWithContent 阶段自增（R1、F2）
    internal var drawVersion by mutableLongStateOf(0L)

    // WebView 采集统计
    internal var captureAttempts = 0L
    internal var publishedCaptures = 0L
    internal var captureFailures = 0L
    internal var captureTimeNanos = 0L

    // Compose 离屏采样与分析统计（R1、R3、R4、S1、T1）
    internal var sourceRecordCount = 0L
    internal var snapshotCount = 0L
    internal var analysisCount = 0L
    internal var lastSourceWidth = 0
    internal var lastSourceHeight = 0
    internal var lastReadbackWidth = 0
    internal var lastReadbackHeight = 0
    internal var lastSampleThreadName = "none"
    internal var lastSampleDurationNanos = 0L
    internal var inFlightSnapshotCount = 0
    internal var maxObservedInFlightCount = 0
    internal var lastComputedAmbientOpacity = 1f
    internal var lastComputedPeakOpacity = 1f
    internal var lastComputedBlurDp = 0f
    internal var lastComputedHighlightAlpha = 0f
    internal var lastProtectedCellCount = 0

    // 跨窗口共享快照与 CPU 安全局部采样分析副本（均为有界 <=25% 且 <=640px 的 ARGB_8888 位图，R3）
    internal var sharedSnapshot by mutableStateOf<ImageBitmap?>(null)
    internal var analysisBitmap by mutableStateOf<Bitmap?>(null)

    private var generation = 0L
    private var snapshotInFlight = false
    private var lastSnapshotNanos = 0L
    private var pendingSnapshot = false
    private var activeCaptureJob: Job? = null
    private var trailingJob: Job? = null
    internal var lastSampleFingerprint = 0L
        private set

    internal val activeSnapshotCount: Int
        get() = if (sharedSnapshot != null || analysisBitmap != null || image != null || lastSampleFingerprint != 0L) 1 else 0

    internal fun statsSummary(): String =
        "sourceRecords=$sourceRecordCount snapshots=$snapshotCount analyses=$analysisCount " +
            "webAttempts=$captureAttempts webPublished=$publishedCaptures " +
            "sourceSize=${lastSourceWidth}x${lastSourceHeight} readbackSize=${lastReadbackWidth}x${lastReadbackHeight} " +
            "inFlight=$inFlightSnapshotCount maxInFlight=$maxObservedInFlightCount activeSnapshots=$activeSnapshotCount " +
            "ambientOpacity=${"%.3f".format(java.util.Locale.US, lastComputedAmbientOpacity)} " +
            "peakOpacity=${"%.3f".format(java.util.Locale.US, lastComputedPeakOpacity)} " +
            "protectedCells=$lastProtectedCellCount blurDp=${"%.1f".format(java.util.Locale.US, lastComputedBlurDp)} " +
            "highlightAlpha=${"%.3f".format(java.util.Locale.US, lastComputedHighlightAlpha)} " +
            "thread=$lastSampleThreadName durationMs=${lastSampleDurationNanos / 1_000_000.0}"

    fun setImage(image: ImageBitmap?, size: IntSize = this.size, origin: Offset = this.origin) {
        if (image != null && (!enabled || activeTransparencyPercent <= 0)) {
            clearAndStop("setImage_while_disabled_or_zero")
            return
        }
        if (image == null) {
            this.image = null
            this.sharedSnapshot = null
            this.analysisBitmap = null
            this.lastSampleFingerprint = 0L
            drawVersion++
            return
        }
        val bmp = image.asAndroidBitmap()
        this.image = image
        this.sharedSnapshot = image
        this.analysisBitmap = bmp
        this.size = size
        this.origin = origin
        this.lastSourceWidth = size.width
        this.lastSourceHeight = size.height
        this.lastReadbackWidth = bmp.width
        this.lastReadbackHeight = bmp.height
        this.lastSampleThreadName = Thread.currentThread().name
        drawVersion++
    }

    /**
     * R4：当透明度切到 0%、组件失活、进入后台或能力降级时，
     * 取消所有排队采样、使在途代次失效，并立即清空缓存位图与状态。
     */
    fun clearAndStop(reason: String = "stopped") {
        generation++
        pendingSnapshot = false
        snapshotInFlight = false
        inFlightSnapshotCount = 0
        activeCaptureJob?.cancel()
        activeCaptureJob = null
        trailingJob?.cancel()
        trailingJob = null
        val hadData = image != null || sharedSnapshot != null || analysisBitmap != null
        image = null
        sharedSnapshot = null
        analysisBitmap = null
        lastSampleFingerprint = 0L
        if (hadData) {
            runCatching {
                android.util.Log.i("GlassPreview", "glassCleared reason=$reason ${statsSummary()}")
            }
        }
    }

    /**
     * R1：由 glassBackdropSource 在录制完有界 sampleLayer 后调用。
     * 严禁在此方法内直接修改任何 Compose MutableState，避免 draw -> state -> draw 死循环。
     */
    internal fun onSourceLayerRecorded(width: Int, height: Int, transparencyPercent: Int) {
        if (!enabled || transparencyPercent <= 0) return
        sourceRecordCount++
        lastSourceWidth = width
        lastSourceHeight = height
        sampleLayerReady = true
        notifySourceChanged(transparencyPercent)
    }

    internal fun notifySourceChanged(
        transparencyPercent: Int = activeTransparencyPercent,
        force: Boolean = false
    ) {
        activeTransparencyPercent = transparencyPercent
        if (!enabled || transparencyPercent <= 0) {
            clearAndStop("notify_zero_or_disabled")
            return
        }
        val scope = hostScope ?: return
        if (snapshotInFlight) {
            pendingSnapshot = true
            return
        }
        val now = nowNanos()
        val elapsed = now - lastSnapshotNanos
        if (!force && lastSnapshotNanos != 0L && elapsed < minIntervalNanos) {
            pendingSnapshot = true
            scheduleTrailingOnScope(scope, minIntervalNanos - elapsed, transparencyPercent)
            return
        }
        trailingJob?.cancel()
        trailingJob = null
        activeCaptureJob = scope.launch {
            requestSnapshot(transparencyPercent, force = force)
        }
    }

    private fun scheduleTrailingOnScope(
        scope: CoroutineScope,
        remainingNanos: Long,
        transparencyPercent: Int
    ) {
        if (trailingJob?.isActive == true) return
        val token = generation
        val delayMs = max(1L, (remainingNanos + 999_999L) / 1_000_000L)
        trailingJob = scope.launch {
            delay(delayMs)
            trailingJob = null
            if (generation == token && pendingSnapshot && enabled && activeTransparencyPercent > 0) {
                requestSnapshot(activeTransparencyPercent, fromTrailingTimer = true)
            }
        }
    }

    @JvmOverloads
    suspend fun requestSnapshot(
        transparencyPercent: Int = activeTransparencyPercent,
        force: Boolean = false,
        fromTrailingTimer: Boolean = false
    ) {
        activeTransparencyPercent = transparencyPercent
        if (transparencyPercent <= 0 || !enabled) {
            clearAndStop("request_zero_or_disabled")
            return
        }
        if (image != null) {
            sharedSnapshot = image
            analysisBitmap = image?.asAndroidBitmap()
            return
        }
        val synthProvider = syntheticSampleProvider
        val provider = sampleProvider
        val currentSampleLayer = sampleLayer
        if (synthProvider == null && provider == null && (currentSampleLayer == null || !sampleLayerReady)) return

        if (snapshotInFlight) {
            pendingSnapshot = true
            return
        }

        val now = nowNanos()
        val elapsed = now - lastSnapshotNanos
        if (!force && !fromTrailingTimer && lastSnapshotNanos != 0L && elapsed < minIntervalNanos) {
            pendingSnapshot = true
            val scope = hostScope
            if (scope != null) {
                scheduleTrailingOnScope(scope, minIntervalNanos - elapsed, transparencyPercent)
                return
            } else {
                val delayMs = max(1L, (minIntervalNanos - elapsed + 999_999L) / 1_000_000L)
                val waitToken = generation
                delay(delayMs)
                if (generation != waitToken || !pendingSnapshot || !enabled || activeTransparencyPercent <= 0) {
                    return
                }
            }
        }

        val token = generation
        snapshotInFlight = true
        inFlightSnapshotCount = 1
        maxObservedInFlightCount = maxOf(maxObservedInFlightCount, inFlightSnapshotCount)
        pendingSnapshot = false
        val startNanos = nowNanos()
        try {
            if (synthProvider != null) {
                val sample = runCatching { synthProvider.invoke() }.getOrNull() ?: return
                if (generation != token || !enabled || activeTransparencyPercent <= 0) return
                val (dw, dh) = glassSnapshotDimensions(sample.width, sample.height)?.takeIf {
                    sample.width > 640 || sample.height > 640
                } ?: (sample.width to sample.height)
                lastReadbackWidth = dw
                lastReadbackHeight = dh
                lastSampleThreadName = Thread.currentThread().name
                val finishNanos = nowNanos()
                lastSnapshotNanos = finishNanos
                lastSampleDurationNanos = finishNanos - startNanos
                snapshotCount++
                if (!force && sample.fingerprint == lastSampleFingerprint && lastSampleFingerprint != 0L) {
                    return
                }
                lastSampleFingerprint = sample.fingerprint
                recorded = true
                drawVersion++
                return
            }

            val rawBmp: Bitmap? = if (provider != null) {
                runCatching { provider.invoke() }.getOrNull()
            } else {
                runCatching { currentSampleLayer?.toImageBitmap()?.asAndroidBitmap() }.getOrNull()
            }

            if (rawBmp == null) return
            if (generation != token || !enabled || activeTransparencyPercent <= 0) {
                if (provider == null && !rawBmp.isRecycled) {
                    runCatching { rawBmp.recycle() }
                }
                return
            }

            // R3：记录实际读回尺寸（由于 sampleLayer 录制前已按 glassSnapshotDimensions 缩放，此处读回即有界小图）
            lastReadbackWidth = rawBmp.width
            lastReadbackHeight = rawBmp.height
            lastSampleThreadName = Thread.currentThread().name

            val isHw = try { rawBmp.config?.name == "HARDWARE" } catch (_: Throwable) { false }
            val swBmp = if (isHw) {
                val copied = runCatching { rawBmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                runCatching { rawBmp.recycle() }
                copied
            } else {
                rawBmp
            } ?: return

            val (dw, dh) = glassSnapshotDimensions(swBmp.width, swBmp.height)?.takeIf {
                swBmp.width > 640 || swBmp.height > 640
            } ?: (swBmp.width to swBmp.height)

            val cpuSafe = if (dw == swBmp.width && dh == swBmp.height && swBmp.config == Bitmap.Config.ARGB_8888) {
                swBmp
            } else {
                val scaled = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(scaled)
                val src = android.graphics.Rect(0, 0, swBmp.width, swBmp.height)
                val dst = android.graphics.Rect(0, 0, dw, dh)
                val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(swBmp, src, dst, paint)
                if (swBmp !== rawBmp && !swBmp.isRecycled) {
                    runCatching { swBmp.recycle() }
                }
                scaled
            }

            // GraphicsLayer 读回路径（provider == null）的位图归本函数所有：
            // 回收判定按所有权而非对象恒等，cpuSafe === rawBmp 时同样需要回收，
            // 否则静态内容反复重绘会按节流间隔持续泄漏采样位图。
            val ownsReadback = provider == null

            if (generation != token || !enabled || activeTransparencyPercent <= 0) {
                if ((ownsReadback || cpuSafe !== rawBmp) && cpuSafe !== analysisBitmap && !cpuSafe.isRecycled) {
                    runCatching { cpuSafe.recycle() }
                }
                return
            }

            val finishNanos = nowNanos()
            lastSnapshotNanos = finishNanos
            lastSampleDurationNanos = finishNanos - startNanos
            snapshotCount++

            // 指纹与全像素比较是纯 CPU 计算（cpuSafe 已保证为软件 ARGB_8888 位图，
            // 可安全跨线程 getPixel），放到 Default 线程避免拖慢主线程帧。
            val fingerprint = withContext(Dispatchers.Default) { computeBitmapFingerprint(cpuSafe) }
            if (generation != token || !enabled || activeTransparencyPercent <= 0) {
                if ((ownsReadback || cpuSafe !== rawBmp) && cpuSafe !== analysisBitmap && !cpuSafe.isRecycled) {
                    runCatching { cpuSafe.recycle() }
                }
                return
            }
            if (
                !force &&
                fingerprint == lastSampleFingerprint &&
                analysisBitmap != null &&
                sharedSnapshot != null &&
                withContext(Dispatchers.Default) { areBoundedBitmapsIdentical(cpuSafe, analysisBitmap) }
            ) {
                // R1 & S2：全量像素完全一致时跳过状态更新，阻断静态重绘环且绝不漏检局部变化
                if ((ownsReadback || cpuSafe !== rawBmp) && cpuSafe !== analysisBitmap && !cpuSafe.isRecycled) {
                    runCatching { cpuSafe.recycle() }
                }
                return
            }

            lastSampleFingerprint = fingerprint
            val boundedImage = cpuSafe.asImageBitmap()
            sharedSnapshot = boundedImage
            analysisBitmap = cpuSafe
            if (!recorded) {
                recorded = true
            }
            drawVersion++
            runCatching {
                android.util.Log.i("GlassPreview", "glassSampleReadback ${statsSummary()}")
            }
        } finally {
            if (generation == token) {
                snapshotInFlight = false
                inFlightSnapshotCount = 0
                if (pendingSnapshot && enabled && activeTransparencyPercent > 0) {
                    val scope = hostScope
                    if (scope != null) {
                        val elapsedAfter = max(0L, nowNanos() - lastSnapshotNanos)
                        val remaining = max(1_000_000L, minIntervalNanos - elapsedAfter)
                        scheduleTrailingOnScope(scope, remaining, activeTransparencyPercent)
                    } else {
                        val delayMs = max(1L, (minIntervalNanos + 999_999L) / 1_000_000L)
                        delay(delayMs)
                        if (generation == token && pendingSnapshot && enabled && activeTransparencyPercent > 0) {
                            requestSnapshot(activeTransparencyPercent, fromTrailingTimer = true)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MIN_SNAPSHOT_INTERVAL_NANOS = 100_000_000L
    }
}

/**
 * S2：对有界采样位图（<=25% 尺寸且 <=640px）进行全像素遍历指纹计算，
 * 确保任意横带、竖带、角落小块或单字符像素变化都能被检测到。
 */
internal fun computeBitmapFingerprint(bitmap: Bitmap): Long {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0L
    return computeBitmapFingerprint(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
}

internal fun computeBitmapFingerprint(width: Int, height: Int, getPixel: (Int, Int) -> Int): Long {
    if (width <= 0 || height <= 0) return 0L
    var hash = -0x340d631b7bdddcdbL xor (width.toLong() shl 32) xor height.toLong()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = getPixel(x, y).toLong() and 0xFFFFFFFFL
            val coordMix = (y * width + x + 1).toLong() * -0x61c8864680b583ebL
            hash = (hash xor (pixel + coordMix)) * 0x100000001b3L
        }
    }
    return if (hash == 0L) 1L else hash
}

internal fun areBoundedBitmapsIdentical(a: Bitmap?, b: Bitmap?): Boolean {
    if (a === b) return a != null
    if (a == null || b == null || a.isRecycled || b.isRecycled) return false
    return areBoundedPixelsIdentical(
        widthA = a.width,
        heightA = a.height,
        getPixelA = { x, y -> a.getPixel(x, y) },
        widthB = b.width,
        heightB = b.height,
        getPixelB = { x, y -> b.getPixel(x, y) }
    )
}

internal fun areBoundedPixelsIdentical(
    widthA: Int,
    heightA: Int,
    getPixelA: (Int, Int) -> Int,
    widthB: Int,
    heightB: Int,
    getPixelB: (Int, Int) -> Int
): Boolean {
    if (widthA <= 0 || heightA <= 0 || widthA != widthB || heightA != heightB) return false
    for (y in 0 until heightA) {
        for (x in 0 until widthA) {
            if (getPixelA(x, y) != getPixelB(x, y)) return false
        }
    }
    return true
}
