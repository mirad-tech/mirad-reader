package com.mirad.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.reader.ReaderSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(
    container: AppContainer,
    bookId: Long,
    systemDark: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val latestSystemDark by rememberUpdatedState(systemDark)
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        container.books.contentLock(bookId).withLock {
            val loaded = container.books.getBook(bookId)
            if (loaded == null) {
                error = "书籍不存在"
                return@withLock
            }
            val appearance = container.settings.currentAppAppearance()
            val config = appearance.resolveReaderConfig(container.settings.currentGlobal(), latestSystemDark)
            val created = ReaderSession(container, loaded, scope)
            try {
                created.prepare(config, appearance.effectiveDark(latestSystemDark))
                book = loaded
                session = created
                awaitCancellation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "打开失败"
            } finally {
                val lastWrite = created.release()
                withContext(NonCancellable) { lastWrite?.join() }
            }
        }
    }

    val current = session
    val currentBook = book
    if (error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!)
                MiradTextButton(onClick = onBack) { Text("返回书架") }
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
