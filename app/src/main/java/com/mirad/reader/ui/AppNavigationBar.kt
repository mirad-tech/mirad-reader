package com.mirad.reader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirad.reader.ui.common.GlassSurface
import com.mirad.reader.ui.common.LocalPageSafeInsets
import com.mirad.reader.ui.common.glassSafeInsets

@Composable
internal fun AppNavigationBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val safeInsets = LocalPageSafeInsets.current ?: glassSafeInsets()
    Box(Modifier.fillMaxWidth()
        .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        GlassSurface(Modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp)) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppTab.entries.forEach { tab ->
                    val active = selected == tab
                    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    val fill by animateColorAsState(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                        else Color.Transparent, label = "navigationSelection")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(fill)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(tab) })
                        .semantics { contentDescription = "${tab.title}导航" }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(when (tab) {
                            AppTab.Shelf -> ShelfIcon
                            AppTab.Search -> Icons.Outlined.Search
                            AppTab.Notes -> NotesIcon
                            AppTab.Settings -> Icons.Outlined.Settings
                        }, null, tint = tint, modifier = Modifier.size(21.dp))
                        Text(tab.title, style = MaterialTheme.typography.labelMedium, color = tint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private val ShelfIcon = ImageVector.Builder("Shelf", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 6f); curveTo(9f, 4f, 5f, 4f, 2.5f, 5.5f)
        lineTo(2.5f, 19f); curveTo(5f, 17.5f, 9f, 17.5f, 12f, 19.5f)
        curveTo(15f, 17.5f, 19f, 17.5f, 21.5f, 19f)
        lineTo(21.5f, 5.5f); curveTo(19f, 4f, 15f, 4f, 12f, 6f)
        lineTo(12f, 19.5f)
    }
}.build()

private val NotesIcon = ImageVector.Builder("Notes", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(6f, 3f); lineTo(18f, 3f); quadTo(20f, 3f, 20f, 5f)
        lineTo(20f, 19f); quadTo(20f, 21f, 18f, 21f)
        lineTo(6f, 21f); quadTo(4f, 21f, 4f, 19f)
        lineTo(4f, 5f); quadTo(4f, 3f, 6f, 3f)
        moveTo(8f, 8f); lineTo(16f, 8f)
        moveTo(8f, 12f); lineTo(16f, 12f)
        moveTo(8f, 16f); lineTo(13f, 16f)
    }
}.build()
