package com.mirad.reader.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAnchorTest {

    private val chapters = listOf(680, 1200, 300)

    @Test
    fun insideChapterIsValid() {
        assertFalse(NoteAnchor.isStale(0, 0, chapters))
        assertFalse(NoteAnchor.isStale(1, 500, chapters))
        assertFalse(NoteAnchor.isStale(2, 299, chapters))
    }

    @Test
    fun offsetExactlyAtChapterEndIsStillValid() {
        assertFalse(NoteAnchor.isStale(0, 680, chapters))
    }

    @Test
    fun missingChapterIsStale() {
        // 重新分章后整章消失：chapterIndex 越界
        assertTrue(NoteAnchor.isStale(3, 0, chapters))
        assertTrue(NoteAnchor.isStale(99, 0, chapters))
    }

    @Test
    fun offsetBeyondChapterLengthIsStale() {
        assertTrue(NoteAnchor.isStale(0, 681, chapters))
        assertTrue(NoteAnchor.isStale(2, 301, chapters))
    }

    @Test
    fun negativeOffsetIsStale() {
        assertTrue(NoteAnchor.isStale(0, -1, chapters))
    }

    @Test
    fun emptyChapterListMakesEverythingStale() {
        assertTrue(NoteAnchor.isStale(0, 0, emptyList()))
    }
}
