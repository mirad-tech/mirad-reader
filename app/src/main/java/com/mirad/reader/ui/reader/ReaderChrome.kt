package com.mirad.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.mirad.reader.ui.common.MiradSlider
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
import androidx.compose.ui.graphics.RectangleShape
import com.mirad.reader.ui.common.GlassSurface
import com.mirad.reader.ui.common.LocalGlassLegacy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ThemePalette
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Same safe area for controls and text, including transient bars in immersive mode. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun readerSafeInsets(): WindowInsets =
    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

// WebView rounds its viewport before centering columns; keep fractional edges outside cutouts.
internal fun readerHorizontalInsetDp(inset: Dp): Int =
    if (inset.value > 0f) ceil(inset.value).toInt() + 1 else 0

/**
 * 阅读顶栏：只保留「返回 / 章节信息 / 书签 / 更多」。
 * 笔记、搜索、目录、设置与自动阅读都收到下方的操作条里，避免顶栏挤 5 个图标；
 * 朗读在「更多」菜单里，不占底栏位置。
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
    onAutoRead: () -> Unit,
    onSettings: () -> Unit,
    onToggleTts: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val legacy = LocalGlassLegacy.current
    Box(Modifier.fillMaxWidth()
        .windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(horizontal = if (legacy) 0.dp else 4.dp, vertical = if (legacy) 0.dp else 10.dp)) {
    GlassSurface(Modifier.fillMaxWidth(), shape = if (legacy) RectangleShape else MaterialTheme.shapes.large) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                // 返回 / 文字列 / 右侧操作组之间留 8dp：旧版三组贴在一起，长标题时看着"挤成一团"。
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Column(
                    modifier = Modifier.weight(1f),
                    // 两行信息之间留 6dp，空副标题仍不占位。
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 空副标题不占一行：旧版用空字符串的 Text 占位，多出一行高度还拉大了视觉间距。
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onBookmark) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "书签",
                        tint = if (hasBookmark) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                ReaderDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("目录") }, onClick = { menu = false; onToc() })
                    DropdownMenuItem(text = { Text("笔记与书签") }, onClick = { menu = false; onNotes() })
                    DropdownMenuItem(
                        text = { Text(if (speaking) "暂停朗读" else "朗读本章") },
                        onClick = { menu = false; onToggleTts() }
                    )
                    DropdownMenuItem(text = { Text("全书搜索") }, onClick = { menu = false; onSearch() })
                    DropdownMenuItem(text = { Text("自动阅读") }, onClick = { menu = false; onAutoRead() })
                    DropdownMenuItem(text = { Text("阅读设置") }, onClick = { menu = false; onSettings() })
                }
                }
            }
            if (legacy) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        }
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
    onAutoRead: () -> Unit,
    autoReading: Boolean,
    onSliderChange: (Float) -> Unit
) {
    val legacy = LocalGlassLegacy.current
    Box(Modifier.fillMaxWidth()
        .windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
        .padding(horizontal = if (legacy) 0.dp else 4.dp, vertical = if (legacy) 0.dp else 10.dp)) {
    GlassSurface(Modifier.fillMaxWidth(), shape = if (legacy) RectangleShape else MaterialTheme.shapes.large) {
        Column {
            if (legacy) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
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
                    onSliderChange(target ?: percent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
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
                // 底栏给"自动阅读"而不是朗读：它是阅读中最常用的位置控制，
                // 朗读仍在右上「更多」菜单里（朗读本章 / 暂停朗读），功能不丢。
                BarAction(
                    text = "自动阅读",
                    icon = Icons.Default.PlayArrow,
                    onClick = onAutoRead,
                    active = autoReading
                )
                BarAction("搜索", Icons.Default.Search, onSearch)
                BarAction("设置", Icons.Default.Settings, onSettings)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
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
                        text = String.format(java.util.Locale.US, "%.1f%%", (draftState.value ?: percent) * 100f),
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
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
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

/** 页眉页脚：章节名 / 时间 / 电量 / 页码 / 进度。 */
@Composable
internal fun HeaderFooter(
    config: ReaderConfig,
    palette: ThemePalette,
    chapterTitle: String,
    page: Int,
    pages: Int,
    percent: Float,
    insetLeftDp: Int = -1
) {
    val clock = rememberClock(config.showClock)
    val battery = rememberBattery(config.showBattery)
    val textColor = Color(palette.secondaryText)
    // 正文列居中后，页眉页脚要跟着列左边缘走，否则会贴在屏幕边上与正文错位。
    val horizontal = if (insetLeftDp >= 0) (insetLeftDp + 4).dp else (config.horizontalPaddingDp + 4).dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = horizontal, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (config.showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    maxLines = 1
                )
                Row {
                    if (config.showBattery) {
                        Text("$battery%  ", style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                    if (config.showClock) {
                        Text(clock, style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                }
            }
        }
        if (config.showFooter) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1f%%", percent * 100f),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
                if (config.showPageNumber) {
                    Text(
                        text = "${page + 1} / ${pages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberClock(enabled: Boolean): String {
    var time by remember { mutableStateOf(currentTime()) }
    LaunchedEffect(enabled) {
        while (enabled) {
            time = currentTime()
            delay(20_000)
        }
    }
    return time
}

private fun currentTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun rememberBattery(enabled: Boolean): Int {
    val context = LocalContext.current
    var level by remember { mutableStateOf(readBattery(context)) }
    LaunchedEffect(enabled) {
        while (enabled) {
            level = readBattery(context)
            delay(60_000)
        }
    }
    return level
}

private fun readBattery(context: Context): Int = runCatching {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (level >= 0 && scale > 0) level * 100 / scale else -1
}.getOrDefault(-1)
