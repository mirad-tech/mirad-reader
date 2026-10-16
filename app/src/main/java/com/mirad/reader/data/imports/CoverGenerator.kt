package com.mirad.reader.data.imports

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** 封面生成：EPUB 内嵌封面转存 / TXT 生成占位封面。 */
object CoverGenerator {

    private const val COVER_WIDTH = 480
    private const val COVER_HEIGHT = 640

    /** TXT 占位封面：按标题取色，绘制标题与作者。 */
    fun generatePlaceholder(title: String, author: String?, out: File): Boolean = runCatching {
        val colorSeed = title.hashCode()
        val hue = ((colorSeed % 360) + 360) % 360
        val top = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.32f, 0.58f))
        val bottom = Color.HSVToColor(floatArrayOf(((hue + 28) % 360).toFloat(), 0.42f, 0.32f))

        val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, COVER_HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, COVER_WIDTH.toFloat(), COVER_HEIGHT.toFloat(), bg)

        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(90, 255, 255, 255)
        }
        canvas.drawRect(18f, 18f, COVER_WIDTH - 18f, COVER_HEIGHT - 18f, border)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 46f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        val lines = wrap(title, titlePaint, (COVER_WIDTH - 96).toFloat(), maxLines = 6)
        var y = 150f
        for (line in lines) {
            canvas.drawText(line, 48f, y, titlePaint)
            y += titlePaint.textSize * 1.35f
        }

        if (!author.isNullOrBlank()) {
            val authorPaint = Paint().apply {
                color = Color.argb(200, 255, 255, 255)
                textSize = 30f
                isAntiAlias = true
            }
            canvas.drawText(author, 48f, COVER_HEIGHT - 64f, authorPaint)
        }

        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    /** 从输入流转存封面（自动降采样，避免大图 OOM）。 */
    fun saveCover(input: InputStream, out: File, maxSize: Int = 900): Boolean = runCatching {
        val bytes = input.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    /** 简单按像素宽度折行（中文按字符宽度估算）。 */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val result = ArrayList<String>()
        val builder = StringBuilder()
        for (c in text) {
            builder.append(c)
            if (paint.measureText(builder.toString()) > maxWidth) {
                builder.deleteCharAt(builder.length - 1)
                result.add(builder.toString())
                builder.setLength(0)
                builder.append(c)
                if (result.size == maxLines) break
            }
        }
        if (result.size < maxLines && builder.isNotEmpty()) result.add(builder.toString())
        if (result.size == maxLines && text.length > result.joinToString("").length) {
            result[maxLines - 1] = result[maxLines - 1].dropLast(1) + "…"
        }
        return result.ifEmpty { listOf(text.take(20)) }
    }

    /** 生成"标题 + 序号"的图片占位（调试或封面缺失时使用）。 */
    fun measureArea(width: Int, height: Int): Rect = Rect(0, 0, width, height)
}
