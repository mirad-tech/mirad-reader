package com.mirad.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.GroupDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.dao.SessionDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
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
        PreferenceEntity::class
    ],
    version = 1,
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
}
