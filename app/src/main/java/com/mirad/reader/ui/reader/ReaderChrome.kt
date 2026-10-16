package com.mirad.reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
            TextButton(onClick = onToggleTts) {
                Text(if (speaking) "暂停" else "朗读")
            }
            IconButton(onClick = onBookmark) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "书签",
                    tint = if (hasBookmark) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNotes) {
                Icon(Icons.Default.List, contentDescription = "笔记")
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("目录") }, onClick = { menu = false; onToc() })
                DropdownMenuItem(text = { Text("阅读设置") }, onClick = { menu = false; onSettings() })
            }
        }
    }
}

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
    onSliderChange: (Float) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onToc) { Text("目录") }
                Slider(
                    value = percent.coerceIn(0f, 1f),
                    onValueChange = onSliderChange,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onProgress) { Text("进度") }
                TextButton(onClick = onSettings) { Text("设置") }
            }
            Text(
                text = "第 ${chapterIndex + 1}/$chapterCount 章 · 第 ${page + 1}/$pages 页 · ${(percent * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
