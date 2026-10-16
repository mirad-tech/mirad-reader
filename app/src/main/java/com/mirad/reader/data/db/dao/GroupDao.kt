package com.mirad.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.mirad.reader.data.db.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<GroupEntity>

    @Query("UPDATE books SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearBooksInGroup(groupId: Long)
}
