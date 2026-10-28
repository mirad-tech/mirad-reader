package com.mirad.reader.data.imports

import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadImportReceiptsTest {
    private class Preferences : PreferenceDao {
        val entries = mutableMapOf<String, PreferenceEntity>()
        override suspend fun put(preference: PreferenceEntity) { entries[preference.key] = preference }
        override suspend fun get(key: String) = entries[key]?.value
        override fun observe(key: String) = MutableStateFlow(entries[key]?.value)
        override suspend fun remove(key: String) { entries.remove(key) }
        override suspend fun removeByPrefix(prefix: String) { entries.keys.removeAll { it.startsWith(prefix) } }
        override suspend fun getAll() = entries.values.toList()
    }

    @Test fun restoredReceiptFindsTheAlreadyCommittedBook() = runBlocking {
        val preferences = Preferences()
        DownloadImportReceipts(preferences) { it == 42L }.record("task", 42)
        val restored = DownloadImportReceipts(preferences) { it == 42L }
        assertEquals(42L, restored.find("task"))
        assertNull(restored.find("other"))
    }

    @Test fun removedBookDoesNotPermanentlyBlockAnotherDownload() = runBlocking {
        val preferences = Preferences()
        DownloadImportReceipts(preferences) { true }.record("task", 42)
        assertNull(DownloadImportReceipts(preferences) { false }.find("task"))
        assertTrue(preferences.entries.isEmpty())
    }
}
