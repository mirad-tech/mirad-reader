package com.mirad.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
        val loaded = container.books.getBook(bookId)
        if (loaded == null) {
            error = "书籍不存在"
            return@LaunchedEffect
        }
        val appearance = container.settings.currentAppAppearance()
        val config = appearance.resolveReaderConfig(container.settings.currentGlobal(), latestSystemDark)
        val created = ReaderSession(container, loaded, scope)
        // prepare 失败也要释放：会话在 init 里注册了全局音量键 handler，不释放会让
        // ReaderKeys.pager 指向一个已经废弃的会话，同时 resources/zip 句柄一直挂着。
        // release() 不会写坏进度：ReaderSession 用 prepared 标记挡住了半初始化状态的写库。
        val prepared = runCatching { created.prepare(config, appearance.effectiveDark(latestSystemDark)) }
        if (prepared.isFailure) {
            created.release()
            error = prepared.exceptionOrNull()?.message ?: "打开失败"
            return@LaunchedEffect
        }
        book = loaded
        session = created
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

    DisposableEffect(current) {
        onDispose { current.release(); com.mirad.reader.reader.ReaderKeys.pager = null }
    }

    ReaderContent(
        container = container,
        session = current,
        book = currentBook,
        systemDark = systemDark,
        onBack = onBack
    )
}
