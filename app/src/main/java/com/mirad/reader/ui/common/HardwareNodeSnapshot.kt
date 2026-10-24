package com.mirad.reader.ui.common

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.annotation.RequiresApi

/**
 * Renders an already-recorded native node into an independent hardware buffer. No visible Compose
 * GraphicsContext or window participates, so a readback cannot invalidate a visible layer owner.
 * Run on a worker thread. The caller owns the returned HARDWARE bitmap and must recycle it.
 */
@RequiresApi(29)
internal fun captureHardwareNode(node: RenderNode): Bitmap? {
    if (node.width <= 0 || node.height <= 0 || !node.hasDisplayList()) return null
    var reader: ImageReader? = null
    var renderer: HardwareRenderer? = null
    return try {
        reader = ImageReader.newInstance(node.width, node.height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
        }
        val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        if (status and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND != 0) return null
        reader.acquireLatestImage()?.use { image ->
            image.hardwareBuffer?.use { buffer ->
                // wrapHardwareBuffer retains its own reference; closing Image/HardwareBuffer here
                // does not invalidate the returned Bitmap, which is released by its owner.
                Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
            }
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    } finally {
        renderer?.destroy()
        reader?.close()
    }
}
