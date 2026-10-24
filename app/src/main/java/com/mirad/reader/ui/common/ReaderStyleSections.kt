package com.mirad.reader.ui.common

import android.content.Context
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.BuiltinBackground
import com.mirad.reader.core.model.BuiltinBackgrounds
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReadingFont
import com.mirad.reader.core.model.ReadingFonts
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.core.model.ThemePaletteResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 内置背景缩略图缓存。 */
private object BackgroundThumbs {
    private val cache = LruCache<String, ImageBitmap>(24)

    suspend fun load(context: Context, assetName: String): ImageBitmap? {
        cache.get(assetName)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(assetName).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 160) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.assets.open(assetName).use {
                    BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
        bitmap?.let { cache.put(assetName, it) }
        return bitmap
    }
}

/** 字体选择：系统预设 + 内置/已导入字体。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FontSection(
    config: ReaderConfig,
    customFonts: List<ReadingFont>,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onImport: () -> Unit,
    onDelete: (ReadingFont) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingCard(title = "字体") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = 4
            ) {
                ReadingFonts.PRESETS.forEach { font ->
                    FontChoiceChip(
                        font = font,
                        selected = config.fontFamily == font.systemKey,
                        onSelect = { onChange { it.copy(fontFamily = font.systemKey ?: FontFamilyKey.SYSTEM) } },
                        onDelete = null
                    )
                }
            }

            CardDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "我的字体",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (customFonts.isEmpty()) {
                    Text(
                        text = "还没有导入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (customFonts.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(customFonts, key = { it.filePath ?: it.assetName ?: it.label }) { font ->
                        val selected = config.fontFamily == FontFamilyKey.CUSTOM &&
                            ((font.assetName != null && config.customFontAsset == font.assetName) ||
                                (font.filePath != null && config.customFontPath == font.filePath))
                        FontChoiceChip(
                            font = font,
                            selected = selected,
                            onSelect = {
                                onChange {
                                    it.copy(
                                        fontFamily = FontFamilyKey.CUSTOM,
                                        customFontAsset = font.assetName,
                                        customFontPath = font.filePath
                                    )
                                }
                            },
                            onDelete = if (font.filePath != null) ({ onDelete(font) }) else null
                        )
                    }
                }
            }
            MiradOutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Text("导入字体文件（.ttf / .otf / .ttc）")
            }
            if (config.fontFamily == FontFamilyKey.CUSTOM) {
                val currentFont = customFonts.firstOrNull { font ->
                    (font.assetName != null && font.assetName == config.customFontAsset) ||
                        (font.filePath != null && font.filePath == config.customFontPath)
                }
                Text(
                    text = "当前使用：" + (currentFont?.label ?: "自定义"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FontChoiceChip(
    font: ReadingFont,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(Modifier.widthIn(max = 240.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f, fill = false)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.heightIn(min = 40.dp)
                    .clip(MiradButtonShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MiradButtonShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = font.label,
                    style = MiradButtonTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "删除字体：${font.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 背景与主题：日间/夜间两组显式分区，各自完整编辑配色、背景素材与不透明度。 */
@Composable
fun BackgroundSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onPickImage: (dark: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val appearance = LocalAppAppearance.current
    val controller = LocalAppAppearanceController.current
    val effectiveDark = LocalAppDarkTheme.current
        ?: ThemePaletteResolver.resolve(config, isSystemInDarkTheme()).isDark
    var sectionDark by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(effectiveDark) }
    // 分区显示值：优先取外观记录里的目标组；隔离预览没有外观记录时，
    // 当前组退回 config 里的旧字段，另一组退回默认，保证任何入口都可渲染。
    val style = when {
        appearance != null -> appearance.style(sectionDark)
        sectionDark == effectiveDark -> ReadingStyle.from(config).normalized(sectionDark)
        sectionDark -> ReadingStyle.NIGHT_DEFAULT
        else -> ReadingStyle.DAY_DEFAULT
    }
    // 写入目标分区对应的样式组；无控制器（隔离预览）时退回旧的单组路径。
    val change: ((ReaderConfig) -> ReaderConfig) -> Unit = { transform ->
        if (controller != null) controller.updateReadingConfig(sectionDark, transform)
        else onChange(transform)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        SettingCard(title = if (sectionDark) "夜间配色" else "日间配色") {
            SegmentedRow(
                options = listOf(false to "日间样式", true to "夜间样式"),
                selected = sectionDark,
                onSelect = { dark -> sectionDark = dark }
            )
            // 目标组预览：底色块 + 文字色示例条（不带示例文字），所见即当前编辑组的实际观感。
            val previewPalette = ThemePaletteResolver.resolve(
                config.copy(
                    theme = style.theme,
                    backgroundType = style.backgroundType,
                    backgroundColor = style.backgroundColor,
                    backgroundImageUri = style.backgroundImageUri
                ), sectionDark
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(previewPalette.background))
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 12.dp)
                        .width(72.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(previewPalette.text))
                )
            }
            if (sectionDark != effectiveDark) {
                Text(
                    text = if (sectionDark) "切换到夜间模式后生效" else "切换到日间模式后生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ChoiceRow(
                label = "",
                options = readingThemeChoices(sectionDark),
                selected = style.theme,
                onSelect = { key -> change { it.copy(theme = key) } },
                columns = 3
            )

            if (style.theme == ThemeKey.CUSTOM) {
                ColorDotRow(
                    colors = readingCustomColors(sectionDark, style.backgroundColor),
                    selected = style.backgroundColor,
                    onSelect = { color -> change { it.copy(backgroundColor = color) } }
                )
            }
        }
        SettingCard(title = "背景素材") {
            SegmentedRow(
                options = listOf(BackgroundType.COLOR to "纯色背景", BackgroundType.IMAGE to "图片背景"),
                selected = style.backgroundType,
                onSelect = { key -> change { it.copy(backgroundType = key) } }
            )

            if (style.backgroundType == BackgroundType.IMAGE) {
                Text(
                    text = "内置背景",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(readingBackgroundChoices(sectionDark), key = { it.key }) { preset ->
                        BackgroundPresetTile(
                            preset = preset,
                            selected = style.backgroundImageUri == preset.uri,
                            onClick = {
                                change {
                                    it.copy(
                                        backgroundType = BackgroundType.IMAGE,
                                        backgroundImageUri = preset.uri
                                    )
                                }
                            }
                        )
                    }
                }
                MiradOutlinedButton(
                    onClick = { onPickImage(sectionDark) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择本机图片作为背景")
                }
                val current = style.backgroundImageUri
                if (!current.isNullOrBlank() && BuiltinBackgrounds.fromUri(current) == null) {
                    Text(
                        text = "当前：本机图片",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LabeledSlider(
                    label = "背景不透明度",
                    value = style.backgroundImageAlpha,
                    valueText = "${(style.backgroundImageAlpha * 100).toInt()}%",
                    range = 0.1f..1f,
                    onChange = { value -> change { it.copy(backgroundImageAlpha = value) } }
                )
            }
        }
    }
}

@Composable
private fun BackgroundPresetTile(
    preset: BuiltinBackground,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(preset.assetName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preset.assetName) {
        thumb = BackgroundThumbs.load(context, preset.assetName)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 58.dp, height = 96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(onClick = onClick)
        ) {
            val image = thumb
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = preset.label,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Text(
            text = preset.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}
