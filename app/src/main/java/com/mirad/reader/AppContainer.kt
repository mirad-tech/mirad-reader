package com.mirad.reader

import android.app.Application
import androidx.room.Room
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.data.imports.ImportPipeline
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
    ).build()

    val settings: SettingsRepository = SettingsRepository(database.preferenceDao())

    val books: BookRepository = BookRepository(
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        progressDao = database.progressDao(),
        paths = paths
    )

    val notes: NoteRepository = NoteRepository(
        highlightDao = database.highlightDao(),
        bookmarkDao = database.bookmarkDao()
    )

    val stats: StatsRepository = StatsRepository(database.sessionDao())

    val groups: GroupRepository = GroupRepository(database.groupDao())

    val textProvider: ChapterTextProvider = ChapterTextProvider(paths)

    val search: SearchRepository = SearchRepository(textProvider)

    val importer: ImportPipeline = ImportPipeline(
        context = app,
        paths = paths,
        books = books,
        scope = appScope
    )

    val context: Application get() = application
}
