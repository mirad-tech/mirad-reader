package com.mirad.reader

import android.app.Application
import androidx.room.Room
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.FontManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    val paths: AppPaths = AppPaths(app)

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
                uris.forEach { uri ->
                    runCatching {
                        val file = java.io.File(uri)
                        if (file.parentFile == dir) file.delete()
                    }
                }
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

    val textProvider: ChapterTextProvider = ChapterTextProvider(paths)

    /** 已导入书籍的维护：重新识别编码、重新分章（分章不重写正文文件）。 */
    val maintenance: BookMaintenance = BookMaintenance(books, paths, database)

    val fonts: FontManager = FontManager(app, paths)

    val search: SearchRepository = SearchRepository(textProvider, database.searchFtsDao())

    val importer: ImportPipeline = ImportPipeline(
        context = app,
        paths = paths,
        books = books,
        scope = appScope,
        appDatabase = database
    )

    val networkDownloader: com.mirad.reader.data.imports.NetworkBookDownloader =
        com.mirad.reader.data.imports.NetworkBookDownloader(
            context = app,
            importer = importer
        )

    val context: Application get() = application

    val openDownloads = MutableStateFlow(false)
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
