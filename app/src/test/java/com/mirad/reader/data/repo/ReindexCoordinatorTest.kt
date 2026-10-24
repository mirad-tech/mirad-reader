package com.mirad.reader.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 重新识别的生产发布协调回归：测试直接执行 redetect 实际调用的 [ReindexCoordinator]，
 * 覆盖"数据库更新后正文替换失败"的关键故障边界（复核验收 4/5/6）。
 */
class ReindexCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 按调用序号注入 rename 成败：成功时执行真实 renameTo，失败时不移动文件。 */
    private fun sequenceRename(vararg outcomes: Boolean): (File, File) -> Boolean {
        val iter = outcomes.iterator()
        return { from, to -> iter.hasNext() && iter.next() && from.renameTo(to) }
    }

    private fun newTarget(content: String = "old-body"): File {
        val target = File(tmp.root, "content.utf8.txt")
        target.writeText(content)
        return target
    }

    private fun newStaging(target: File, content: String = "new-body"): File {
        val staging = ContentReplacer.stagingFile(target)
        staging.writeText(content)
        return staging
    }

    @Test
    fun successPublishesNewBodyWithoutRestore() = runTest {
        val target = newTarget()
        val staging = newStaging(target)
        var restored = false
        ReindexCoordinator(
            commitNewIndex = { },
            restorePreviousIndex = { restored = true }
        ).commitAndPublish(staging, target)
        assertEquals("new-body", target.readText())
        assertFalse("成功路径不得触碰旧目录", restored)
        assertFalse(staging.exists())
    }

    /**
     * 复核验收 4（关键新增）：数据库已提交后正文替换失败。
     * 协调器必须恢复旧目录，最终是完整旧状态——正文旧内容可读，绝无"新索引配旧正文"。
     */
    @Test
    fun replaceFailureAfterCommitRestoresPreviousIndex() = runTest {
        val target = newTarget("old-body")
        val staging = newStaging(target, "new-body")
        var commits = 0
        var restores = 0
        val coordinator = ReindexCoordinator(
            commitNewIndex = { commits++ },
            restorePreviousIndex = { restores++ }
        )
        // 直改、备份、替换、回滚全部失败：publish 必然失败且原正文恢复到 target
        val error = runCatching {
            coordinator.commitAndPublish(staging, target, sequenceRename(false, true, false, true))
        }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("已恢复原正文与原目录"))
        assertEquals("提交恰好一次", 1, commits)
        assertEquals("替换失败后必须恢复旧目录", 1, restores)
        assertEquals("读取路径上的原正文仍可读", "old-body", target.readText())
        assertFalse("恢复成功后 staging 已清理", staging.exists())
    }

    /**
     * 复核验收 6：恢复也失败时保留全部现场（staging 与原正文），
     * 由 ReindexRecoveryException 阻止最外层清理误删补救副本。
     */
    @Test
    fun restoreFailureKeepsRecoverySceneIntact() = runTest {
        val target = newTarget("old-body")
        val staging = newStaging(target, "new-body")
        val restoreError = IllegalStateException("db unavailable")
        val error = runCatching {
            ReindexCoordinator(
                commitNewIndex = { },
                restorePreviousIndex = { throw restoreError }
            ).commitAndPublish(staging, target, sequenceRename(false, true, false, true))
        }.exceptionOrNull()!!
        assertTrue("恢复失败必须用专用异常标记现场", error is ReindexRecoveryException)
        assertEquals("恢复阶段的原始错误信息必须随异常保留", restoreError.message, error.cause?.message)
        assertTrue("新正文副本必须保留", staging.exists())
        assertEquals("new-body", staging.readText())
        assertEquals("原正文必须保留可读", "old-body", target.readText())
    }

    /** 复核验收 5：数据库提交失败（异常透传）时不发布正文、不恢复目录，保持完整旧状态。 */
    @Test
    fun commitFailurePropagatesWithoutPublishOrRestore() = runTest {
        val target = newTarget("old-body")
        val staging = newStaging(target, "new-body")
        var restored = false
        val commitError = IllegalStateException("transaction failed")
        val error = runCatching {
            ReindexCoordinator(
                commitNewIndex = { throw commitError },
                restorePreviousIndex = { restored = true }
            ).commitAndPublish(staging, target)
        }.exceptionOrNull()!!
        assertEquals(commitError, error)
        assertFalse("未提交成功不得触碰旧目录", restored)
        assertEquals("old-body", target.readText())
        assertTrue("staging 清理由调用方负责，协调器不得删除", staging.exists())
    }

    /** 复核验收 5：提交阶段取消 = 数据库未提交，取消语义透传，不进入发布。 */
    @Test
    fun commitCancellationPropagatesWithoutPublish() = runTest {
        val target = newTarget("old-body")
        val staging = newStaging(target, "new-body")
        var restored = false
        val error = runCatching {
            ReindexCoordinator(
                commitNewIndex = { throw CancellationException("cancelled before commit") },
                restorePreviousIndex = { restored = true }
            ).commitAndPublish(staging, target)
        }.exceptionOrNull()!!
        assertTrue("取消必须保持取消语义", error is CancellationException)
        assertFalse(restored)
        assertEquals("old-body", target.readText())
    }

    /**
     * 提交挂起期间外部取消到达：取消发生在数据库提交完成之前，
     * 协调器不得带着已取消的任务继续发布。
     */
    @Test
    fun cancellationDuringCommitSuspendsPublication() = runTest {
        val target = newTarget("old-body")
        val staging = newStaging(target, "new-body")
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        var restored = false
        val job = async {
            ReindexCoordinator(
                commitNewIndex = {
                    commitEntered.complete(Unit)
                    releaseCommit.await()
                },
                restorePreviousIndex = { restored = true }
            ).commitAndPublish(staging, target)
        }
        commitEntered.await()
        job.cancel()
        releaseCommit.complete(Unit)
        val error = runCatching { job.await() }.exceptionOrNull()!!
        assertTrue(error is CancellationException)
        assertFalse("取消不得触发恢复", restored)
        assertEquals("正文保持旧状态", "old-body", target.readText())
        assertTrue(staging.exists())
    }
}
