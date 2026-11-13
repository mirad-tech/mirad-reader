package com.mirad.reader.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import com.mirad.reader.ui.common.MiradLinearProgress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.ui.design.Elevation
import com.mirad.reader.ui.design.LocalMiradColors
import com.mirad.reader.ui.design.Sizes
import com.mirad.reader.ui.design.Spacing

// ---------------------------------------------------------------- 书籍卡片

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun BookGridCard(
    item: ShelfBook,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val badgeLineHeight = MaterialTheme.typography.labelSmall.lineHeight
    val titleTopInset = with(LocalDensity.current) { maxOf(32.dp, badgeLineHeight.toDp() + 11.dp) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Sizes.coverAspect)
                .shadow(Elevation.bookCover, RoundedCornerShape(Sizes.coverCorner))
                .clip(RoundedCornerShape(Sizes.coverCorner))
        ) {
            BookCover(book = item.book, modifier = Modifier.fillMaxSize(),
                titleTopInset = titleTopInset, progress = if (item.book.finished) 0f else item.percent)
            if (item.book.isPinned) {
                Badge(text = "置顶", modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else LocalMiradColors.current.coverScrim
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.book.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = item.book.format,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.xs)
            )
            if (item.book.finished) {
                FinishedLabel(MaterialTheme.typography.labelSmall)
            } else if (item.percent > 0f) {
                Text(
                    text = "${(item.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookListRow(
    item: ShelfBook,
    selected: Boolean,
    percentColumnWidth: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                book = item.book,
                modifier = Modifier
                    .width(48.dp)
                    .height(66.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.book.author ?: "未知作者"} · ${item.book.totalChapters} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.book.finished && item.percent > 0f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    InlineProgress(item.percent)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(Modifier.width(percentColumnWidth), contentAlignment = Alignment.CenterEnd) {
                if (item.book.finished) {
                    FinishedLabel(listProgressLabelStyle())
                } else if (item.percent > 0f) {
                    Text(
                        text = "${(item.percent * 100).toInt()}%",
                        style = listProgressLabelStyle(),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private val FinishedIconSize = 14.dp
private const val FinishedText = "已读完"

@Composable
private fun FinishedLabel(style: TextStyle) {
    val color = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(Icons.Default.Check, contentDescription = null, tint = color, modifier = Modifier.size(FinishedIconSize))
        Text(FinishedText, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun listProgressLabelWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val style = listProgressLabelStyle()
    val percentWidth = measurer.measure("100%", style = style, maxLines = 1).size.width
    val finishedWidth = measurer.measure(FinishedText, style = style, maxLines = 1).size.width
    return with(LocalDensity.current) {
        maxOf(percentWidth, finishedWidth + FinishedIconSize.roundToPx() + Spacing.xs.roundToPx()).toDp()
    }
}

@Composable
private fun listProgressLabelStyle() = MaterialTheme.typography.labelMedium.copy(
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.Medium
)

/** 列表模式：独立细进度条。 */
@Composable
private fun InlineProgress(percent: Float) {
    MiradLinearProgress(
        progress = { percent.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
    )
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/** 「3 小时前 / 昨天 / 3 天前」这类相对时间，书架副标题用。 */
internal fun formatRelative(timeMillis: Long): String {
    if (timeMillis <= 0L) return "还没读过"
    val diff = System.currentTimeMillis() - timeMillis
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 2 -> "昨天"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> {
            val days = minutes / (60 * 24)
            "${days / 30} 个月前"
        }
    }
}
