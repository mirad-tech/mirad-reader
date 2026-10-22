package com.mirad.reader.ui.shelf

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mirad.reader.ui.common.MiradDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.data.imports.DownloadProgress
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.launch

/**
 * 输入书源/网络网址导入书籍对话框。
 *
 * 支持：
 * 1. 电子书文件直链（.txt / .epub）；
 * 2. 小说或文章网页长文链接（智能提取正文与标题生成书籍）。
 */
@Composable
fun UrlImportDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlText by remember { mutableStateOf("") }
    val downloadProgress by container.networkDownloader.progress.collectAsState()

    // 弹窗打开时尝试自动读取剪贴板中的 http(s) 链接
    LaunchedEffect(Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (clip.startsWith("http://", ignoreCase = true) || clip.startsWith("https://", ignoreCase = true)) {
            urlText = clip
        }
    }

    val isBusy = downloadProgress is DownloadProgress.Connecting || downloadProgress is DownloadProgress.Downloading || downloadProgress is DownloadProgress.Success

    AlertDialog(
        onDismissRequest = {
            if (!isBusy) {
                container.networkDownloader.resetState()
                onDismiss()
            }
        },
        title = {
            Text(
                text = "输入网址导入书籍",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = urlText,
                    label = { Text("书籍网址") },
                    onValueChange = { urlText = it },
                    placeholder = { Text("https://example.com/book.txt") },
                    singleLine = false,
                    maxLines = 3,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                            if (clip.isNotEmpty()) urlText = clip
                        },
                        enabled = !isBusy
                    ) {
                        Text("粘贴剪贴板")
                    }
                    if (urlText.isNotEmpty() && !isBusy) {
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        TextButton(onClick = { urlText = "" }) {
                            Text("清空")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                // 下载状态与进度提示
                when (val p = downloadProgress) {
                    is DownloadProgress.Connecting -> {
                        Text(
                            text = "正在连接网络资源…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                    }
                    is DownloadProgress.Downloading -> {
                        val progressText = if (p.totalBytes > 0) {
                            "${(p.percent * 100).toInt()}% (${p.bytesDownloaded / 1024} KB / ${p.totalBytes / 1024} KB)"
                        } else {
                            "已下载 ${p.bytesDownloaded / 1024} KB"
                        }
                        Text(
                            text = "正在下载：$progressText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        LinearProgressIndicator(
                            progress = { if (p.totalBytes > 0) p.percent else 0f },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp))
                        )
                    }
                    is DownloadProgress.Error -> {
                        com.mirad.reader.ui.common.ErrorDetails(p.message)
                    }
                    is DownloadProgress.Success -> {
                        Text(
                            text = "已完成下载，正在导入《${p.title}》…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    DownloadProgress.Idle -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (urlText.isNotBlank()) {
                        scope.launch {
                            val success = container.networkDownloader.downloadAndImport(urlText)
                            if (success) {
                                val state = container.networkDownloader.progress.value
                                val bookTitle = if (state is DownloadProgress.Success) state.title else "书籍"
                                container.networkDownloader.resetState()
                                onSuccess(bookTitle)
                                onDismiss()
                            }
                        }
                    }
                },
                enabled = !isBusy && urlText.isNotBlank()
            ) {
                Text(if (isBusy) "下载中…" else "开始导入")
            }
        },
        dismissButton = {
            if (!isBusy) {
                TextButton(
                    onClick = {
                        container.networkDownloader.resetState()
                        onDismiss()
                    }
                ) {
                    Text("取消")
                }
            }
        }
    )
}
