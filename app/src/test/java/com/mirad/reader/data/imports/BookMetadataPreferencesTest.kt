package com.mirad.reader.data.imports

import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMetadataPreferencesTest {
    private val old = BookEntity(id = 7, title = "手动书名", author = "手动作者", format = "TXT", filePath = "old.txt")

    @Test fun missingAndLegacyMarkersPreserveBothHistoricalFields() = runBlocking {
        for (raw in listOf(null, "t=0|a=0", "t=1|a=0", "t=0|a=1")) {
            val preferences = Preferences()
            if (raw != null) preferences.put(PreferenceEntity(BookCustomMetaStore.key(old.id), raw))
            val resolved = BookCustomMetaStore(preferences).resolve(old, "新版书名", "新版作者")
            assertEquals(raw, old.title, resolved.title)
            assertEquals(raw, old.author, resolved.author)
            assertTrue(resolved.keptCustomTitle)
            assertTrue(resolved.keptCustomAuthor)
        }
    }

    @Test fun initializingANewBookAllowsMetadataToFollowTheIncomingEdition() = runBlocking {
        val preferences = Preferences()
        val store = BookCustomMetaStore(preferences)
        store.initialize(old.id)
        assertEquals("v=2|t=0|a=0", preferences.get(BookCustomMetaStore.key(old.id)))
        val resolved = store.resolve(old, "新版书名", "新版作者")
        assertEquals("新版书名", resolved.title)
        assertEquals("新版作者", resolved.author)
        assertFalse(resolved.keptCustomTitle)
        assertFalse(resolved.keptCustomAuthor)
    }

    @Test fun editingOnlyOneKnownFieldKeepsTheOtherFieldUpdatable() = runBlocking {
        val preferences = Preferences()
        val store = BookCustomMetaStore(preferences)
        store.initialize(old.id)
        store.recordEdit(old, "新手动书名", old.author)
        val resolved = store.resolve(old.copy(title = "新手动书名"), "新版书名", "新版作者")
        assertEquals("新手动书名", resolved.title)
        assertEquals("新版作者", resolved.author)
        assertEquals("v=2|t=1|a=0", preferences.get(BookCustomMetaStore.key(old.id)))
    }

    @Test fun editingAnUnknownHistoricalRecordCannotUnlockTheUntouchedField() = runBlocking {
        val preferences = Preferences()
        val store = BookCustomMetaStore(preferences)
        store.recordEdit(old, "再次编辑书名", old.author)
        val resolved = store.resolve(old.copy(title = "再次编辑书名"), "新版书名", "新版作者")
        assertEquals("再次编辑书名", resolved.title)
        assertEquals(old.author, resolved.author)
        assertEquals("v=2|t=1|a=1", preferences.get(BookCustomMetaStore.key(old.id)))
    }

    private class Preferences : PreferenceDao {
        private val rows = linkedMapOf<String, PreferenceEntity>()
        override suspend fun put(preference: PreferenceEntity) { rows[preference.key] = preference }
        override suspend fun get(key: String): String? = rows[key]?.value
        override fun observe(key: String): Flow<String?> = MutableStateFlow(rows[key]?.value)
        override suspend fun remove(key: String) { rows.remove(key) }
        override suspend fun removeByPrefix(prefix: String) { rows.keys.removeAll { it.startsWith(prefix) } }
        override suspend fun getAll(): List<PreferenceEntity> = rows.values.toList()
    }
}
