package com.mirad.reader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 普通页面统一整条悬浮玻璃，二级页面用返回按钮表达层级。 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    divider: Boolean = !large,
    root: Boolean = LocalAppRootPage.current,
    floatingGlass: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val safeInsets = LocalPageSafeInsets.current ?: glassSafeInsets()
    if (floatingGlass) {
        val titleStyle = MaterialTheme.typography.headlineSmall
        Box(modifier.widthIn(max = 720.dp).fillMaxWidth()
            .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 6.dp)) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                minimumTintAlpha = if (MaterialTheme.colorScheme.surface.luminance() < .5f) .78f else .66f,
                shadowElevation = 1.dp
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onBack != null && !root) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = titleStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                    }
                }
            }
        }
        return
    }
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null && !root) {
            GlassSurface(shape = CircleShape) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (root || large) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassSurface(shape = RoundedCornerShape(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
