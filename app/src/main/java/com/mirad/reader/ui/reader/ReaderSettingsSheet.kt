package com.mirad.reader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.mirad.reader.ui.common.bouncingVerticalScroll
import com.mirad.reader.ui.common.MiradOutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.AutoReadSpeed
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.settings.ReadingSettingsPage
import com.mirad.reader.ui.settings.canEditReaderTypography
import com.mirad.reader.ui.settings.reading.AppModeQuickRow
import com.mirad.reader.ui.settings.reading.FontSizeControl
import com.mirad.reader.ui.settings.reading.QuickThemeSwatches
import com.mirad.reader.ui.settings.reading.ReadingSettingsHost
import com.mirad.reader.ui.settings.readingSettingsPages
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SheetActionButton
import com.mirad.reader.ui.common.SheetScaffold
import com.mirad.reader.ui.design.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import com.mirad.reader.ui.common.MiradLinearProgress
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import com.mirad.reader.reader.AutoReadState
import com.mirad.reader.tts.TtsBus
import com.mirad.reader.tts.TtsController
import com.mirad.reader.ui.settings.reading.AutoReadSpeedControls
import com.mirad.reader.ui.common.SettingSwitchRow
import com.mirad.reader.ui.common.rememberReadingFonts

// ---------------------------------------------------------------- 阅读设置

@Composable
fun ReaderSettingsSheet(
    container: AppContainer,
    bookId: Long,
    isEpub: Boolean = false,
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onOpenAutoRead: () -> Unit,
    onResetGlobal: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val appearanceController = LocalAppAppearanceController.current
    val fontActions = rememberReadingFonts(container, scope, onChange)

    val pickImage = rememberReadingBackgroundPicker(container)

    DisposableEffect(appearanceController) {
        onDispose { appearanceController?.commitReadingConfig() }
    }
    CompositionLocalProvider(LocalSettingsCommit provides { appearanceController?.commitReadingConfig() }) {
        ReaderSettingsPanel(
            config = config, fonts = fontActions.fonts, isEpub = isEpub,
            onChange = onChange, onOpenAutoRead = onOpenAutoRead,
            onResetGlobal = onResetGlobal, onDismiss = onDismiss,
            onImportFont = fontActions.importFont,
            onDeleteFont = fontActions.deleteFont,
            onPickImage = pickImage
        )
    }
}

/** The same settings panel can be rendered with memory-only state in the native preview. */
@Composable
internal fun ReaderSettingsPanel(
    config: ReaderConfig,
    fonts: List<com.mirad.reader.core.model.ReadingFont> = emptyList(),
    isEpub: Boolean = false,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onOpenAutoRead: () -> Unit,
    onResetGlobal: () -> Unit,
    onDismiss: () -> Unit,
    onImportFont: () -> Unit = {},
    onDeleteFont: (com.mirad.reader.core.model.ReadingFont) -> Unit = {},
    onPickImage: (dark: Boolean) -> Unit = {},
    initialPage: ReadingSettingsPage? = null
) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialPage) }
    val scrollStates = ReadingSettingsPage.entries.associateWith { rememberScrollState() }
    val homeScroll = rememberScrollState()
    androidx.activity.compose.BackHandler(page != null) { page = null }
    SheetScaffold(
        title = page?.title ?: "阅读设置",
        subtitle = "全书通用",
        onDismiss = onDismiss,
        fillHeight = true,
        maxHeightFraction = 0.62f,
        actions = { if (page != null) SheetActionButton("返回", { page = null }) }
    ) {
        Column(Modifier.fillMaxWidth().bouncingVerticalScroll(page?.let { scrollStates.getValue(it) } ?: homeScroll).padding(horizontal = Spacing.xl)) {
            val selected = page
            if (selected == null) {
                // 快捷调节卡放最前：窄屏大字体下面板高度受限时，日夜模式与主题色块仍要一眼可达。
                SettingCard {
                    AppModeQuickRow()
                    QuickThemeSwatches(config, onChange)
                    CardDivider()
                    SegmentedRow(
                        options = listOf(PageMode.PAGED to "左右翻页", PageMode.SCROLL to "上下滚动"),
                        selected = config.pageMode,
                        onSelect = { mode -> onChange { it.copy(pageMode = mode) } }
                    )
                }
                if (canEditReaderTypography(config, isEpub)) {
                    SettingCard { FontSizeControl(config, onChange) }
                }
                SettingCard {
                    SettingNavRow(
                        label = "自动阅读",
                        value = AutoReadSpeed.summary(config),
                        subtitle = if (config.pageMode == PageMode.SCROLL) "匀速下滑，章末停留后接下一章" else "定时翻页，章末按间隔进入下一章",
                        onClick = onOpenAutoRead
                    )
                    CardDivider()
                    readingSettingsPages(isEpub)
                        .forEach { item -> SettingNavRow(item.title, onClick = { page = item }) }
                }
                MiradOutlinedButton(onClick = onResetGlobal, modifier = Modifier.fillMaxWidth()) { Text("恢复阅读默认设置") }
            } else {
                ReadingSettingsHost(page = selected, config = config, onChange = onChange, fonts = fonts,
                    onImportFont = onImportFont,
                    onDeleteFont = onDeleteFont,
                    onPickImage = onPickImage,
                    isEpub = isEpub,
                    onOpenPage = { page = it })
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}


// ---------------------------------------------------------------- 朗读

/** 跳到系统 TTS 设置；老机型没有该页面时退回应用详情页。 */
fun openTtsSettings(context: android.content.Context) {
    val candidates = listOf(
        android.content.Intent("com.android.settings.TTS_SETTINGS"),
        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        )
    )
    for (intent in candidates) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

@Composable
fun TtsSheet(
    tts: TtsController,
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onStart: (resume: Boolean, fromBeginning: Boolean) -> Unit,
    onClose: () -> Unit
) {
    val state by TtsBus.state.collectAsState()
    SheetScaffold(
        title = "朗读",
        subtitle = when {
            state.error != null -> state.error
            state.active -> "第 ${state.index + 1}/${state.total} 句"
            else -> "使用系统语音朗读当前章节"
        },
        onDismiss = onClose
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiradOutlinedButton(onClick = { tts.previous() }, modifier = Modifier.weight(1f)) { Text("上一句") }
                MiradOutlinedButton(
                    onClick = { if (state.active && state.playing) tts.pause() else onStart(state.active, false) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.playing) "暂停" else "播放") }
                MiradOutlinedButton(onClick = { tts.next() }, modifier = Modifier.weight(1f)) { Text("下一句") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiradOutlinedButton(onClick = { onStart(false, true) }, modifier = Modifier.weight(1f)) { Text("从本章开头") }
                MiradOutlinedButton(
                    onClick = { tts.stop(); onClose() },
                    modifier = Modifier.weight(1f)
                ) { Text("停止朗读") }
            }
            if (state.active) {
                MiradLinearProgress(
                    progress = { if (state.total <= 0) 0f else (state.index + 1).toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            ReadingSettingsHost(ReadingSettingsPage.TTS, config, onChange)
            Spacer(modifier = Modifier.height(Spacing.sm))
        }
    }
}

// ---------------------------------------------------------------- 自动阅读

/**
 * 自动阅读设置弹层：「更多」菜单与阅读设置首页共用这一个入口。
 *
 * 只保存速度，不保存运行状态；打开面板本身会暂停自动阅读，调速后需要手动「继续」。
 */
@Composable
fun AutoReadSheet(
    config: ReaderConfig,
    state: AutoReadState,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit
) {
    val paged = config.pageMode != PageMode.SCROLL
    val active = state != AutoReadState.OFF
    SheetScaffold(
        title = "自动阅读",
        subtitle = if (paged) "左右翻页 · 定时翻到下一页" else "上下滚动 · 按真实时间匀速下滑",
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingCard {
                SettingSwitchRow(
                    label = "自动阅读",
                    subtitle = when (state) {
                        AutoReadState.RUNNING -> "正在运行"
                        AutoReadState.PAUSED -> "已暂停，需要手动继续"
                        AutoReadState.OFF -> "关闭"
                    },
                    checked = active,
                    onCheckedChange = { enabled ->
                        // 关闭开关立即取消；开启会在关闭弹层与工具栏后开始。
                        if (enabled) onStart() else onStop()
                    }
                )
                AutoReadSpeedControls(config, onChange)
                if (!paged) {
                    Text(
                        text = "一屏按扣除正文上下安全留白后的可读高度计算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CardDivider()
                Text(
                    text = if (paged) {
                        "到章末后按同一间隔进入下一章；章节加载完成后重新计时。"
                    } else {
                        "到章末后停留一个「翻页间隔」，再进入下一章。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state == AutoReadState.PAUSED) {
                MiradOutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("继续自动阅读")
                }
            }
            Text(
                text = "触摸正文、打开菜单或切到后台会暂停；暂停后不会自动继续。速度设置全书通用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }
    }
}
