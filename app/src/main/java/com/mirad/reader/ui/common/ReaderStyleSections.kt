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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
        SectionTitle("字体")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadingFonts.PRESETS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { font ->
                        ChoiceChip(
                            text = font.label,
                            selected = config.fontFamily == font.systemKey,
                            onClick = {
                                onChange { it.copy(fontFamily = font.systemKey ?: FontFamilyKey.SYSTEM) }
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "我的字体" + if (customFonts.isEmpty()) "（还没有，可导入 .ttf / .otf）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (customFonts.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(customFonts, key = { it.filePath ?: it.assetName ?: it.label }) { font ->
                    val selected = config.fontFamily == FontFamilyKey.CUSTOM &&
                        ((font.assetName != null && config.customFontAsset == font.assetName) ||
                            (font.filePath != null && config.customFontPath == font.filePath))
                    CustomFontChip(
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
            Spacer(modifier = Modifier.height(6.dp))
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("导入字体文件（.ttf / .otf / .ttc）")
        }
        if (config.fontFamily == FontFamilyKey.CUSTOM) {
            Text(
                text = "当前使用：" + (config.customFontAsset?.substringAfterLast('/')
                    ?: config.customFontPath?.substringAfterLast('/') ?: "未选择，已回退系统字体"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun CustomFontChip(
    font: ReadingFont,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = if (onDelete == null) 12.dp else 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = font.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
        if (onDelete != null) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "删除字体",
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp)
                    .clickable(onClick = onDelete)
            )
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
        SectionTitle("背景与主题")
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
        ChoiceRow(
            label = "背景类型",
            options = listOf(BackgroundType.COLOR to "纯色", BackgroundType.IMAGE to "图片"),
            selected = config.backgroundType,
            onSelect = { key -> onChange { it.copy(backgroundType = key) } },
            columns = 2
        )

        if (config.backgroundType == BackgroundType.IMAGE) {
            Text(
                text = "内置背景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                Text("选择本机图片作为背景")
            }
            val current = config.backgroundImageUri
            if (!current.isNullOrBlank() && BuiltinBackgrounds.fromUri(current) == null) {
                Text(
                    text = "当前：${current.substringAfterLast('/')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
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
