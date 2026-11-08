package com.mirad.reader.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.mirad.reader.ui.common.bouncingVerticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.mirad.reader.ui.common.MiradScaffold as Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mirad.reader.AppContainer
import com.mirad.reader.BuildConfig
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReadingFonts
import com.mirad.reader.ui.MiradIcons
import com.mirad.reader.ui.common.LocalGlassAppearance
import com.mirad.reader.ui.common.readingThemeChoices
import com.mirad.reader.ui.theme.MiradSerif
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.FeedbackHost
import com.mirad.reader.ui.common.LocalAppAppearance
import com.mirad.reader.ui.common.LocalAppAppearanceController
import com.mirad.reader.ui.common.LocalAppDarkTheme
import com.mirad.reader.ui.common.LocalAppSystemDark
import com.mirad.reader.ui.common.LocalSettingsCommit
import com.mirad.reader.ui.common.ResetReadingDialog
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingNavRow
import com.mirad.reader.ui.common.glassBackdropSource
import com.mirad.reader.ui.common.rememberFeedbackState
import com.mirad.reader.ui.common.rememberReadingBackgroundPicker
import com.mirad.reader.ui.design.Elevation
import com.mirad.reader.ui.design.Sizes
import com.mirad.reader.ui.design.Spacing
import com.mirad.reader.ui.settings.reading.ReadingSettingsHost
import com.mirad.reader.ui.shelf.GroupManagerSheet
import com.mirad.reader.ui.common.rememberReadingFonts

/**
 * 全书通用阅读设置与应用设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    route: SettingsRoute? = null,
    onOpenRoute: (SettingsRoute) -> Unit = {},
    onOpenHidden: () -> Unit = {},
    onOpenStats: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val baseConfig by container.settings.global.collectAsState(initial = null)
    val appearance = LocalAppAppearance.current
    val appearanceController = LocalAppAppearanceController.current
    val systemDark = LocalAppSystemDark.current
    val isDark = LocalAppDarkTheme.current ?: systemDark
    val config = baseConfig?.let { appearance?.resolveReaderConfig(it, systemDark) ?: it }
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    var showGroupManager by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val feedback = rememberFeedbackState("settings")

    val update: ((ReaderConfig) -> ReaderConfig) -> Unit = { transform ->
        appearanceController?.updateReadingConfig(isDark, transform)
    }

    val fontActions = rememberReadingFonts(container, scope, ready = config != null)

    if (config == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    DisposableEffect(appearanceController) {
        onDispose { appearanceController?.commitReadingConfig() }
    }

    val pickImage = rememberReadingBackgroundPicker(container)

    CompositionLocalProvider(LocalSettingsCommit provides { appearanceController?.commitReadingConfig() }) {
    SettingsContent(
        config = config, fonts = fontActions.fonts, shelf = shelf,
        route = route, onBack = onBack, onOpenRoute = onOpenRoute,
        onOpenHidden = onOpenHidden, onOpenStats = onOpenStats, onChange = { update(it) },
        onDeleteFont = fontActions.deleteFont,
        onPickImage = pickImage,
        groupCount = groups.size,
        onManageGroups = { showGroupManager = true },
        onResetReading = { showResetDialog = true },
        snackbarHost = { FeedbackHost(feedback) }
    )
    }

    if (showGroupManager) {
        GroupManagerSheet(container = container, onDismiss = { showGroupManager = false })
    }

    if (showResetDialog) {
        ResetReadingDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = { scopes ->
                appearanceController?.resetReadingConfig(scopes) { _, _ -> }
            }
        )
    }

}

/** Pure settings surface shared by the app and the isolated native preview. */
@Composable
internal fun SettingsContent(
    config: ReaderConfig = ReaderConfig.DEFAULT,
    fonts: List<com.mirad.reader.core.model.ReadingFont> = emptyList(),
    shelf: List<com.mirad.reader.data.repo.ShelfBook> = emptyList(),
    route: SettingsRoute? = null,
    onBack: () -> Unit = {},
    onOpenRoute: (SettingsRoute) -> Unit = {},
    onOpenHidden: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit = {},
    onDeleteFont: (com.mirad.reader.core.model.ReadingFont) -> Unit = {},
    onPickImage: (dark: Boolean) -> Unit = {},
    groupCount: Int = 0,
    onManageGroups: () -> Unit = {},
    onResetReading: () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    initialScrollPx: Int = 0
) {
    val hiddenCount = shelf.count { it.book.isHidden }
    val scrollState = rememberScrollState(initial = initialScrollPx)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { snackbarHost() },
        topBar = { ScreenHeader(title = route?.title ?: "设置", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource()
                .bouncingVerticalScroll(scrollState)
                .padding(padding)
                .padding(horizontal = Spacing.lg)
        ) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (route == null) {
                val dark = LocalAppDarkTheme.current ?: LocalAppSystemDark.current
                val fontLabel = if (config.fontFamily == FontFamilyKey.CUSTOM) {
                    ReadingFonts.customLabel(config, fonts) ?: "系统字体"
                } else when (config.fontFamily) {
                    FontFamilyKey.SERIF -> "宋体"
                    FontFamilyKey.SANS -> "黑体"
                    FontFamilyKey.KAITI -> "楷体"
                    FontFamilyKey.FANGSONG -> "仿宋"
                    FontFamilyKey.ROUND -> "圆体"
                    FontFamilyKey.LIGHT -> "细体"
                    FontFamilyKey.MONO -> "等宽"
                    else -> "系统字体"
                }
                val rowDivider = Modifier.padding(start = Sizes.settingsIconTile + Spacing.md)
                SettingCard(title = "阅读设置", itemSpacing = 0.dp, shadowElevation = Elevation.settingsGroup) {
                    readingSettingsPages().forEach { page ->
                        val summary = when (page) {
                            ReadingSettingsPage.TYPOGRAPHY -> "$fontLabel · ${config.fontSizeSp.toInt()}\u00A0px"
                            ReadingSettingsPage.THEME -> if (config.backgroundType == BackgroundType.IMAGE) "图片背景"
                                else readingThemeChoices(dark).firstOrNull { it.first == config.theme }?.second
                            ReadingSettingsPage.INTERACTION -> if (config.pageMode == PageMode.SCROLL) "上下滚动" else "左右翻页"
                            else -> null
                        }
                        val icon = when (page) {
                            ReadingSettingsPage.TYPOGRAPHY, ReadingSettingsPage.LAYOUT -> MiradIcons.Typography
                            ReadingSettingsPage.THEME -> MiradIcons.Background
                            ReadingSettingsPage.INTERACTION -> MiradIcons.Settings
                            ReadingSettingsPage.SCREEN -> MiradIcons.Screen
                            ReadingSettingsPage.HEADER_FOOTER -> MiradIcons.Contents
                            ReadingSettingsPage.EPUB -> MiradIcons.Shelf
                            ReadingSettingsPage.TTS -> MiradIcons.Speech
                        }
                        SettingNavRow(page.title, value = summary, icon = icon, highlightIcon = true,
                            onClick = { onOpenRoute(SettingsRoute.Reading(page)) })
                        CardDivider(rowDivider)
                    }
                    SettingNavRow("恢复默认", icon = MiradIcons.Reset, highlightIcon = true, onClick = onResetReading)
                }
                SettingCard(title = "阅读记录", shadowElevation = Elevation.settingsGroup) {
                    SettingNavRow("阅读统计", icon = MiradIcons.Stats, highlightIcon = true, onClick = onOpenStats)
                }
                SettingCard(title = "应用设置", itemSpacing = 0.dp, shadowElevation = Elevation.settingsGroup) {
                    val appearance = LocalAppAppearance.current
                    val glass = LocalGlassAppearance.current
                    appSettingsPages.forEachIndexed { index, page ->
                        if (index > 0) CardDivider(rowDivider)
                        val summary = when (page) {
                            AppSettingsPage.APPEARANCE -> listOfNotNull(
                                when (appearance?.mode) {
                                    AppThemeMode.SYSTEM -> "跟随系统"
                                    AppThemeMode.DAY -> "日间"
                                    AppThemeMode.NIGHT -> "夜间"
                                    null -> null
                                },
                                if (glass.enabled) "清晰度 ${glass.clarityPercent}%" else "玻璃已关闭"
                            ).joinToString(" · ")
                            AppSettingsPage.ABOUT -> BuildConfig.VERSION_NAME
                            else -> null
                        }
                        SettingNavRow(page.title, value = summary, highlightIcon = true,
                            icon = when (page) {
                                AppSettingsPage.APPEARANCE -> MiradIcons.Settings
                                AppSettingsPage.SHELF -> MiradIcons.Shelf
                                AppSettingsPage.ABOUT -> MiradIcons.Info
                            }, onClick = { onOpenRoute(SettingsRoute.App(page)) })
                    }
                }
            } else when (route) {
                is SettingsRoute.Reading -> ReadingSettingsHost(
                    page = route.page, config = config, onChange = onChange, fonts = fonts,
                    onDeleteFont = onDeleteFont, onPickImage = onPickImage,
                    onOpenPage = { onOpenRoute(SettingsRoute.Reading(it)) }
                )
                is SettingsRoute.App -> when (route.page) {
                    AppSettingsPage.APPEARANCE -> AppearanceSettings()
                    AppSettingsPage.SHELF -> ShelfManagementSection(
                        hiddenCount = hiddenCount, groupCount = groupCount,
                        onOpenHidden = onOpenHidden, onManageGroups = onManageGroups
                    )
                    AppSettingsPage.ABOUT -> SettingCard(title = "关于") {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontFamily = MiradSerif)) { append("墨读") }
                                append(" v${BuildConfig.VERSION_NAME}")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xxxl))
        }
    }
}

/** 书架管理分类页：隐藏书籍入口与分组管理入口（重命名/删除在 GroupManagerSheet 内）。 */
@Composable
private fun ShelfManagementSection(
    hiddenCount: Int,
    groupCount: Int,
    onOpenHidden: () -> Unit,
    onManageGroups: () -> Unit
) {
    SettingCard(title = "书架管理") {
        SettingNavRow(
            label = "隐藏的书籍",
            value = if (hiddenCount == 0) "无" else "$hiddenCount 本",
            subtitle = "隐藏后仍会保留阅读记录，可随时恢复",
            onClick = onOpenHidden
        )
        CardDivider()
        SettingNavRow(
            label = "分组管理",
            value = if (groupCount == 0) "还没有分组" else "$groupCount 个",
            onClick = onManageGroups
        )
    }
}
