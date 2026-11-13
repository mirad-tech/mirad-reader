package com.mirad.reader

import android.app.Application
import android.util.Log
import androidx.annotation.MainThread
import androidx.room.Room
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.FontManager
import com.mirad.reader.data.removeRetiredPreviewFonts
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.data.imports.ImportPipeline
import com.mirad.reader.data.repo.BookMaintenance
import com.mirad.reader.data.repo.BookRepository
import com.mirad.reader.data.repo.ChapterTextProvider
import com.mirad.reader.data.repo.GroupRepository
import com.mirad.reader.data.repo.NoteRepository
import com.mirad.reader.data.repo.SearchRepository
import com.mirad.reader.data.repo.SettingsRepository
import com.mirad.reader.data.repo.StatsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import com.mirad.reader.core.sources.BookDownloads
import com.mirad.reader.core.sources.SourceRepository
import com.mirad.reader.core.sources.SourceSearch
import com.mirad.reader.data.downloads.BookDownloadManager
import com.mirad.reader.data.downloads.BookDownloadScheduler
import com.mirad.reader.data.imports.DownloadImportReceipts
import com.mirad.reader.data.sources.BookSourceRepository
import com.mirad.reader.data.sources.DefaultBookSourceEngine
import com.mirad.reader.data.sources.SourceHttpClient
import com.mirad.reader.data.sources.SourceSearchCoordinator
import com.mirad.reader.data.sources.sandbox.SandboxRuleExecutor
import java.io.File

/**
 * 手动依赖注入容器：数据库、仓库、导入管线与全局协程作用域。
 */
class AppContainer(app: Application) {

    private val application: Application = app

    /** 与应用同生命周期的协程作用域，用于导入、统计等长任务。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 短暂续读提示只记在本进程，重建页面不重复提示，重启应用自然恢复。 */
    internal val shelfResumePrompt = com.mirad.reader.ui.shelf.ShelfResumePromptState()

    val paths: AppPaths = AppPaths(app)

    val contentAccess: com.mirad.reader.data.io.BookContentAccess =
        com.mirad.reader.data.io.BookContentAccess(app)

    val database: AppDatabase = Room.databaseBuilder(
        app,
        AppDatabase::class.java,
        "mirad-reader.db"
    ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

    val settings: SettingsRepository = SettingsRepository(
        dao = database.preferenceDao(),
        onBackgroundImageDropped = { uris ->
            // 由仓库在持久化成功后回调：只删应用背景目录内的文件，且删除动作不阻塞保存路径
            val dir = paths.backgroundsDir
            appScope.launch {
                deleteDroppedBackgroundImages(dir, uris)
            }
        }
    )

    val books: BookRepository = BookRepository(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        progressDao = database.progressDao(),
        paths = paths,
        searchFtsDao = database.searchFtsDao(),
        appDatabase = database,
        sessionDao = database.sessionDao()
    )

    val notes: NoteRepository = NoteRepository(
        highlightDao = database.highlightDao(),
        bookmarkDao = database.bookmarkDao()
    )

    val stats: StatsRepository = StatsRepository(database.sessionDao())

    val groups: GroupRepository = GroupRepository(database.groupDao(), database)

    val textProvider: ChapterTextProvider = ChapterTextProvider(paths, contentAccess)

    /** 已导入书籍的维护：重新识别编码、重新分章、重新关联原文件。 */
    val maintenance: BookMaintenance = BookMaintenance(books, paths, database, contentAccess)

    val fonts: FontManager = FontManager(app, paths)

    init {
        appScope.launch { removeRetiredPreviewFonts(paths.fontsDir) }
    }

    val search: SearchRepository = SearchRepository(textProvider, database.searchFtsDao(), database.preferenceDao(), database)

    val importer: ImportPipeline = ImportPipeline(
        context = app,
        paths = paths,
        books = books,
        scope = appScope,
        appDatabase = database,
        contentAccess = contentAccess
    )

    val networkDownloader: com.mirad.reader.data.imports.NetworkBookDownloader =
        com.mirad.reader.data.imports.NetworkBookDownloader(
            context = app,
            importer = importer
        )

    val context: Application get() = application

    val openDownloads = MutableStateFlow(false)
    val feedback = com.mirad.reader.ui.common.FeedbackState("application")

    /** 主线程接纳时先排入仓库的写入锁，切页不会取消这次更新。 */
    @MainThread
    internal fun queueBookFinished(bookId: Long, finished: Boolean, endChapterIndex: Int? = null): Deferred<Boolean> =
        appScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                books.setFinished(bookId, finished, endChapterIndex)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("MiradReader", "无法更新书籍的读完状态", error)
                withContext(Dispatchers.Main.immediate) {
                    feedback.show("无法更新读完状态，请重试", com.mirad.reader.ui.common.FeedbackKind.ERROR)
                }
                false
            }
        }

    private val sourceHttp = SourceHttpClient()
    private val sourceEngine = DefaultBookSourceEngine(sourceHttp, SandboxRuleExecutor(app))
    val sources: SourceRepository = BookSourceRepository(File(app.filesDir, "book-sources"), sourceHttp, appScope)
    val sourceSearch: SourceSearch = SourceSearchCoordinator(sources, sourceEngine, appScope)
    val downloadScheduler by lazy { BookDownloadScheduler(app) { downloadManager.hasPendingTasks() } }
    private val downloadReceipts = DownloadImportReceipts(database.preferenceDao()) { books.getBook(it) != null }
    val downloadManager: BookDownloadManager = BookDownloadManager(
        root = File(app.filesDir, "book-downloads"), engine = sourceEngine, importer = importer, scope = appScope,
        findImported = downloadReceipts::find,
        scheduleRun = { downloadScheduler.schedule() }, cancelRun = { downloadScheduler.cancelIfIdle() },
        networkAvailable = { BookDownloadScheduler.isNetworkAvailable(app) }
    )
    val bookDownloads: BookDownloads get() = downloadManager
}

/** 与隔离文件回归共用真实清理消费者，仓库已核对新旧配置的引用。 */
internal fun deleteDroppedBackgroundImages(directory: File, uris: List<String>) {
    uris.forEach { uri ->
        runCatching {
            val file = File(uri)
            if (file.parentFile == directory) file.delete()
        }
    }
}
