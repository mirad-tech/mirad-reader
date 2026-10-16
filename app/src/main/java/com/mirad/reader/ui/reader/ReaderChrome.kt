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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.Slider
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
        Column {
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
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            Slider(
                value = percent.coerceIn(0f, 1f),
                onValueChange = onSliderChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(28.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarAction("目录", onToc)
                BarAction("笔记", onNotes)
                BarAction(if (speaking) "暂停" else "朗读", onTts, active = speaking)
                BarAction("搜索", onSearch)
                BarAction("设置", onSettings)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "第 ${chapterIndex + 1}/$chapterCount 章 · 第 ${page + 1}/$pages 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun BarAction(text: String, onClick: () -> Unit, active: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 目录/进度等面板的通用头部。 */
@Composable
fun SheetHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 进度面板里的小统计块。 */
@Composable
fun ProgressStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun MiniDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
fun NewBadge(text: String) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
