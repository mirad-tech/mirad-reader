package com.mirad.reader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 书架分组。 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sortOrder: Int = 0
)
