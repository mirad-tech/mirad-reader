package com.mirad.reader.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部打开/分享的文件 URI 提取（计划 §4）。
 * 只证明 URI 提取与路由，不代替跨应用验收。
 */
class ExternalImportUrisTest {

    private val contentUri = "content://com.example.docs/document/123"
    private val secondUri = "content://com.example.docs/document/456"
    private val fileUri = "file:///sdcard/Download/book.txt"

    @Test
    fun viewActionUsesDataUri() {
        val got = ExternalImportUris.collect(ExternalImportUris.ACTION_VIEW, contentUri, emptyList(), emptyList(), null)
        assertEquals(listOf(contentUri), got)
    }

    @Test
    fun sendActionUsesStreamAndClipData() {
        val got = ExternalImportUris.collect(
            ExternalImportUris.ACTION_SEND, null, listOf(secondUri), listOf(fileUri), null
        )
        assertEquals(listOf(fileUri, secondUri), got)
    }

    @Test
    fun sendActionDeduplicatesSameUri() {
        val got = ExternalImportUris.collect(
            ExternalImportUris.ACTION_SEND, null, listOf(contentUri), listOf(contentUri), null
        )
        assertEquals(listOf(contentUri), got)
    }

    @Test
    fun plainTextShareIsNotTreatedAsFile() {
        val got = ExternalImportUris.collect(
            ExternalImportUris.ACTION_SEND, null, emptyList(), emptyList(), "一段普通文字"
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun unrelatedActionAndNullDataAreIgnored() {
        assertTrue(
            ExternalImportUris.collect("android.intent.action.MAIN", contentUri, emptyList(), emptyList(), null).isEmpty()
        )
        assertTrue(
            ExternalImportUris.collect(ExternalImportUris.ACTION_VIEW, null, emptyList(), emptyList(), null).isEmpty()
        )
    }

    @Test
    fun nonFileSchemesAreFilteredOut() {
        val got = ExternalImportUris.collect(
            ExternalImportUris.ACTION_SEND,
            null,
            listOf("https://example.com/a.txt"),
            listOf("content://com.example/x"),
            null
        )
        assertEquals(listOf("content://com.example/x"), got)
    }

    @Test
    fun readGrantFlagIsDetected() {
        assertTrue(ExternalImportUris.carriesReadGrant(ExternalImportUris.FLAG_GRANT_READ_URI_PERMISSION))
        assertFalse(ExternalImportUris.carriesReadGrant(0))
        // 其它 flag 与读授权组合时仍应识别
        assertTrue(
            ExternalImportUris.carriesReadGrant(
                ExternalImportUris.FLAG_GRANT_READ_URI_PERMISSION or 0x00000002
            )
        )
    }

    @Test
    fun prioritizeKeepsOrderAndDropsDuplicates() {
        val got = ExternalImportUris.prioritize(listOf(contentUri, "", secondUri, contentUri))
        assertEquals(listOf(contentUri, secondUri), got)
        assertTrue(ExternalImportUris.prioritize(emptyList()).isEmpty())
    }

    @Test
    fun sendKeepsStreamBeforeClipOrder() {
        // 回归保护：先 EXTRA_STREAM 后 ClipData 的顺序不能反
        val got = ExternalImportUris.collect(
            ExternalImportUris.ACTION_SEND, null, listOf(secondUri), listOf(contentUri), null
        )
        assertEquals(listOf(contentUri, secondUri), got)
    }
}
