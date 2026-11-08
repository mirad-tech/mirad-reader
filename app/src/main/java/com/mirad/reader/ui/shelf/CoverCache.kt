package com.mirad.reader.ui.shelf

import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirad.reader.ui.design.LocalMiradColors
import com.mirad.reader.ui.design.Sizes
import com.mirad.reader.ui.theme.MiradSerif
import com.mirad.reader.data.db.entity.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 封面位图缓存（按路径，LRU）。 */
object CoverCache {
    private val cache = LruCache<String, ImageBitmap>(48)

    suspend fun load(path: String, targetWidth: Int = 360): ImageBitmap? {
        cache.get(path)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > targetWidth) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
        bitmap?.let { cache.put(path, it) }
        return bitmap
    }

    fun clear() = cache.evictAll()
}

/** 真实封面优先；缺图时用稳定纸墨色与完整书名，小尺寸保留简化题签。 */
@Composable
fun BookCover(
    book: BookEntity,
    modifier: Modifier = Modifier,
    titleSize: Int = 19,
    titleTopInset: Dp = 0.dp
) {
    var bitmap by remember(book.id, book.coverPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(book.id, book.coverPath) {
        bitmap = book.coverPath?.let { CoverCache.load(it) }
    }
    val shape = RoundedCornerShape(Sizes.coverCorner)
    val tones = LocalMiradColors.current.coverTones
    val tone = tones[Math.floorMod(book.id, tones.size.toLong()).toInt()]
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(tone.container)
            .border(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f), shape),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val compact = maxWidth < 64.dp
            val fontSize = if (compact) minOf(titleSize, 12) else titleSize
            val density = LocalDensity.current
            Box(
                Modifier.fillMaxSize()
                    .padding(if (compact) 3.dp else 6.dp)
                    .border(1.dp, tone.onContainer.copy(alpha = .34f), RoundedCornerShape(4.dp))
            )
            // 题签属于固定尺寸的封面图，卡片外的书名和进度使用系统大字。
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (compact) 6.dp else 12.dp)
                        .padding(
                            top = if (compact) 6.dp else maxOf(12.dp, titleTopInset),
                            bottom = if (compact) 6.dp else 12.dp
                        )
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = MiradSerif, fontWeight = FontWeight.Normal,
                                fontSize = fontSize.sp, lineHeight = (fontSize * 1.4f).sp
                            ),
                            color = tone.onContainer,
                            textAlign = TextAlign.Center,
                            maxLines = if (compact) 2 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!compact && !book.author.isNullOrBlank()) {
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = tone.onContainer.copy(alpha = .85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
