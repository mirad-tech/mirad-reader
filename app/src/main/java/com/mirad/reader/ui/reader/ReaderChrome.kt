package com.mirad.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.mirad.reader.ui.common.MiradSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Same safe area for controls and text, including transient bars in immersive mode. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun readerSafeInsets(): WindowInsets =
    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

/**
 * 阅读顶栏：只保留「返回 / 章节信息 / 书签 / 更多」。
 * 朗读、笔记、搜索、目录、设置都收到下方的操作条里，避免顶栏挤 5 个图标。
 */
@Composable
fun ReaderTopBar(
    title: String,
    subtitle: String,
    hasBookmark: Boolean,
    speaking: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onNotes: () -> Unit,
    onSearch: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
    onToggleTts: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onBookmark) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "书签",
                        tint = if (hasBookmark) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("目录") }, onClick = { menu = false; onToc() })
                    DropdownMenuItem(text = { Text("笔记与书签") }, onClick = { menu = false; onNotes() })
                    DropdownMenuItem(
                        text = { Text(if (speaking) "暂停朗读" else "朗读本章") },
                        onClick = { menu = false; onToggleTts() }
                    )
                    DropdownMenuItem(text = { Text("全书搜索") }, onClick = { menu = false; onSearch() })
                    DropdownMenuItem(text = { Text("阅读设置") }, onClick = { menu = false; onSettings() })
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        }
    }
}

/**
 * 阅读底栏：一条可拖动的细进度条 + 排成一行的操作。
 */
@Composable
fun ReaderBottomBar(
    percent: Float,
    page: Int,
    pages: Int,
    chapterIndex: Int,
    chapterCount: Int,
    onToc: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
    onNotes: () -> Unit,
    onSearch: () -> Unit,
    onTts: () -> Unit,
    speaking: Boolean,
    onSliderChange: (Float) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            val draftState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<Float?>(null)
            }
            MiradSlider(
                value = (draftState.value ?: percent).coerceIn(0f, 1f),
                // 拖动中只改本地值，松手才真正跳转：否则每帧都会触发一次定位计算，又卡又飘。
                onValueChange = { draftState.value = it },
                onValueChangeFinished = {
                    val target = draftState.value
                    draftState.value = null
                    if (target != null) onSliderChange(target)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarAction("目录", Icons.Default.List, onToc)
                BarAction("笔记", Icons.Default.Edit, onNotes)
                BarAction(
                    text = if (speaking) "暂停" else "朗读",
                    icon = if (speaking) Icons.Default.Clear else Icons.Default.PlayArrow,
                    onClick = onTts,
                    active = speaking
                )
                BarAction("搜索", Icons.Default.Search, onSearch)
                BarAction("设置", Icons.Default.Settings, onSettings)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onProgress)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第 ${chapterIndex + 1}/$chapterCount 章 · 第 ${page + 1}/$pages 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${((draftState.value ?: percent) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "进度详情",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BarAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
