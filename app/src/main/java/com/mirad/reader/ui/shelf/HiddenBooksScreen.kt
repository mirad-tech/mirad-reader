package com.mirad.reader.ui.shelf

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
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
    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    val hidden = shelf.filter { it.book.isHidden }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = Spacing.sm)
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
                                TextButton(onClick = {
                                    scope.launch { container.books.setHidden(item.book.id, false) }
                                }) { Text("恢复") }
                                TextButton(onClick = {
                                    scope.launch { container.books.deleteBook(item.book) }
                                }) { Text("删除") }
                            }
                        }
                    )
                    RowDivider(startIndent = Sizes.coverSmallWidth + Spacing.lg)
                }
            }
        }
    }
}
