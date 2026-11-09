package com.mirad.reader.data.imports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** 已扫描的批次可继续处理待确认项，结果只归属它自己的批次。 */
internal class ImportBatchQueue {
    data class Batch(val id: String, val itemIds: Set<String>)
    private data class Result(val id: String, var items: List<ImportItem>)
    private val _state = MutableStateFlow(ImportState())
    val state = _state.asStateFlow()
    private val pendingResults = ArrayDeque<Result>()
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
        val active = _state.value
        if (active.items.any { it.id == id }) {
            _state.value = active.copy(items = active.items.map { if (it.id == id) transform(it) else it })
        } else {
            val result = pendingResults.firstOrNull { result -> result.items.any { it.id == id } } ?: return
            result.items = result.items.map { if (it.id == id) transform(it) else it }
            if (pendingResults.firstOrNull() === result) _summary.value = summarize(result)
        }
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
        val result = Result(batch.id, items)
        val remaining = _state.value.items.filterNot { it.id in batch.itemIds }
        _state.value = ImportState(remaining, running = remaining.isNotEmpty())
        pendingResults.addLast(result)
        _summary.value = pendingResults.firstOrNull()?.let(::summarize)
        return summarize(result)
    }

    @Synchronized
    fun acknowledge(batchId: String?): List<String> {
        // A delayed dismiss callback must not consume the next batch's result.
        if (batchId == null || pendingResults.firstOrNull()?.id != batchId) return emptyList()
        val removed = pendingResults.removeFirst()
        _summary.value = pendingResults.firstOrNull()?.let(::summarize)
        return removed.items.filter { it.stage in PENDING_DECISION }.map { it.id }
    }

    private fun summarize(result: Result): ImportSummary = ImportSummary(
        success = result.items.count { it.stage == ImportStage.DONE },
        updated = result.items.count { it.stage == ImportStage.UPDATED },
        failed = result.items.count { it.stage == ImportStage.FAILED },
        skipped = result.items.count { it.stage == ImportStage.SKIPPED },
        messages = result.items.mapNotNull { item -> item.message?.let { item.displayName + "：" + it } },
        batchId = result.id,
        bookIds = result.items.filter { it.stage in setOf(ImportStage.DONE, ImportStage.UPDATED, ImportStage.SKIPPED) }
            .mapNotNull { it.bookId }.distinct(),
        pending = result.items.filter { it.stage in PENDING_DECISION }
    )

    private companion object {
        val PENDING_DECISION = setOf(ImportStage.REVIEW, ImportStage.WAITING_READER)
        val TERMINAL = setOf(ImportStage.DONE, ImportStage.UPDATED, ImportStage.SKIPPED, ImportStage.FAILED) + PENDING_DECISION
    }
}
