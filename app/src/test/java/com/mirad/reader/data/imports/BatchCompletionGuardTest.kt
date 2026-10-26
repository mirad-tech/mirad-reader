package com.mirad.reader.data.imports

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消路径的确定性回归。
 *
 * 设备上几乎无法复现这条路径：79MB 的 TXT 下载完成后，导入阶段也只持续一两秒，
 * UI 自动化追不上"正在导入"的窗口。这里用真正被取消的协程验证同一个生产单元。
 */
class BatchCompletionGuardTest {

    @Test
    fun cancellationAbortsUnfinishedAndStillCompletes() = runTest {
        var abortReason: String? = null
        var completed = 0
        val guard = BatchCompletionGuard(
            abort = { abortReason = it },
            complete = { completed++ }
        )

        val job = launch { guard.run { awaitCancellation() } }
        runCurrent()
        assertNull("取消前不应误报中断", abortReason)
        assertEquals(0, completed)

        job.cancel()
        job.join()

        assertEquals(ImportPipeline.CANCELLED_MESSAGE, abortReason)
        assertEquals("取消后必须收敛批次，否则 running 永久为 true", 1, completed)
    }

    @Test
    fun normalCompletionDoesNotAbort() = runTest {
        var abortReason: String? = null
        var completed = 0
        val guard = BatchCompletionGuard(
            abort = { abortReason = it },
            complete = { completed++ }
        )

        guard.run { /* 全部条目正常走完 */ }

        assertNull("正常完成不应把条目标记为中断", abortReason)
        assertEquals(1, completed)
    }

    @Test
    fun exceptionMarksInterruptedAndPropagates() = runTest {
        var abortReason: String? = null
        var completed = 0
        val guard = BatchCompletionGuard(
            abort = { abortReason = it },
            complete = { completed++ }
        )

        val failure = runCatching {
            guard.run { throw IllegalStateException("正文写入失败") }
        }

        assertTrue("原始异常必须继续向上抛", failure.exceptionOrNull() is IllegalStateException)
        assertEquals(ImportPipeline.INTERRUPTED_MESSAGE, abortReason)
        assertEquals("异常路径同样要收敛", 1, completed)
    }
}
