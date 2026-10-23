package com.mirad.reader.ui.common

import android.content.Context
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import com.mirad.reader.core.model.ThemeKey
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

/** 背景与主题：主题色、纯色、内置纹理、自定义图片、不透明度。 */
@Composable
fun BackgroundSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingCard(title = "背景与主题") {
            ChoiceRow(
                label = "主题",
                options = listOf(
                    ThemeKey.LIGHT to "白",
                    ThemeKey.PAPER to "纸色",
                    ThemeKey.SEPIA to "米黄",
                    ThemeKey.GREEN to "护眼",
                    ThemeKey.DARK to "夜间",
                    ThemeKey.BLACK to "纯黑",
                    ThemeKey.SYSTEM to "跟随系统"
                ),
                selected = config.theme,
                onSelect = { key -> onChange { it.copy(theme = key) } },
                columns = 4
            )

            CardDivider()

            SegmentedRow(
                options = listOf(BackgroundType.COLOR to "纯色背景", BackgroundType.IMAGE to "图片背景"),
                selected = config.backgroundType,
                onSelect = { key -> onChange { it.copy(backgroundType = key) } }
            )

            if (config.backgroundType == BackgroundType.IMAGE) {
                Text(
                    text = "内置背景",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(BuiltinBackgrounds.ALL, key = { it.key }) { preset ->
                        BackgroundPresetTile(
                            preset = preset,
                            selected = config.backgroundImageUri == preset.uri,
                            onClick = {
                                onChange {
                                    it.copy(
                                        backgroundType = BackgroundType.IMAGE,
                                        backgroundImageUri = preset.uri,
                                        theme = preset.suggestedTheme,
                                        backgroundImageAlpha = 1f
                                    )
                                }
                            }
                        )
                    }
                }
                MiradOutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                    Text("选择本机图片作为背景")
                }
                val current = config.backgroundImageUri
                if (!current.isNullOrBlank() && BuiltinBackgrounds.fromUri(current) == null) {
                    Text(
                        text = "当前：${current.substringAfterLast('/')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LabeledSlider(
                    label = "背景不透明度",
                    value = config.backgroundImageAlpha,
                    valueText = "${(config.backgroundImageAlpha * 100).toInt()}%",
                    range = 0.1f..1f,
                    onChange = { value -> onChange { it.copy(backgroundImageAlpha = value) } }
                )
            } else {
                ColorDotRow(
                    colors = listOf(
                        0xFFFFFFFF.toInt(), 0xFFF5F1E6.toInt(), 0xFFEDE0C8.toInt(),
                        0xFFCCE8CF.toInt(), 0xFFB8D8E8.toInt(), 0xFF1A1C1E.toInt(),
                        0xFF000000.toInt()
                    ),
                    selected = config.backgroundColor,
                    onSelect = { color ->
                        onChange { it.copy(backgroundColor = color, theme = ThemeKey.CUSTOM) }
                    }
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
