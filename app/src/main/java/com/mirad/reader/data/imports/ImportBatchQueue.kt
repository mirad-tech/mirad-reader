package com.mirad.reader.data.imports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** In-memory task/result ownership. Finished batches never contribute to a later summary. */
internal class ImportBatchQueue {
    data class Batch(val id: String, val itemIds: Set<String>)
    private val _state = MutableStateFlow(ImportState())
    val state = _state.asStateFlow()
    private val pendingResults = ArrayDeque<ImportSummary>()
    private val _summary = MutableStateFlow<ImportSummary?>(null)
    val summary = _summary.asStateFlow()

    @Synchronized
    fun enqueue(items: List<ImportItem>): Batch {
        val batch = Batch(UUID.randomUUID().toString(), items.map { it.id }.toSet())
        _state.value = ImportState(_state.value.items + items, running = true)
        return batch
    }

    @Synchronized
    fun update(id: String, transform: (ImportItem) -> ImportItem) {
        _state.value = _state.value.copy(items = _state.value.items.map { if (it.id == id) transform(it) else it })
    }

    /**
     * 把批次内仍未终态的条目收成失败，供取消/异常路径使用。
     *
     * 只改条目状态，不动 `running` 与出队：这两件事统一由随后的 [complete] 负责
     * （生产代码里 abort 与 complete 总是成对调用）。不调用它的后果是 [complete]
     * 的 check 会拒绝收敛，`running` 一直是 true，导入浮层永久停在"导入中"。
     */
    @Synchronized
    fun abort(batch: Batch, reason: String) {
        _state.value = _state.value.copy(
            items = _state.value.items.map { item ->
                if (item.id in batch.itemIds && item.stage !in TERMINAL) {
                    item.copy(stage = ImportStage.FAILED, progress = 1f, message = item.message ?: reason)
                } else {
                    item
                }
            }
        )
    }

    @Synchronized
    fun complete(batch: Batch): ImportSummary {
        val items = _state.value.items.filter { it.id in batch.itemIds }
        check(items.size == batch.itemIds.size && items.all { it.stage in TERMINAL }) {
            "Cannot complete a batch with unfinished items"
        }
        val result = ImportSummary(
            success = items.count { it.stage == ImportStage.DONE },
            failed = items.count { it.stage == ImportStage.FAILED },
            skipped = items.count { it.stage == ImportStage.SKIPPED },
            messages = items.filter { it.stage != ImportStage.DONE }
                .map { "${it.displayName}：${it.message.orEmpty()}" },
            batchId = batch.id,
            bookIds = items.filter { it.stage == ImportStage.DONE || it.stage == ImportStage.SKIPPED }
                .mapNotNull { it.bookId }.distinct()
        )
        val remaining = _state.value.items.filterNot { it.id in batch.itemIds }
        _state.value = ImportState(remaining, running = remaining.isNotEmpty())
        pendingResults.addLast(result)
        _summary.value = pendingResults.firstOrNull()
        return result
    }

    @Synchronized
    fun acknowledge(batchId: String?) {
        // A delayed dismiss callback must not consume the next batch's result.
        if (pendingResults.firstOrNull()?.batchId != batchId) return
        pendingResults.removeFirstOrNull()
        _summary.value = pendingResults.firstOrNull()
    }

    private companion object {
        val TERMINAL = setOf(ImportStage.DONE, ImportStage.SKIPPED, ImportStage.FAILED)
    }
}
