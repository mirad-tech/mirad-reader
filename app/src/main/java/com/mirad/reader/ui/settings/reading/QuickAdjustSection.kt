package com.mirad.reader.ui.settings.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.LocalAppAppearance
import com.mirad.reader.ui.common.LocalAppAppearanceController
import com.mirad.reader.ui.common.LocalAppDarkTheme
import com.mirad.reader.ui.common.readingThemeChoices
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 日夜模式快捷切换：与「界面外观」页写同一个应用模式字段，
 * 阅读面板里不用跳出去就能切换。无外观记录的隔离预览不渲染。
 */
@Composable
internal fun AppModeQuickRow() {
    val appearance = LocalAppAppearance.current ?: return
    val controller = LocalAppAppearanceController.current ?: return
    ChoiceRow(
        label = "日夜模式",
        options = listOf(
            AppThemeMode.SYSTEM.name to "跟随系统",
            AppThemeMode.DAY.name to "日间",
            AppThemeMode.NIGHT.name to "夜间"
        ),
        selected = appearance.mode.name,
        onSelect = { key ->
            AppThemeMode.entries.firstOrNull { it.name == key }?.let(controller::setMode)
            controller.commit()
        },
        columns = 3
    )
}

/**
 * 当前生效组的主题色板：每个主题一个底色色块，点击写入当前组并切回纯色背景。
 * 与 BackgroundSection 相同的分区写入与隔离预览回退。
 */
@Composable
internal fun QuickThemeSwatches(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
    val appearance = LocalAppAppearance.current
    val controller = LocalAppAppearanceController.current
    val effectiveDark = LocalAppDarkTheme.current
        ?: ThemePaletteResolver.resolve(config, isSystemInDarkTheme()).isDark
    val style = appearance?.style(effectiveDark)
        ?: ReadingStyle.from(config).normalized(effectiveDark)
    val change: ((ReaderConfig) -> ReaderConfig) -> Unit = { transform ->
        if (controller != null) controller.updateReadingConfig(effectiveDark, transform)
        else onChange(transform)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        readingThemeChoices(effectiveDark).forEach { (key, label) ->
            val swatchColor = if (key == ThemeKey.CUSTOM) {
                Color(style.backgroundColor)
            } else {
                Color(ThemePaletteResolver.resolve(config.copy(theme = key), effectiveDark).background)
            }
            val selected = style.theme == key
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(swatchColor, CircleShape)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
                    .clickable {
                        change { it.copy(theme = key, backgroundType = BackgroundType.COLOR) }
                    }
            )
        }
    }
}
