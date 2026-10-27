package com.mirad.reader.ui.shelf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import com.mirad.reader.ui.common.BouncingLazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.repo.BookMaintenance
import com.mirad.reader.ui.common.SheetScaffold
import com.mirad.reader.ui.common.MiradLinearProgress
import com.mirad.reader.ui.common.MiradTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChapterPreviewSheet(
    container: AppContainer,
    book: BookEntity,
    onDismiss: () -> Unit,
    onApplied: (Int) -> Unit
) {
    var preview by remember(book.id) { mutableStateOf<BookMaintenance.ChapterPreview?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(book.id, attempt) {
        loading = true
        error = null
        preview = null
        try {
            preview = container.maintenance.previewChapters(book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "目录识别失败，请重试"
        } finally {
            loading = false
        }
    }
    BackHandler(enabled = applying) { }
    SheetScaffold(
        title = "重新识别《${book.title}》章节",
        onDismiss = { if (!applying) onDismiss() },
        fillHeight = true,
        actions = {
            MiradTextButton(
                enabled = preview != null && !loading && !applying,
                loading = applying,
                onClick = {
                    val candidate = preview ?: return@MiradTextButton
                    applying = true
                    scope.launch {
                        try {
                            val count = container.maintenance.applyChapters(book, candidate)
                            onApplied(count)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "目录更新失败，原目录已保留"
                            preview = null
                        } finally {
                            applying = false
                        }
                    }
                }
            ) { Text("应用") }
        }
    ) {
        if (loading) {
            MiradLinearProgress(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            Text("正在识别章节…", modifier = Modifier.padding(20.dp))
        }
        error?.let {
            Box(Modifier.padding(horizontal = 20.dp)) {
                com.mirad.reader.ui.common.ErrorDetails(it)
            }
            MiradTextButton(onClick = { attempt++ }, enabled = !applying) { Text("重新预览") }
        }
        preview?.let { candidate ->
            Text(
                "${candidate.slices.size} 节 · ${if (candidate.automatic) "按篇幅分节" else "已识别章节"}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            BouncingLazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(candidate.slices) { index, chapter ->
                    ListItem(
                        headlineContent = { Text(chapter.title) },
                        supportingContent = { Text("${chapter.charCount} 字") },
                        leadingContent = { Text("${index + 1}") }
                    )
                }
            }
        }
    }
}
