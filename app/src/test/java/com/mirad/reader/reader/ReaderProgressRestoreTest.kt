package com.mirad.reader.reader

import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.dao.SessionDao
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.db.entity.ReadingSessionEntity
import com.mirad.reader.data.repo.BookRepository
import com.mirad.reader.data.repo.StatsRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/** 调用真实进度协调器及仓库；只替换存储边界，不模拟 Android/Room。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderProgressRestoreTest {
    @Test fun unreadyExitDoesNotOverwriteAnExistingProgress() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.progress.saveProgressNow()
            fixture.drainWrites()
            assertTrue("尚未获得可信正文位置，不应把默认 offset=0 写入已有进度", fixture.saved.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun failedPrepareNeverSavesEvenWhenAStaleStateLooksReady() = runTest {
        val fixture = Fixture(this, prepared = false)
        try {
            fixture.state.value = ReaderSessionState(ready = true, offset = 123)
            fixture.progress.saveProgressNow()
            fixture.drainWrites()
            assertTrue(fixture.saved.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun firstRestoredPositionIsTheBaselineRatherThanNewlyReadCharacters() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.progress.startSession()
            fixture.state.value = ReaderSessionState(ready = true, offset = 5000, percent = .5f)
            // 对应会话收到第一个可信位置后的实际保存入口。
            fixture.progress.scheduleProgressSave()
            runCurrent()
            fixture.elapseRecordedSession()
            fixture.progress.endSession()
            fixture.drainWrites()
            assertEquals(1, fixture.sessions.size)
            assertEquals("恢复旧偏移不是阅读动作", 0L, fixture.sessions.single().charsRead)
        } finally { fixture.close() }
    }

    @Test fun goingToBackgroundBeforeTheFirstPositionDoesNotRecordLoadingAsReading() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.progress.startSession()
            fixture.elapseRecordedSession()
            fixture.progress.endSession()
            fixture.drainWrites()
            assertTrue("尚未恢复正文的停留不应产生阅读会话", fixture.sessions.isEmpty())

            fixture.state.value = ReaderSessionState(ready = true, offset = 5000)
            fixture.progress.scheduleProgressSave()
            runCurrent()
            fixture.elapseRecordedSession()
            fixture.progress.endSession()
            fixture.drainWrites()
            assertTrue("后台迟到的位置不能自动开启统计", fixture.sessions.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun readyReadingAndResumeStillSaveAndCountForegroundDeltas() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.state.value = ReaderSessionState(ready = true, offset = 5000)
            fixture.progress.startSession()
            fixture.state.value = fixture.state.value.copy(offset = 5400, percent = .54f)
            fixture.progress.saveProgressNow()
            fixture.elapseRecordedSession()
            fixture.progress.endSession()
            fixture.drainWrites()
            assertEquals(5400, fixture.saved.single().charOffset)

            fixture.progress.startSession()
            fixture.state.value = fixture.state.value.copy(offset = 5425)
            fixture.elapseRecordedSession()
            fixture.progress.endSession()
            fixture.progress.endSession()
            fixture.drainWrites()
            assertEquals(listOf(400L, 25L), fixture.sessions.map { it.charsRead })
        } finally { fixture.close() }
    }

    @Test fun backgroundDuringChapterLoadingCountsOnlyTheLastVisiblePosition() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.state.value = ReaderSessionState(ready = true, offset = 5000)
            fixture.progress.startSession()
            fixture.state.value = fixture.state.value.copy(offset = 5400)
            // 对应gotoChapterInternal：先保存当前可信页，再切换为目标章的加载状态。
            fixture.progress.saveProgressNow()
            fixture.state.value = ReaderSessionState(chapterIndex = 1, offset = 0, ready = false)
            fixture.progress.saveProgressNow()
            fixture.elapseRecordedSession()
            fixture.progress.endSession()
            fixture.drainWrites()
            assertEquals(listOf(5400), fixture.saved.map { it.charOffset })
            assertEquals(400L, fixture.sessions.single().charsRead)
        } finally { fixture.close() }
    }

    private class Fixture(private val test: TestScope, prepared: Boolean = true) {
        val saved = mutableListOf<ProgressEntity>()
        val sessions = mutableListOf<ReadingSessionEntity>()
        val state = MutableStateFlow(ReaderSessionState())
        private val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        private val books = allocate<BookRepository>().also { repo ->
            setField(repo, "bookDao", proxy<BookDao> { _, _ -> Unit })
            setField(repo, "progressDao", proxy<ProgressDao> { method, args ->
                if (method == "upsert") saved += args!![0] as ProgressEntity
                Unit
            })
        }
        private val stats = StatsRepository(proxy<SessionDao> { method, args ->
            if (method == "insert") { sessions += args!![0] as ReadingSessionEntity; 1L } else Unit
        })
        private val container = allocate<AppContainer>().also {
            setField(it, "books", books)
            setField(it, "stats", stats)
            setField(it, "appScope", appScope)
        }
        val progress = ReaderProgress(container, 1L, test, state, { listOf(10_000L, 10_000L) }, { prepared })

        suspend fun drainWrites() {
            test.runCurrent()
            appScope.coroutineContext[Job]!!.children.toList().joinAll()
        }

        // 不等待真实六秒：仅把已经开始的会话时钟移过仓库最短统计门槛。
        fun elapseRecordedSession() {
            val field = ReaderProgress::class.java.getDeclaredField("sessionStartedAt").apply { isAccessible = true }
            if (field.getLong(progress) > 0L) field.setLong(progress, System.currentTimeMillis() - 6_000L)
        }

        fun close() { progress.cancelPendingSave(); appScope.cancel() }
    }

    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }

        // AppContainer 构造器会启动 Room；本测试保留真实进度/仓库，只跳过 Android 装配。
        private inline fun <reified T> allocate(): T = unsafe.allocateInstance(T::class.java) as T
        private fun setField(target: Any, name: String, value: Any) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args)
            } as T
    }
}
