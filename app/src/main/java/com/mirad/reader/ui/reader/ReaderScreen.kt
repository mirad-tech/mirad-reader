package com.mirad.reader.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.io.MissingBookSourceException
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.ui.common.FeedbackKind
import com.mirad.reader.ui.common.MiradTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(
    container: AppContainer,
    bookId: Long,
    systemDark: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestSystemDark by rememberUpdatedState(systemDark)
    var book by remember(bookId) { mutableStateOf<BookEntity?>(null) }
    var session by remember(bookId) { mutableStateOf<ReaderSession?>(null) }
    var error by remember(bookId) { mutableStateOf<String?>(null) }
    var missingSource by remember(bookId) { mutableStateOf(false) }
    var reloadKey by remember(bookId) { mutableIntStateOf(0) }
    var relinking by remember(bookId) { mutableStateOf(false) }

    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        relinking = true
        scope.launch {
            try {
                BookContentAccess.tryTakePersistableReadPermission(context, uri)
                val uriText = uri.toString()
                container.maintenance.relinkSource(bookId, uriText, uriText)
                error = null
                missingSource = false
                reloadKey++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.feedback.show(e.message ?: "无法关联所选文件", FeedbackKind.ERROR)
            } finally {
                relinking = false
            }
        }
    }

    LaunchedEffect(bookId, reloadKey) {
        session = null
        error = null
        missingSource = false
        container.books.contentLock(bookId).withLock {
            val loaded = container.books.getBook(bookId)
            if (loaded == null) {
                error = "书籍不存在"
                return@withLock
            }
            book = loaded
            val readable = withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.textProvider.isBookSourceReadable(loaded)
            }
            if (!readable) {
                missingSource = true
                error = "原文件已移动或无法读取"
                return@withLock
            }
            val appearance = container.settings.currentAppAppearance()
            val config = appearance.resolveReaderConfig(container.settings.currentGlobal(), latestSystemDark)
            val created = ReaderSession(container, loaded, scope)
            try {
                created.prepare(config, appearance.effectiveDark(latestSystemDark))
                session = created
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingBookSourceException) {
                missingSource = true
                error = e.message ?: "原文件已移动或无法读取"
            } catch (e: Exception) {
                error = e.message ?: "打开失败"
            } finally {
                val writes = created.release()
                withContext(NonCancellable) { writes.joinAll() }
            }
        }
    }

    val current = session
    val currentBook = book
    if (error != null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(error!!, style = MaterialTheme.typography.titleMedium)
                if (missingSource) {
                    Text(
                        text = "阅读进度与笔记已保留，可重新选择原文件恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiradTextButton(
                            enabled = !relinking,
                            onClick = {
                                val mime = if (currentBook?.format == BookFormat.EPUB) {
                                    arrayOf("application/epub+zip", "application/octet-stream", "*/*")
                                } else {
                                    arrayOf("text/plain", "*/*")
                                }
                                relinkPicker.launch(mime)
                            }
                        ) {
                            Text(if (relinking) "正在关联…" else "重新关联文件")
                        }
                        MiradTextButton(onClick = onBack) { Text("返回书架") }
                    }
                } else {
                    MiradTextButton(onClick = onBack) { Text("返回书架") }
                }
            }
        }
        return
    }
    if (current == null || currentBook == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    ReaderContent(
        container = container,
        session = current,
        book = currentBook,
        systemDark = systemDark,
        onBack = onBack
    )
}
