package com.mirad.reader.data.imports

import android.net.Uri
import java.io.File

/** 导入来源。 */
sealed interface ImportSource {
    val displayName: String

    data class FromUri(val uri: Uri, override val displayName: String) : ImportSource

    data class FromFile(
        val file: File,
        val originUrl: String? = null,
        val downloadTaskId: String? = null
    ) : ImportSource {
        override val displayName: String get() = file.name
    }
}

enum class ImportStage {
    PENDING,
    COPYING,
    PARSING,
    INDEXING,
    DONE,
    SKIPPED,
    FAILED
}

data class ImportItem(
    val id: String,
    val displayName: String,
    val stage: ImportStage = ImportStage.PENDING,
    val progress: Float = 0f,
    val message: String? = null,
    val bookId: Long? = null
)

data class ImportState(
    val items: List<ImportItem> = emptyList(),
    val running: Boolean = false
) {
    val visible: List<ImportItem> get() = items.filter { it.stage != ImportStage.PENDING }
    val finished: Boolean get() = !running && items.isNotEmpty()
    val successCount: Int get() = items.count { it.stage == ImportStage.DONE }
    val failedCount: Int get() = items.count { it.stage == ImportStage.FAILED }
    val skippedCount: Int get() = items.count { it.stage == ImportStage.SKIPPED }
    val currentName: String? get() = items.lastOrNull { it.stage in setOf(ImportStage.COPYING, ImportStage.PARSING, ImportStage.INDEXING) }?.displayName
}

/** 导入结果汇总，用于一次性提示。 */
data class ImportSummary(
    val success: Int,
    val failed: Int,
    val skipped: Int,
    val messages: List<String>,
    val batchId: String = "",
    val bookIds: List<Long> = emptyList()
)
