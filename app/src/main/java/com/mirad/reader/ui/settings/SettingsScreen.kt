package com.mirad.reader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mirad.reader.AppContainer
import com.mirad.reader.BuildConfig
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.ReaderConfig
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
import com.mirad.reader.ui.design.Spacing
import com.mirad.reader.ui.settings.reading.ReadingSettingsHost
import com.mirad.reader.ui.shelf.GroupManagerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onOpenHidden: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val baseConfig by container.settings.global.collectAsState(initial = null)
    val appearance = LocalAppAppearance.current
    val appearanceController = LocalAppAppearanceController.current
    val systemDark = LocalAppSystemDark.current
    val isDark = LocalAppDarkTheme.current ?: systemDark
    val config = baseConfig?.let { appearance?.resolveReaderConfig(it, systemDark) ?: it }
    // 初始为空、进入组合后到 IO 线程刷新：available() 要列 assets 与字体目录，不能在主线程组合期执行。
    var fonts by remember { mutableStateOf(listOf<com.mirad.reader.core.model.ReadingFont>()) }
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    var showGroupManager by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val feedback = rememberFeedbackState("settings")

    LaunchedEffect(Unit) {
        fonts = withContext(Dispatchers.IO) { container.fonts.available() }
    }

    if (config == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val update: ((ReaderConfig) -> ReaderConfig) -> Unit = { transform ->
        appearanceController?.updateReadingConfig(isDark, transform)
    }
    val latestUpdate by rememberUpdatedState(update)

    DisposableEffect(appearanceController) {
        onDispose { appearanceController?.commitReadingConfig() }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { container.fonts.import(uri) }
                if (imported != null) {
                    fonts = withContext(Dispatchers.IO) { container.fonts.available() }
                    latestUpdate {
                        it.copy(
                            fontFamily = FontFamilyKey.CUSTOM,
                            customFontAsset = null,
                            customFontPath = imported.filePath
                        )
                    }
                }
            }
        }
    }
    val pickImage = rememberReadingBackgroundPicker(container)

    CompositionLocalProvider(LocalSettingsCommit provides { appearanceController?.commitReadingConfig() }) {
    SettingsContent(
        config = config, fonts = fonts, shelf = shelf,
        route = route, onBack = onBack, onOpenRoute = onOpenRoute,
        onOpenHidden = onOpenHidden, onChange = { update(it) },
        onImportFont = { fontPicker.launch(arrayOf("*/*")) },
        onDeleteFont = { font -> scope.launch {
            withContext(Dispatchers.IO) { container.fonts.delete(font) }
            fonts = withContext(Dispatchers.IO) { container.fonts.available() }
        } },
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
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit = {},
    onImportFont: () -> Unit = {},
    onDeleteFont: (com.mirad.reader.core.model.ReadingFont) -> Unit = {},
    onPickImage: (dark: Boolean) -> Unit = {},
    groupCount: Int = 0,
    onManageGroups: () -> Unit = {},
    onResetReading: () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    initialScrollPx: Int = 0
) {
    val hiddenCount = shelf.count { it.book.isHidden }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { snackbarHost() },
        topBar = { ScreenHeader(title = route?.title ?: "设置", onBack = onBack,
            subtitle = if (route is SettingsRoute.Reading) "全书通用" else null) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource()
                .verticalScroll(rememberScrollState(initial = initialScrollPx))
                .padding(padding)
                .padding(horizontal = Spacing.lg)
        ) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (route == null) {
                SettingCard(title = "阅读设置") {
                    readingSettingsPages().forEach { page ->
                        SettingNavRow(page.title, onClick = { onOpenRoute(SettingsRoute.Reading(page)) })
                    }
                    CardDivider()
                    SettingNavRow("恢复默认", onClick = onResetReading)
                }
                SettingCard(title = "应用设置") {
                    appSettingsPages.forEach { page ->
                        SettingNavRow(page.title, onClick = { onOpenRoute(SettingsRoute.App(page)) })
                    }
                }
            } else when (route) {
                is SettingsRoute.Reading -> ReadingSettingsHost(
                    page = route.page, config = config, onChange = onChange, fonts = fonts,
                    onImportFont = onImportFont, onDeleteFont = onDeleteFont, onPickImage = onPickImage,
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
                            text = "墨读 v${BuildConfig.VERSION_NAME}",
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
