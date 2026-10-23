package com.mirad.reader.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一按钮规范样式：
 * 14sp 字号、21sp 行高、常规字重、文字居中、12dp 圆角、至少 40dp 视觉高度和 48dp 实际触摸区域。
 */
val MiradButtonTextStyle = TextStyle(
    fontSize = 14.sp,
    lineHeight = 21.sp,
    fontWeight = FontWeight.Normal,
    textAlign = TextAlign.Center
)

val MiradButtonShape = RoundedCornerShape(12.dp)

@Composable
fun MiradButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    val containerColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val contentColor = if (danger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .heightIn(min = 40.dp)
            .minimumInteractiveComponentSize(),
        shape = MiradButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = contentPadding
    ) {
        ProvideTextStyle(MiradButtonTextStyle) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                content()
            }
        }
    }
}

@Composable
fun MiradOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    val outlineColor = if (danger) MaterialTheme.colorScheme.error
    else if (!enabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
    else MaterialTheme.colorScheme.outlineVariant
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .heightIn(min = 40.dp)
            .minimumInteractiveComponentSize(),
        shape = MiradButtonShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, outlineColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = contentPadding
    ) {
        ProvideTextStyle(MiradButtonTextStyle) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                content()
            }
        }
    }
}

@Composable
fun MiradTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .heightIn(min = 40.dp)
            .minimumInteractiveComponentSize(),
        shape = MiradButtonShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = contentPadding
    ) {
        ProvideTextStyle(MiradButtonTextStyle) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                content()
            }
        }
    }
}

/**
 * 设置项分组卡片：统一的圆角、描边与内边距。
 */
@Composable
fun SettingCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                content()
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 设置项标题（卡片内部的小标题）。 */
@Composable
fun ItemLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
}

/** 带数值的滑杆：标签与数值同一行，滑杆紧跟其后。 */
val LocalSettingsCommit = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: (() -> Unit)? = null,
    steps: Int = 0
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        MiradSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onCommit ?: LocalSettingsCommit.current,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
        )
    }
}

/** 胶囊选项，自动按列数换行。选中用主色实心，未选中为描边。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 4
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = columns
        ) {
            options.forEach { (value, text) ->
                ChoiceChip(text = text, selected = value == selected, onClick = { onSelect(value) })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null
) {
    val selectedColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        tween(120),
        label = "choice"
    )
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(MiradButtonShape)
            .background(if (selected && enabled) selectedColor else Color.Transparent)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = MiradButtonShape
            )
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.RadioButton
            )
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MiradButtonTextStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = textColor
        )
    }
}

/** 分段切换：统一 14sp、21sp、常规字重、文字居中、12dp 圆角、至少 40dp 视觉高度和 48dp 实际触摸区域。 */
@Composable
fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MiradButtonShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, text) ->
            val isSelected = value == selected
            val segmentColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                tween(120),
                label = "segment"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(segmentColor)
                    .heightIn(min = 40.dp)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .semantics { contentDescription = text },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    style = MiradButtonTextStyle,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 颜色圆点，选中打勾：
 * 使用 FlowRow 换行布局，保证每个色点拥有独立 48dp 触摸区域而不发生重叠。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorDotRow(
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        colors.forEach { argb ->
            val isSelected = argb == selected
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .clickable(role = Role.RadioButton) { onSelect(argb) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        val dark = com.mirad.reader.core.model.ThemePaletteResolver.isDarkColor(argb)
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已选择颜色",
                            tint = if (dark) com.mirad.reader.ui.design.LocalMiradColors.current.checkOnDarkSwatch
                            else com.mirad.reader.ui.design.LocalMiradColors.current.selectionCheck,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 设置项一行：左侧标题（可带副标题），右侧开关。 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
