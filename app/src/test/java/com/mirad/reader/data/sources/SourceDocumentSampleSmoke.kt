package com.mirad.reader.data.sources

import kotlinx.coroutines.*
import java.io.File

/** 显式手工入口：只对本地完整规则样本导入并输出计数，不抓取链接、不打印规则内容。 */
object SourceDocumentSampleSmoke {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "需要样本文件和空的本地验证目录" }
        val source = File(args[0])
        val root = File(args[1])
        require(!root.exists() || root.listFiles().isNullOrEmpty()) { "验证目录必须为空" }
        val text = source.inputStream().use { readSourceDocument(it) }
        val payload = parseSourceImport(text)
        require(payload is SourceImportPayload.Definitions) { "该手工入口只接受本地完整规则，禁止链接清单联网" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = BookSourceRepository(root, SourceHttpClient(), scope)
            val report = repository.importText(text)
            println("entries=${payload.entries.size} added=${report.added} updated=${report.updated} invalid=${report.invalid} unsupported=${report.unsupported} stored=${repository.sources.value.size}")
        } finally { scope.cancel() }
    }
}
