package com.mirad.reader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.BuildConfig
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.OrientationKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.TapZoneKey
import com.mirad.reader.core.model.TextAlignKey
import com.mirad.reader.data.db.entity.GroupEntity
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.common.BackgroundSection
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.FontSection
import com.mirad.reader.ui.common.InlineBanner
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.RowDivider
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingNavRow
import com.mirad.reader.ui.common.SettingSwitchRow
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局默认阅读设置。阅读器内改的设置只对那本书生效，这里改的是"新书默认值"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    category: SettingsCategory? = null,
    onOpenCategory: (SettingsCategory) -> Unit = {},
    onOpenHidden: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ReaderConfig.DEFAULT) }
    var fonts by remember { mutableStateOf(container.fonts.available()) }
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    var renameTarget by remember { mutableStateOf<GroupEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    val hiddenCount = shelf.count { it.book.isHidden }

    LaunchedEffect(Unit) {
        config = container.settings.currentGlobal()
        fonts = withContext(Dispatchers.IO) { container.fonts.available() }
    }

    fun update(transform: (ReaderConfig) -> ReaderConfig) {
        val updated = transform(config)
        config = updated
        container.settings.queueGlobalConfig(updated, container.appScope)
    }

    DisposableEffect(container) {
        onDispose { container.settings.flushGlobalConfig(container.appScope) }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { container.fonts.import(uri) }
                if (imported != null) {
                    fonts = withContext(Dispatchers.IO) { container.fonts.available() }
                    update {
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
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val target = File(container.paths.backgroundsDir, "bg_default.jpg")
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        // 同上：输入流为 null 时不能当作成功，否则会提示已设置背景但图片没落盘
                        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
                        input.use { source ->
                            target.outputStream().use { output -> source.copyTo(output) }
                        }
                        true
                    }.getOrDefault(false)
                }
                if (ok) update { it.copy(backgroundType = BackgroundType.IMAGE, backgroundImageUri = target.absolutePath) }
            }
        }
    }

    CompositionLocalProvider(LocalSettingsCommit provides { container.settings.flushGlobalConfig(container.appScope) }) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenHeader(title = category?.title ?: "设置", onBack = onBack,
            subtitle = if (category != null && category != SettingsCategory.SHELF && category != SettingsCategory.ABOUT) "全局默认" else null) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (category == null) {
                SettingCard { SettingsCategory.entries.forEach { item ->
                    SettingNavRow(item.title, onClick = { onOpenCategory(item) })
                } }
            } else if (category != SettingsCategory.SHELF && category != SettingsCategory.ABOUT) {
                ReaderSettingsContent(category, config, { update(it) }, fonts,
                    onImportFont = { fontPicker.launch(arrayOf("*/*")) },
                    onDeleteFont = { font -> scope.launch {
                        withContext(Dispatchers.IO) { container.fonts.delete(font) }
                        fonts = withContext(Dispatchers.IO) { container.fonts.available() }
                    } },
                    onPickImage = { imagePicker.launch(arrayOf("image/*")) })
            }
            if (category == SettingsCategory.SHELF) {
            SettingCard(title = "书架管理") {
                SettingNavRow(
                    label = "隐藏的书籍",
                    value = if (hiddenCount == 0) "无" else "$hiddenCount 本",
                    subtitle = "隐藏后仍会保留阅读记录，可随时恢复",
                    onClick = onOpenHidden
                )
                CardDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "分组",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (groups.isEmpty()) "还没有分组" else "${groups.size} 个",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (groups.isEmpty()) {
                    Text(
                        text = "在书架上长按选中的书即可「移入分组」，也可以新建分组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    groups.forEach { group ->
                        val count = shelf.count { it.book.groupId == group.id }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$group.name（$count 本）",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                renameTarget = group
                                renameDraft = group.name
                            }) { Text("重命名") }
                            TextButton(onClick = {
                                scope.launch { container.groups.delete(group) }
                            }) { Text("删除") }
                        }
                    }
                }
            }

            }
            if (category == SettingsCategory.ABOUT) {
            SettingCard(title = "关于") {
                Text(
                    text = "墨读 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            }

            Spacer(modifier = Modifier.height(Spacing.xxxl))
        }
    }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("分组名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameDraft.trim()
                    renameTarget = null
                    if (name.isNotEmpty() && name != target.name) {
                        scope.launch { container.groups.rename(target, name) }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }
}
