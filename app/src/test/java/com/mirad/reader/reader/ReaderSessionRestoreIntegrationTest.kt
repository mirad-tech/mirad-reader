package com.mirad.reader.reader

import android.app.Application
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.dao.SessionDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.db.entity.ReadingSessionEntity
import com.mirad.reader.data.repo.BookRepository
import com.mirad.reader.data.repo.ChapterTextProvider
import com.mirad.reader.data.repo.NoteRepository
import com.mirad.reader.data.repo.StatsRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/**
 * 执行真实ReaderSession.prepare/桥接闭包/进度仓库/release。
 * Android Handler与页面加载由可控事件替代；不启动WebView或Room，不声称设备E2E。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSessionRestoreIntegrationTest {
    @Test fun prepareThenExitBeforeRenderingPreservesPersistedProgress() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.session.prepare(ReaderConfig.DEFAULT, false)
            assertEquals(5000, fixture.session.state.value.offset)
            assertEquals(.5f, fixture.session.state.value.percent)
            assertFalse(fixture.session.state.value.ready)
            fixture.releaseSession()
            fixture.drainWrites()
            assertTrue(fixture.saved.isEmpty())
            assertTrue(fixture.sessions.isEmpty())
            assertNull(ReaderKeys.pager)
        } finally { fixture.close() }
    }

    @Test fun callbacksBeforeRenderingAndAfterReleaseAreIgnored() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.session.prepare(ReaderConfig.DEFAULT, false)
            fixture.emitReady()
            fixture.emitPosition(0)
            assertFalse(fixture.session.state.value.ready)
            assertEquals(5000, fixture.session.state.value.offset)

            fixture.releaseSession()
            fixture.scriptInitialized()
            fixture.emitPosition(125)
            assertFalse(fixture.session.state.value.ready)
            assertEquals(5000, fixture.session.state.value.offset)
            fixture.drainWrites()
            assertTrue(fixture.saved.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun readySignalWithoutPositionDoesNotOpenTheSaveOrStatisticsGate() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.session.prepare(ReaderConfig.DEFAULT, false)
            fixture.scriptInitialized()
            fixture.emitReady()
            assertFalse("脚本就绪不等于首次位置已回报", fixture.session.state.value.ready)
            fixture.session.startSession()
            fixture.elapseRecordedSession()
            fixture.session.saveProgressNow()
            fixture.session.endSession()
            fixture.drainWrites()
            assertTrue(fixture.saved.isEmpty())
            assertTrue(fixture.sessions.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun restoredPositionAndForegroundResumeUseTheRealSessionStatistics() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.session.prepare(ReaderConfig.DEFAULT, false)
            // 页面入口确认脚本后，生产JS先恢复位置再发布ready。
            fixture.scriptInitialized()
            fixture.emitPosition(5000)
            fixture.emitReady()
            fixture.elapseRecordedSession()
            fixture.session.endSession()
            fixture.drainWrites()
            assertEquals(listOf(0L), fixture.sessions.map { it.charsRead })

            fixture.session.startSession()
            fixture.emitPosition(5200)
            fixture.elapseRecordedSession()
            fixture.session.saveProgressNow()
            fixture.session.endSession()
            fixture.drainWrites()
            assertEquals(listOf(0L, 200L), fixture.sessions.map { it.charsRead })
            assertEquals(5200, fixture.saved.last().charOffset)
        } finally { fixture.close() }
    }

    @Test fun failedPrepareCanReleaseKeysWithoutSavingPartialState() = runTest {
        val fixture = Fixture(this, failHighlights = true)
        try {
            val result = runCatching { fixture.session.prepare(ReaderConfig.DEFAULT, false) }
            assertTrue(result.isFailure)
            fixture.releaseSession()
            fixture.drainWrites()
            assertNull(ReaderKeys.pager)
            assertTrue(fixture.saved.isEmpty())
            assertTrue(fixture.sessions.isEmpty())
        } finally { fixture.close() }
    }

    private class Fixture(private val test: TestScope, failHighlights: Boolean = false) {
        val saved = mutableListOf<ProgressEntity>()
        val sessions = mutableListOf<ReadingSessionEntity>()
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        private val pageScope = CoroutineScope(SupervisorJob() + dispatcher)
        private val original = ProgressEntity(bookId = 1L, charOffset = 5000, pageIndex = 5, pageCount = 10, percent = .5f)
        private val book = BookEntity(id = 1L, title = "恢复夹具", format = BookFormat.TXT, filePath = "synthetic.txt")
        private val chapters = listOf(ChapterEntity(bookId = 1L, index = 0, title = "第一章", charCount = 10_000L))
        private val books = BookRepository(
            bookDao = proxy<BookDao> { method, _ -> if (method == "getById") book else Unit },
            chapterDao = proxy<ChapterDao> { method, _ -> if (method == "getForBook") chapters else error(method) },
            progressDao = proxy<ProgressDao> { method, args ->
                when (method) {
                    "get" -> original
                    "upsert" -> { saved += args!![0] as ProgressEntity; Unit }
                    else -> error(method)
                }
            },
            paths = allocate<AppPaths>()
        )
        private val notes = NoteRepository(
            proxy<HighlightDao> { method, _ ->
                if (method == "getForChapter" && failHighlights) error("highlight read unavailable")
                emptyList<Any>()
            },
            proxy<BookmarkDao> { method, _ -> if (method == "find") null else emptyList<Any>() }
        )
        private val stats = StatsRepository(proxy<SessionDao> { method, args ->
            if (method == "insert") { sessions += args!![0] as ReadingSessionEntity; 1L } else error(method)
        })
        private val container = allocate<AppContainer>().also {
            setField(it, "application", Application())
            setField(it, "books", books)
            setField(it, "notes", notes)
            setField(it, "stats", stats)
            setField(it, "textProvider", ChapterTextProvider())
            setField(it, "appScope", appScope)
        }
        val session = ReaderSession(container, book, pageScope)
        private val bridge = field(session, "bridge") as ReaderJsBridge
        private var released = false

        // 只替代Android页面回调的到达时机，状态处理执行真实桥接构造回调。
        fun scriptInitialized() { setField(session, "chapterRendered", true) }
        @Suppress("UNCHECKED_CAST")
        fun emitReady() { (field(bridge, "onReady") as (Int, Int) -> Unit)(10, 10_000) }
        @Suppress("UNCHECKED_CAST")
        fun emitPosition(offset: Int) { (field(bridge, "onState") as (Int, Int, Int, Int) -> Unit)(offset / 1000, 10, offset, 10_000) }
        fun elapseRecordedSession() {
            val progress = field(session, "progressTracker")!!
            val started = progress.javaClass.getDeclaredField("sessionStartedAt").apply { isAccessible = true }
            if (started.getLong(progress) > 0L) started.setLong(progress, System.currentTimeMillis() - 6_000L)
        }
        suspend fun drainWrites() {
            test.runCurrent()
            appScope.coroutineContext[Job]!!.children.toList().joinAll()
        }
        fun releaseSession() { if (!released) { session.release(); released = true } }
        suspend fun close() {
            releaseSession()
            pageScope.cancel()
            drainWrites()
            appScope.cancel()
        }
    }

    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        private inline fun <reified T> allocate(): T = unsafe.allocateInstance(T::class.java) as T
        private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target)
        }
        private fun setField(target: Any, name: String, value: Any) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args) } as T
    }
}
