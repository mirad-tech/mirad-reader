package com.mirad.reader.ui.shelf

import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
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
fun BookCover(book: BookEntity, modifier: Modifier = Modifier, titleSize: Int = 22) {
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
            val fontSize = if (compact) minOf(titleSize, 14) else titleSize
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(start = 5.dp).width(1.dp)
                .background(tone.onContainer.copy(alpha = .18f)))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = if (compact) 10.dp else 16.dp,
                        end = if (compact) 6.dp else 12.dp,
                        top = if (compact) 6.dp else 16.dp,
                        bottom = if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = MiradSerif, fontWeight = FontWeight.Normal,
                        fontSize = fontSize.sp, lineHeight = (fontSize * 1.45f).sp),
                    color = tone.onContainer,
                    maxLines = if (compact) 3 else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                )
                if (!compact && !book.author.isNullOrBlank()) Text(
                    text = book.author, style = MaterialTheme.typography.labelSmall,
                    color = tone.onContainer.copy(alpha = .85f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
