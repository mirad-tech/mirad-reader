package com.mirad.reader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.mirad.reader.ui.common.bouncingVerticalScroll
import com.mirad.reader.ui.common.BouncingLazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.ReadingStats
import com.mirad.reader.core.model.StatsMath
import com.mirad.reader.ui.common.glassBackdropSource
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.StatTile
import com.mirad.reader.ui.design.LocalMiradColors
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * 阅读统计：今日主卡 + 统计瓦片 + 近 30 天柱状图。
 */
@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit) {
    var stats by remember { mutableStateOf<ReadingStats?>(null) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { container.stats.stats() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenHeader(title = "阅读统计", onBack = onBack) }
    ) { padding ->
        val current = stats
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource()
        ) {
            if (current == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (current.sessionCount == 0) {
                StatsEmptyContent(padding)
            } else {
                StatsContent(current, padding)
            }
        }
    }
}

/** 与隔离预览共用空页，实际数据仍由 StatsScreen 读取。 */
@Composable
internal fun StatsEmptyContent(contentPadding: PaddingValues) {
    BouncingLazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            EmptyState(
                title = "还没有阅读记录",
                badge = "时",
                description = "阅读满 5 秒就会开始统计，读一读就有数据了",
                modifier = Modifier.fillParentMaxSize()
            )
        }
    }
}

@Composable
private fun StatsContent(stats: ReadingStats, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bouncingVerticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = Spacing.lg)
    ) {
        Spacer(modifier = Modifier.height(Spacing.sm))

        // 今日：主卡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                .padding(Spacing.xl)
        ) {
            Text(
                text = "今日阅读",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = StatsMath.formatDuration(stats.todayMs),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                InlineStat("连续", "${stats.streakDays} 天")
                InlineStat("今日字数", "${stats.todayChars} 字")
                InlineStat("累计记录", "${stats.sessionCount} 次")
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        SettingCard(title = "各时间段") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
                StatTile("本周", StatsMath.formatDuration(stats.weekMs), Modifier.weight(1f))
                StatTile("本月", StatsMath.formatDuration(stats.monthMs), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
                StatTile("累计时长", StatsMath.formatDuration(stats.totalMs), Modifier.weight(1f))
                StatTile("累计字数", "${stats.totalChars} 字", Modifier.weight(1f))
            }
        }

        SettingCard(title = "最近 30 天") {
            DailyChart(stats)
            Text(
                text = "柱高表示当天阅读时长；单次阅读满 5 秒才计入，单次上限 6 小时。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xxxl))
    }
}

@Composable
private fun InlineStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun DailyChart(stats: ReadingStats) {
    val maxMs = stats.daily.maxOfOrNull { it.durationMs } ?: 0L
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = LocalMiradColors.current.chartEmpty
    val baseline = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        val count = stats.daily.size.coerceAtLeast(1)
        val gap = size.width * 0.025f
        val barWidth = (size.width - gap * (count - 1)) / count
        val radius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
        val chartHeight = size.height - 6.dp.toPx()

        drawLine(
            color = baseline,
            start = Offset(0f, chartHeight),
            end = Offset(size.width, chartHeight),
            strokeWidth = 1.dp.toPx()
        )

        stats.daily.forEachIndexed { index, day ->
            val ratio = if (maxMs <= 0L) 0f else (day.durationMs.toFloat() / maxMs)
            val left = index * (barWidth + gap)
            if (ratio > 0f) {
                val barHeight = (chartHeight * ratio.coerceIn(0.04f, 1f))
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius
                )
            } else {
                drawRoundRect(
                    color = emptyColor,
                    topLeft = Offset(left, chartHeight - 3.dp.toPx()),
                    size = Size(barWidth, 3.dp.toPx()),
                    cornerRadius = radius
                )
            }
        }
    }
    val formatter = DateTimeFormatter.ofPattern("MM-dd")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stats.daily.firstOrNull()?.date?.format(formatter).orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "峰值 ${StatsMath.formatDuration(maxMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stats.daily.lastOrNull()?.date?.format(formatter).orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
