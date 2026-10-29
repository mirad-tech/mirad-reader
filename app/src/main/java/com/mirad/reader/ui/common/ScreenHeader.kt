package com.mirad.reader.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 标题落在页面上，返回与操作各自形成轻玻璃控件组。 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    divider: Boolean = !large,
    root: Boolean = LocalAppRootPage.current,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val safeInsets = LocalPageSafeInsets.current ?: glassSafeInsets()
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
