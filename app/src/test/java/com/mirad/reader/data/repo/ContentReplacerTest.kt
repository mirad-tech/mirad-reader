package com.mirad.reader.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 正文发布的文件层回归：独占临时文件 + 备份-替换-回滚。
 * 对应审查 P1：取消的旧任务不得删除新任务的临时文件；发布任一步失败时原正文必须仍可读。
 */
class ContentReplacerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newTarget(content: String = "old"): File {
        val target = File(tmp.root, "content.utf8.txt")
        target.writeText(content)
        return target
    }

    private fun newStaging(target: File, content: String = "new"): File {
        val staging = ContentReplacer.stagingFile(target)
        staging.writeText(content)
        return staging
    }

    @Test
    fun stagingFilesAreExclusivePerOperation() {
        val target = newTarget()
        val a = ContentReplacer.stagingFile(target)
        val b = ContentReplacer.stagingFile(target)
        assertNotEquals("两次操作的临时文件必须独占", a.absolutePath, b.absolutePath)
        assertEquals("临时文件与目标同目录", target.parentFile, a.parentFile)
        assertTrue("临时文件名可辨识", a.name.startsWith(target.name + "."))
        assertTrue(a.name.endsWith(".tmp"))
    }

    @Test
    fun publishMovesStagingOntoTargetWhenRenameWorks() {
        val target = newTarget("old")
        val staging = newStaging(target, "new")
        assertTrue(ContentReplacer.publish(staging, target))
        assertEquals("new", target.readText())
        assertFalse("staging 应随成功发布消失", staging.exists())
        assertFalse("不应残留备份", File(target.parentFile, target.name + ".bak").exists())
    }

    /** 按调用序号注入成败：成功时执行真实 renameTo（保持文件系统语义），失败时不移动文件。 */
    private fun sequenceRename(vararg outcomes: Boolean): (File, File) -> Boolean {
        val iter = outcomes.iterator()
        return { from, to -> iter.hasNext() && iter.next() && from.renameTo(to) }
    }

    @Test
    fun publishFallsBackToBackupReplaceWhenDirectRenameFails() {
        val target = newTarget("old")
        val staging = newStaging(target, "new")
        // 直改失败 → 备份成功 → 替换成功
        val rename = sequenceRename(false, true, true)
        assertTrue(ContentReplacer.publish(staging, target, rename))
        assertEquals("new", target.readText())
        assertFalse(File(target.parentFile, target.name + ".bak").exists())
    }

    @Test
    fun publishRestoresOriginalBodyWhenReplaceAfterBackupFails() {
        val target = newTarget("old")
        val staging = newStaging(target, "new")
        // 直改失败 → 备份成功 → 替换失败 → 回滚恢复成功
        val rename = sequenceRename(false, true, false, true)
        assertFalse(ContentReplacer.publish(staging, target, rename))
        assertEquals("原正文必须恢复可读", "old", target.readText())
        assertTrue("staging（新正文唯一副本）必须保留", staging.exists())
        assertEquals("new", staging.readText())
    }

    @Test
    fun publishKeepsTargetIntactWhenEvenBackupFails() {
        val target = newTarget("old")
        val staging = newStaging(target, "new")
        // 直改失败 → 备份也失败
        val rename = sequenceRename(false, false)
        assertFalse(ContentReplacer.publish(staging, target, rename))
        assertEquals("old", target.readText())
        assertTrue(staging.exists())
    }

    @Test
    fun publishWorksWhenTargetDoesNotExistYet() {
        val target = File(tmp.root, "content.utf8.txt")
        val staging = newStaging(target, "new")
        assertTrue(ContentReplacer.publish(staging, target))
        assertEquals("new", target.readText())
    }

    /**
     * 交错场景的文件层复现（审查验收 1）：任务 A 取消后的清理（删自己的 staging）
     * 不得影响随后重试任务 B 的临时文件与发布。
     */
    @Test
    fun cancelledTaskCleanupNeverDeletesRetryingTasksStaging() {
        val target = newTarget("old")
        val stagingA = newStaging(target, "from-a")
        val stagingB = newStaging(target, "from-b")
        // A 被取消：异常清理只删自己的独占临时文件
        stagingA.delete()
        // B（立即重试）不受影响：临时文件完好并成功发布
        assertTrue("B 的临时文件不能被 A 的清理删掉", stagingB.exists())
        assertTrue(ContentReplacer.publish(stagingB, target))
        assertEquals("from-b", target.readText())
    }
}
