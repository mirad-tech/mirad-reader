package com.mirad.reader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.ReadingStats
import com.mirad.reader.core.model.StatsMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit) {
    var stats by remember { mutableStateOf<ReadingStats?>(null) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { container.stats.stats() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val current = stats
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { Text("统计中…") }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("今日", StatsMath.formatDuration(current.todayMs), Modifier.weight(1f))
                StatCard("本周", StatsMath.formatDuration(current.weekMs), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("本月", StatsMath.formatDuration(current.monthMs), Modifier.weight(1f))
                StatCard("累计", StatsMath.formatDuration(current.totalMs), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("连续阅读", "${current.streakDays} 天", Modifier.weight(1f))
                StatCard("今日字数", "${current.todayChars} 字", Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text("最近 30 天阅读时长", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            DailyChart(current)

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "累计阅读 ${current.totalChars} 字，共 ${current.sessionCount} 次阅读记录。\n" +
                    "统计口径：单次阅读满 5 秒才计入，单次上限 6 小时。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DailyChart(stats: ReadingStats) {
    val maxMs = stats.daily.maxOfOrNull { it.durationMs } ?: 0L
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        val count = stats.daily.size.coerceAtLeast(1)
        val gap = size.width * 0.02f
        val barWidth = (size.width - gap * (count - 1)) / count
        val labelHeight = 6.dp.toPx()
        val chartHeight = size.height - labelHeight * 2
        stats.daily.forEachIndexed { index, day ->
            val ratio = if (maxMs <= 0L) 0f else (day.durationMs.toFloat() / maxMs)
            val barHeight = chartHeight * ratio.coerceIn(0f, 1f)
            val left = index * (barWidth + gap)
            if (barHeight > 0f) {
                drawRect(
                    color = barColor,
                    topLeft = Offset(left, chartHeight - barHeight),
                    size = Size(barWidth, barHeight)
                )
            } else {
                drawRect(
                    color = Color(0x22000000),
                    topLeft = Offset(left, chartHeight - 2.dp.toPx()),
                    size = Size(barWidth, 2.dp.toPx())
                )
            }
        }
    }
    val formatter = DateTimeFormatter.ofPattern("MM-dd")
    val first = stats.daily.firstOrNull()?.date?.format(formatter).orEmpty()
    val last = stats.daily.lastOrNull()?.date?.format(formatter).orEmpty()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(first, style = MaterialTheme.typography.labelSmall)
        Text("峰值 ${StatsMath.formatDuration(maxMs)}", style = MaterialTheme.typography.labelSmall)
        Text(last, style = MaterialTheme.typography.labelSmall)
    }
}
