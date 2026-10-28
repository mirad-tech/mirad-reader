package com.mirad.reader.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 两个笔记入口共用文档选择与写出；取消选择时不提示成功，空输出流按失败处理。 */
@Composable
internal fun rememberMarkdownExporter(onResult: (Boolean) -> Unit): (String, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val content = pending
        pending = null
        if (uri != null && content != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val out = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
                    out.use { it.write(content.toByteArray()) }
                    true
                }.getOrDefault(false)
            }
            onResult(ok)
        }
    }
    return { name, content -> pending = content; launcher.launch(name) }
}
