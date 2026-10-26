package com.mirad.reader.data.imports

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 批次收敛保护：无论正常结束、抛异常还是被取消，都保证 `abort`（仅在中断时）与 `complete`
 * 恰好执行一次。
 *
 * 单独成类的原因很实际：`ImportPipeline` 依赖 Android `Context`，JVM 单测构造不出来；
 * 而"取消后批次状态会卡死"恰恰是这里最容易写错、也最难用 UI 复现的一条路径
 * （下载 79MB 的书，导入阶段也只持续一两秒）。抽成无 Android 依赖的小单元后，
 * 可以在 `runTest` 里用真正被取消的协程验证，而不是靠计时碰运气。
 */
internal class BatchCompletionGuard(
    private val abort: (reason: String) -> Unit,
    private val complete: () -> Unit
) {

    /**
     * @param block 批次的实际工作（逐个导入）。
     *
     * 取消时 [abort] 必须能看到"仍未终态"的条目：旧实现把清理写在裸的 `withContext` 里，
     * 协程已取消时那一行会立刻抛 `CancellationException`，清理与收敛都不会执行，
     * `running` 永远停在 true，导入浮层永久显示"导入中"。
     */
    suspend fun run(block: suspend () -> Unit) {
        var interrupted = false
        try {
            block()
        } catch (t: Throwable) {
            interrupted = true
            throw t
        } finally {
            // 必须在进入 NonCancellable 之前读：那个上下文里的 isActive 恒为 true。
            val cancelled = !currentCoroutineContext().isActive
            withContext(NonCancellable) {
                if (interrupted) {
                    abort(if (cancelled) ImportPipeline.CANCELLED_MESSAGE else ImportPipeline.INTERRUPTED_MESSAGE)
                }
                complete()
            }
        }
    }
}
