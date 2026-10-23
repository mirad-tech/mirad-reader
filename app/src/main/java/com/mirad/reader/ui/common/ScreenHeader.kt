package com.mirad.reader.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.RectangleShape
import com.mirad.reader.ui.design.Spacing

/**
 * 全项目统一的页面顶栏。
 *
 * [large] = true 用于一级页面（书架：大标题 + 副标题），false 用于子页面（返回箭头 + 标题）。
 * 所有页面都走这里，避免有的页面是 TopAppBar、有的是自定义 Row。
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    divider: Boolean = !large,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val legacy = LocalGlassLegacy.current
    Box(modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
        .background(MaterialTheme.colorScheme.background))
    Box(Modifier.fillMaxWidth().statusBarsPadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(
        horizontal = if (legacy) 0.dp else 12.dp,
        vertical = if (legacy) 0.dp else 8.dp
    )) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = if (legacy) RectangleShape else MaterialTheme.shapes.large
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (onBack != null) Spacing.sm else if (large) Spacing.xl else Spacing.lg,
                        end = Spacing.sm,
                        top = if (large) Spacing.md else Spacing.sm,
                        bottom = Spacing.sm
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                actions()
            }
            if (divider && legacy) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            }
        }
    }
    }
    }
}
