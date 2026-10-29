package com.mirad.reader.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.BuiltinBackground
import com.mirad.reader.core.model.BuiltinBackgrounds
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.data.repo.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

interface AppAppearanceController {
    fun setMode(mode: AppThemeMode)
    fun commit()
    fun retrySave()
    fun updateReadingConfig(
        isDark: Boolean,
        transform: (ReaderConfig) -> ReaderConfig,
        onApplied: (ReaderConfig, AppAppearance) -> Unit = { _, _ -> }
    )
    fun resetReadingConfig(
        scopes: Set<ResetScope>,
        onApplied: (ReaderConfig, AppAppearance) -> Unit = { _, _ -> }
    )
    fun commitReadingConfig()
}

/** 恢复默认的范围：排版与交互走全局配置，日/夜样式各走外观记录里的一组。 */
enum class ResetScope { LAYOUT_INTERACTION, DAY_STYLE, NIGHT_STYLE }

/** One application-owned queue serializes read/modify/write across both editors and delayed imports. */
internal class ReadingConfigEditor(
    private val settings: SettingsRepository,
    private val applicationScope: CoroutineScope,
    private val callbackContext: CoroutineContext = Dispatchers.Main.immediate,
    private val onFailure: (Exception) -> Unit = {}
) {
    private val edits = settings.readingConfigEdits

    fun update(
        isDark: Boolean,
        transform: (ReaderConfig) -> ReaderConfig,
        onApplied: (ReaderConfig, AppAppearance) -> Unit = { _, _ -> }
    ): Job = enqueue(onApplied) {
        val base = settings.currentGlobal()
        val latest = settings.currentAppAppearance()
        val changed = transform(latest.style(isDark).applyTo(base))
        val updated = ReadingStyle.from(changed).normalized(isDark).applyTo(changed)
        settings.queueReadingConfig(updated, isDark, applicationScope)
        updated
    }

    /**
     * 分范围恢复默认：
     * - 排版与交互 → ReaderConfig 恢复默认，但保留五个样式字段（日/夜配色不动）；
     * - 日间/夜间配色 → 外观记录对应组恢复默认样式，其余全部不动。
     */
    fun resetReading(
        scopes: Set<ResetScope>,
        onApplied: (ReaderConfig, AppAppearance) -> Unit = { _, _ -> }
    ): Job = enqueue(onApplied) {
        val current = settings.currentGlobal()
        if (ResetScope.LAYOUT_INTERACTION in scopes) {
            settings.saveGlobal(ReadingStyle.from(current).applyTo(ReaderConfig.DEFAULT))
        }
        var appearance = settings.currentAppAppearance()
        if (ResetScope.DAY_STYLE in scopes) {
            appearance = appearance.withReadingStyle(false, ReadingStyle.DAY_DEFAULT)
            settings.saveAppAppearance(appearance)
        }
        if (ResetScope.NIGHT_STYLE in scopes) {
            appearance = appearance.withReadingStyle(true, ReadingStyle.NIGHT_DEFAULT)
            settings.saveAppAppearance(appearance)
        }
        settings.currentGlobal()
    }

    fun flush(): Job = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
        edits.withLock { settings.flushReadingConfig(applicationScope) }
    }

    private fun enqueue(
        onApplied: (ReaderConfig, AppAppearance) -> Unit,
        operation: suspend () -> ReaderConfig
    ): Job = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            edits.withLock {
                val config = operation()
                withContext(callbackContext) { onApplied(config, settings.currentAppAppearance()) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            withContext(callbackContext) { onFailure(error) }
        }
    }
}

/** A null value keeps isolated component previews independent of the application store. */
val LocalAppAppearance = staticCompositionLocalOf<AppAppearance?> { null }
val LocalAppDarkTheme = staticCompositionLocalOf<Boolean?> { null }
val LocalAppSystemDark = staticCompositionLocalOf { false }
val LocalAppAppearanceSaveFailed = staticCompositionLocalOf { false }
val LocalAppAppearanceController = staticCompositionLocalOf<AppAppearanceController?> { null }

internal fun readingThemeChoices(isDark: Boolean): List<Pair<String, String>> = if (isDark) {
    listOf(ThemeKey.DARK to "深灰", ThemeKey.BLACK to "纯黑", ThemeKey.CUSTOM to "自定义深色")
} else {
    listOf(
        ThemeKey.LIGHT to "白", ThemeKey.PAPER to "纸色", ThemeKey.SEPIA to "米黄",
        ThemeKey.GREEN to "护眼", ThemeKey.CUSTOM to "自定义浅色"
    )
}

internal fun readingBackgroundChoices(isDark: Boolean): List<BuiltinBackground> =
    BuiltinBackgrounds.ALL.filter { it.isDark == isDark }

internal fun readingCustomColors(isDark: Boolean, selected: Int): List<Int> {
    val colors = listOf(
        0xFFFFFFFF.toInt(), 0xFFF5F1E6.toInt(), 0xFFEDE0C8.toInt(),
        0xFFCCE8CF.toInt(), 0xFFB8D8E8.toInt(), 0xFF1A1C1E.toInt(), 0xFF000000.toInt()
    ).filter { ThemePaletteResolver.isDarkColor(it) == isDark }
    return if (ThemePaletteResolver.isDarkColor(selected) == isDark && selected !in colors) {
        colors + selected
    } else colors
}

/** The destination group belongs to the picker launch, even if system night mode changes outside the app. */
@Composable
internal fun rememberReadingBackgroundPicker(container: AppContainer): (dark: Boolean) -> Unit {
    val context = LocalContext.current.applicationContext
    val controller = LocalAppAppearanceController.current
    var targetDark by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val destination = targetDark
        targetDark = null
        if (uri != null && destination != null && controller != null) {
            container.appScope.launch {
                val imported = withContext(Dispatchers.IO) {
                    var target: File? = null
                    runCatching {
                        val suffix = when (context.contentResolver.getType(uri)) {
                            "image/png" -> ".png"
                            "image/webp" -> ".webp"
                            "image/gif" -> ".gif"
                            else -> ".jpg"
                        }
                        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                        input.use { source ->
                            // A fresh file preserves both saved groups and prevents partial writes from replacing an active image.
                            // 被替换的旧图不在这里删除：此处尚未确认持久化成功，清理由仓库在
                            // 外观成功落库后按已持久化记录的引用差集触发。
                            File.createTempFile(if (destination) "bg_night_" else "bg_day_", suffix,
                                container.paths.backgroundsDir).also { outputFile ->
                                target = outputFile
                                outputFile.outputStream().use { output -> source.copyTo(output) }
                            }
                        }
                    }.getOrElse {
                        target?.delete()
                        null
                    }
                }
                if (imported == null) {
                    withContext(Dispatchers.Main) {
                        container.feedback.show("背景图片读取失败，请重新选择。", FeedbackKind.ERROR)
                    }
                } else {
                    controller.updateReadingConfig(destination, { latest ->
                        latest.copy(backgroundType = BackgroundType.IMAGE, backgroundImageUri = imported.absolutePath)
                    }) { _, _ -> controller.commitReadingConfig() }
                }
            }
        }
    }
    // 调用方传入目标分区（日间/夜间样式组），导入的图片进入对应组。
    return { dark: Boolean ->
        targetDark = dark
        picker.launch(arrayOf("image/*"))
    }
}

@Composable
fun AppThemeModeSettings() {
    val appearance = LocalAppAppearance.current ?: return
    val controller = LocalAppAppearanceController.current ?: return
    DisposableEffect(controller) { onDispose { controller.commit() } }

    SettingCard(title = "日夜模式") {
        ChoiceRow(
            label = "",
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
}
