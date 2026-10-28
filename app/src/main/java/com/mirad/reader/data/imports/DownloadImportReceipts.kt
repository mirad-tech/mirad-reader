package com.mirad.reader.data.imports

import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity

/** 调用方在书籍入库的同一事务内写凭据，文件任务清单可随后安全补写。 */
class DownloadImportReceipts(
    private val preferences: PreferenceDao,
    private val bookExists: suspend (Long) -> Boolean
) {
    suspend fun find(taskId: String): Long? {
        val key = key(taskId)
        val stored = preferences.get(key) ?: return null
        val id = stored.toLongOrNull()?.takeIf { it > 0 }
        if (id != null && bookExists(id)) return id
        preferences.remove(key)
        return null
    }

    suspend fun record(taskId: String, bookId: Long) {
        preferences.put(PreferenceEntity(key(taskId), bookId.toString(), System.currentTimeMillis()))
    }

    private fun key(taskId: String) = "book_download_receipt_v1_$taskId"
}
