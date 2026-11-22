package com.mirad.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.GroupDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.dao.SearchCacheDao
import com.mirad.reader.data.db.dao.SessionDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.ChapterSearchEntity
import com.mirad.reader.data.db.entity.GroupEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.db.entity.ReadingSessionEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        ReadingSessionEntity::class,
        GroupEntity::class,
        PreferenceEntity::class,
        ChapterSearchEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun sessionDao(): SessionDao
    abstract fun groupDao(): GroupDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun searchCacheDao(): SearchCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `chapters_fts` USING FTS4(" +
                        "`bookId` INTEGER NOT NULL, " +
                        "`chapterIndex` INTEGER NOT NULL, " +
                        "`chapterTitle` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite FTS4 virtual tables do not support ALTER TABLE ADD COLUMN.
                // Drop and recreate with contentLower column for Unicode-safe case-insensitive queries.
                db.execSQL("DROP TABLE IF EXISTS `chapters_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `chapters_fts` USING FTS4(" +
                        "`bookId` INTEGER NOT NULL, " +
                        "`chapterIndex` INTEGER NOT NULL, " +
                        "`chapterTitle` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`contentLower` TEXT NOT NULL)"
                )
            }
        }

        /**
         * 搜索缓存从 FTS4 虚拟表改为普通表：旧表只是可重建的缓存，直接删除，各书下次搜索时重新建立。
         * 删除后的空闲页由 [SearchCacheVacuum] 在启动后单独回收（VACUUM 不能在迁移事务内执行）。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `chapters_fts`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_search` (" +
                        "`bookId` INTEGER NOT NULL, " +
                        "`chapterIndex` INTEGER NOT NULL, " +
                        "`chapterTitle` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`contentLower` TEXT, " +
                        "PRIMARY KEY(`bookId`, `chapterIndex`))"
                )
            }
        }
    }
}
