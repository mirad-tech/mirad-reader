package com.mirad.reader.ui.shelf

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.ui.common.glassBackdropSource
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.RowDivider
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.design.Sizes
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.launch

/**
 * 隐藏的书籍：把书架上「隐藏」过的书集中在这里，可以恢复或删除。
 * （之前只有隐藏入口没有恢复入口，是个死路。）
 */
@Composable
fun HiddenBooksScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val feedback = com.mirad.reader.ui.common.rememberFeedbackState("hidden")
    var deleteTarget by remember { mutableStateOf<com.mirad.reader.data.db.entity.BookEntity?>(null) }
    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    val hidden = shelf.filter { it.book.isHidden }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { com.mirad.reader.ui.common.FeedbackHost(feedback) },
        topBar = {
            ScreenHeader(
                title = "隐藏的书籍",
                subtitle = if (hidden.isEmpty()) null else "共 ${hidden.size} 本",
                onBack = onBack
            )
        }
    ) { padding ->
        if (hidden.isEmpty()) {
            EmptyState(
                title = "没有被隐藏的书",
                badge = "空",
                description = "在书架上长按一本书，选择「在书架隐藏」后会出现在这里",
                modifier = Modifier
                    .fillMaxSize()
                    .glassBackdropSource()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .glassBackdropSource(),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                    top = padding.calculateTopPadding() + Spacing.sm,
                    bottom = padding.calculateBottomPadding() + Spacing.sm
                )
            ) {
                items(hidden, key = { it.book.id }) { item ->
                    ListRow(
                        title = item.book.title,
                        subtitle = "${item.book.author ?: "未知作者"} · ${item.book.totalChapters} 章",
                        leading = {
                            BookCover(
                                book = item.book,
                                modifier = Modifier.size(Sizes.coverSmallWidth, Sizes.coverSmallHeight)
                            )
                        },
                        trailing = {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                MiradTextButton(onClick = {
                                    scope.launch {
                                        runCatching { container.books.setHidden(item.book.id, false) }
                                            .onSuccess { feedback.show("已恢复到书架") }
                                            .onFailure { feedback.show("恢复失败，请重试", com.mirad.reader.ui.common.FeedbackKind.ERROR) }
                                    }
                                }) { Text("恢复") }
                                MiradTextButton(danger = true, onClick = {
                                    deleteTarget = item.book
                                }) { Text("删除") }
                            }
                        }
                    )
                    RowDivider(startIndent = Sizes.coverSmallWidth + Spacing.lg)
                }
            }
        }
    }
    deleteTarget?.let { book ->
        com.mirad.reader.ui.common.MiradDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除《${book.title}》") },
            text = { Text("书籍及其阅读记录、书签与笔记将被删除，无法恢复。") },
            confirmButton = { MiradTextButton(danger = true, onClick = {
                scope.launch {
                    runCatching { container.books.deleteBook(book) }
                        .onSuccess { deleteTarget = null; feedback.show("已删除书籍") }
                        .onFailure { feedback.show("删除失败，请重试", com.mirad.reader.ui.common.FeedbackKind.ERROR); deleteTarget = null }
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { MiradTextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

}
