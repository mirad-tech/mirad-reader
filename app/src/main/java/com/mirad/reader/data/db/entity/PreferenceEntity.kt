package com.mirad.reader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 键值配置表：全局阅读配置与每本书的覆盖配置均以 JSON 字符串存放。
 */
@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = 0L
)
