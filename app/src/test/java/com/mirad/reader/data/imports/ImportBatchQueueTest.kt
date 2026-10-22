package com.mirad.reader.data.imports

import org.junit.Assert.*
import org.junit.Test

class ImportBatchQueueTest {
    @Test fun isolatesBatchesAndKeepsUnreadResultsInOrder() {
        val queue = ImportBatchQueue()
        val first = queue.enqueue(listOf(ImportItem("a", "第一本"), ImportItem("b", "损坏文件")))
        val second = queue.enqueue(listOf(ImportItem("c", "重复书")))
        queue.update("a") { it.copy(stage = ImportStage.DONE) }
        queue.update("b") { it.copy(stage = ImportStage.FAILED, message = "无法解析") }
        val one = queue.complete(first)
        assertEquals(1, one.success)
        assertEquals(1, one.failed)
        assertTrue(queue.state.value.running)
        assertEquals(listOf("c"), queue.state.value.items.map { it.id })
        queue.update("c") { it.copy(stage = ImportStage.SKIPPED, message = "已存在") }
        val two = queue.complete(second)
        assertEquals(0, two.success)
        assertEquals(0, two.failed)
        assertEquals(1, two.skipped)
        assertEquals(listOf("重复书：已存在"), two.messages)
        assertFalse(queue.state.value.running)
        assertEquals(one, queue.summary.value)
        queue.acknowledge(one.batchId)
        assertEquals(two, queue.summary.value)
        queue.acknowledge(one.batchId)
        assertEquals(two, queue.summary.value)
        queue.acknowledge(two.batchId)
        assertNull(queue.summary.value)
    }

    @Test fun allSkippedBatchGeneratesZeroSuccessAndZeroFailed() {
        val queue = ImportBatchQueue()
        val batch = queue.enqueue(listOf(ImportItem("s1", "已存在的书1"), ImportItem("s2", "已存在的书2")))
        queue.update("s1") { it.copy(stage = ImportStage.SKIPPED, message = "书架已存在") }
        queue.update("s2") { it.copy(stage = ImportStage.SKIPPED, message = "书架已存在") }
        val summary = queue.complete(batch)
        assertEquals(0, summary.success)
        assertEquals(0, summary.failed)
        assertEquals(2, summary.skipped)
        assertEquals(2, summary.messages.size)
        assertFalse(queue.state.value.running)
    }

    @Test fun allFailedBatchGeneratesCorrectErrorMessages() {
        val queue = ImportBatchQueue()
        val batch = queue.enqueue(listOf(ImportItem("f1", "坏书")))
        queue.update("f1") { it.copy(stage = ImportStage.FAILED, message = "文件已截断") }
        val summary = queue.complete(batch)
        assertEquals(0, summary.success)
        assertEquals(1, summary.failed)
        assertEquals(0, summary.skipped)
        assertEquals(listOf("坏书：文件已截断"), summary.messages)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotSummarizePendingTasksAsCompleted() {
        val queue = ImportBatchQueue()
        queue.complete(queue.enqueue(listOf(ImportItem("a", "等待中的书"))))
    }
}
