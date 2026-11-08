package com.mirad.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 导航、阅读控制和设置入口共用的细线图标，保留原书架与笔记轮廓。 */
object MiradIcons {
    val Shelf = lineIcon("Shelf") {
        moveTo(12f, 6f); curveTo(9f, 4f, 5f, 4f, 2.5f, 5.5f)
        lineTo(2.5f, 19f); curveTo(5f, 17.5f, 9f, 17.5f, 12f, 19.5f)
        curveTo(15f, 17.5f, 19f, 17.5f, 21.5f, 19f)
        lineTo(21.5f, 5.5f); curveTo(19f, 4f, 15f, 4f, 12f, 6f)
        lineTo(12f, 19.5f)
    }
    val Notes = lineIcon("Notes") {
        moveTo(6f, 3f); lineTo(18f, 3f); quadTo(20f, 3f, 20f, 5f)
        lineTo(20f, 19f); quadTo(20f, 21f, 18f, 21f)
        lineTo(6f, 21f); quadTo(4f, 21f, 4f, 19f)
        lineTo(4f, 5f); quadTo(4f, 3f, 6f, 3f)
        moveTo(8f, 8f); lineTo(16f, 8f)
        moveTo(8f, 12f); lineTo(16f, 12f)
        moveTo(8f, 16f); lineTo(13f, 16f)
    }
    val Search = lineIcon("Search") {
        moveTo(17f, 10f); curveTo(17f, 14f, 14f, 17f, 10f, 17f)
        curveTo(6f, 17f, 3f, 14f, 3f, 10f); curveTo(3f, 6f, 6f, 3f, 10f, 3f)
        curveTo(14f, 3f, 17f, 6f, 17f, 10f); close()
        moveTo(15f, 15f); lineTo(21f, 21f)
    }
    val Settings = lineIcon("Settings") {
        moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(20f, 18f)
        moveTo(8f, 3f); lineTo(8f, 9f); moveTo(16f, 9f); lineTo(16f, 15f)
        moveTo(10f, 15f); lineTo(10f, 21f)
    }
    val Contents = lineIcon("Contents") {
        for (y in listOf(6f, 12f, 18f)) {
            moveTo(4f, y); lineTo(4.1f, y); moveTo(8f, y); lineTo(20f, y)
        }
    }
    val Edit = lineIcon("Edit") {
        moveTo(4f, 20f); lineTo(5f, 15f); lineTo(16f, 4f)
        quadTo(17.5f, 2.5f, 19f, 4f); lineTo(20f, 5f)
        quadTo(21.5f, 6.5f, 20f, 8f); lineTo(9f, 19f); close()
        moveTo(14f, 6f); lineTo(18f, 10f)
    }
    val Play = lineIcon("Play") { moveTo(8f, 4f); lineTo(20f, 12f); lineTo(8f, 20f); close() }
    val Bookmark = lineIcon("Bookmark") {
        moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f)
        lineTo(12f, 17f); lineTo(6f, 21f); close()
    }
    val Back = lineIcon("Back", mirrored = true) {
        moveTo(20f, 12f); lineTo(4f, 12f); moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f)
    }
    val More = lineIcon("More") {
        for (y in listOf(5f, 12f, 19f)) { moveTo(12f, y); lineTo(12f, y + .1f) }
    }
    val ChevronRight = lineIcon("ChevronRight", mirrored = true) {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    }
    val Typography = lineIcon("Typography") {
        moveTo(4f, 6f); lineTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 6f)
        moveTo(12f, 4f); lineTo(12f, 20f); moveTo(8f, 20f); lineTo(16f, 20f)
    }
    val Background = lineIcon("Background") {
        moveTo(5f, 3f); lineTo(19f, 3f); quadTo(21f, 3f, 21f, 5f)
        lineTo(21f, 19f); quadTo(21f, 21f, 19f, 21f)
        lineTo(5f, 21f); quadTo(3f, 21f, 3f, 19f); lineTo(3f, 5f); quadTo(3f, 3f, 5f, 3f)
        moveTo(3f, 17f); lineTo(9f, 11f); lineTo(14f, 16f); lineTo(17f, 13f); lineTo(21f, 17f)
        moveTo(16f, 7f); lineTo(16.1f, 7f)
    }
    val Screen = lineIcon("Screen") {
        moveTo(7f, 2.5f); lineTo(17f, 2.5f); quadTo(19f, 2.5f, 19f, 4.5f)
        lineTo(19f, 19.5f); quadTo(19f, 21.5f, 17f, 21.5f)
        lineTo(7f, 21.5f); quadTo(5f, 21.5f, 5f, 19.5f)
        lineTo(5f, 4.5f); quadTo(5f, 2.5f, 7f, 2.5f)
        moveTo(10f, 18f); lineTo(14f, 18f)
    }
    val Speech = lineIcon("Speech") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f)
        lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); quadTo(20f, 12f, 16f, 16f)
        moveTo(19f, 5f); quadTo(25f, 12f, 19f, 19f)
    }
    val Stats = lineIcon("Stats") {
        moveTo(4f, 3f); lineTo(4f, 21f); lineTo(21f, 21f)
        moveTo(8f, 17f); lineTo(8f, 12f); moveTo(13f, 17f); lineTo(13f, 6f)
        moveTo(18f, 17f); lineTo(18f, 9f)
    }
    val Reset = lineIcon("Reset", mirrored = true) {
        moveTo(4.5f, 12f)
        curveTo(4.5f, 16.1f, 7.9f, 19.5f, 12f, 19.5f)
        curveTo(16.1f, 19.5f, 19.5f, 16.1f, 19.5f, 12f)
        curveTo(19.5f, 7.9f, 16.1f, 4.5f, 12f, 4.5f)
        quadTo(8.3f, 4.5f, 5f, 8.8f)
        moveTo(4.5f, 4.8f); lineTo(4.5f, 9f); lineTo(8.8f, 9f)
    }
    val Info = lineIcon("Info") {
        moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
        moveTo(12f, 7f); lineTo(12f, 7.1f); moveTo(11f, 11f); lineTo(12f, 11f)
        lineTo(12f, 17f); moveTo(10f, 17f); lineTo(14f, 17f)
    }
}

private fun lineIcon(name: String, mirrored: Boolean = false, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
