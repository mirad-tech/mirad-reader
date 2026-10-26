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
import androidx.compose.foundation.layout.fillMaxSize
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

    /**
     * 预览用的背景图：内置素材走 assets，本机图片走文件路径（选择时已复制进 backgroundsDir）。
     * 结果进 LruCache，避免每次重组或滑动都重新解码。
     */
    suspend fun loadUri(context: Context, uri: String?): ImageBitmap? {
        if (uri.isNullOrBlank()) return null
        BuiltinBackgrounds.fromUri(uri)?.let { return load(context, it.assetName) }
        cache.get("file:$uri")?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(uri, bounds)
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 320) sample *= 2
                BitmapFactory.decodeFile(
                    uri,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )?.asImageBitmap()
            }.getOrNull()
        }
        bitmap?.let { cache.put("file:$uri", it) }
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
            // 1) 先选要编辑的样式组
            SegmentedRow(
                options = listOf(false to "日间样式", true to "夜间样式"),
                selected = sectionDark,
                onSelect = { dark -> sectionDark = dark }
            )
            // 2) 目标组预览：与阅读画面同构（图片 + 不透明度 + 衬底色 + 文字色）
            BackgroundPreview(style = style, dark = sectionDark)
            if (sectionDark != effectiveDark) {
                Text(
                    text = if (sectionDark) "切换到夜间模式后生效" else "切换到日间模式后生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CardDivider()
            // 3) 背景来源二选一；下方只展示当前来源的设置，避免两个分支同时表现成"正在生效"
            SegmentedRow(
                options = listOf(BackgroundType.COLOR to "纯色背景", BackgroundType.IMAGE to "图片背景"),
                selected = style.backgroundType,
                onSelect = { key -> change { it.copy(backgroundType = key) } }
            )
            if (style.backgroundType == BackgroundType.IMAGE) {
                ImageBackgroundControls(style, sectionDark, change, onPickImage)
            } else {
                ColorBackgroundControls(style, sectionDark, change)
            }
        }
    }
}

/**
 * 目标样式组的真实预览：底色 + 图片（按不透明度）+ 衬底蒙层 + 文字色。
 *
 * 旧实现只画一条纯色色块，选了图片背景后预览仍是纯色，用户无法判断当前到底是什么效果。
 * 这里与 `ReaderCss` 的合成方式保持一致：图片铺满，衬底色以 `1 - 不透明度` 覆盖其上。
 */
@Composable
private fun BackgroundPreview(style: ReadingStyle, dark: Boolean) {
    val context = LocalContext.current
    val palette = ThemePaletteResolver.resolve(
        ReaderConfig.DEFAULT.copy(
            theme = style.theme,
            backgroundType = style.backgroundType,
            backgroundColor = style.backgroundColor,
            backgroundImageUri = style.backgroundImageUri,
            backgroundImageAlpha = style.backgroundImageAlpha
        ),
        dark
    )
    var image by remember(style.backgroundImageUri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(style.backgroundImageUri) {
        image = BackgroundThumbs.loadUri(context, style.backgroundImageUri)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(palette.background))
    ) {
        val bitmap = image
        if (style.backgroundType == BackgroundType.IMAGE && bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "当前背景预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // 与 ReaderCss 的 #mirad-tint 同口径：不透明度越低，衬底色盖得越多。
            val overlay = 1f - style.backgroundImageAlpha.coerceIn(0f, 1f)
            if (overlay > 0f) {
                Box(Modifier.fillMaxSize().background(Color(palette.background).copy(alpha = overlay)))
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 12.dp)
                .width(72.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(palette.text))
        )
    }
}

/** 纯色背景分支：配色预设 + 自定义颜色。选中即表示"当前背景是纯色"。 */
@Composable
private fun ColorBackgroundControls(
    style: ReadingStyle,
    sectionDark: Boolean,
    change: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
    ChoiceRow(
        label = "配色预设",
        options = readingThemeChoices(sectionDark),
        selected = style.theme,
        onSelect = { key ->
            change { it.copy(theme = key, backgroundType = BackgroundType.COLOR) }
        },
        columns = 3
    )
    if (style.theme == ThemeKey.CUSTOM) {
        ColorDotRow(
            colors = readingCustomColors(sectionDark, style.backgroundColor),
            selected = style.backgroundColor,
            onSelect = { color ->
                change { it.copy(backgroundColor = color, backgroundType = BackgroundType.COLOR) }
            }
        )
    }
}

/** 图片背景分支：内置/本机图片、不透明度；衬底配色只在图片未铺满时按需展开。 */
@Composable
private fun ImageBackgroundControls(
    style: ReadingStyle,
    sectionDark: Boolean,
    change: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onPickImage: (dark: Boolean) -> Unit
) {
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
    if (style.backgroundImageAlpha < 1f) {
        var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        MiradTextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (expanded) "收起衬底配色" else "衬底配色")
        }
        if (expanded) {
            Text(
                text = "图片不透明度低于 100% 时，衬底色与文字色按这里的选择；不会切回纯色背景。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    } else {
        Text(
            text = "不透明度 100% 时底色不可见，衬底配色已隐藏。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
