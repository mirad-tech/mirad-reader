package com.mirad.reader.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 书籍格式。 */
object BookFormat {
    const val TXT = "TXT"
    const val EPUB = "EPUB"
}

@Entity(
    tableName = "books",
    indices = [Index("groupId"), Index("lastReadAt"), Index("addedAt")]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val author: String? = null,
    /** [BookFormat] 中的值。 */
    val format: String,
    /** 原始导入文件的绝对路径（filesDir/books/<uuid>/original.<ext>）。 */
    val filePath: String,
    /** TXT 归一化后的 UTF-8 正文路径；EPUB 为 null，正文按需从 zip 读取。 */
    val contentPath: String? = null,
    val coverPath: String? = null,
    /** 导入来源 URI，仅用于展示与去重提示。 */
    val sourceUri: String? = null,
    val fileSize: Long = 0L,
    /** TXT 检测到的编码名（如 UTF-8、GBK）。 */
    val encoding: String? = null,
    /** 分章规则 id；null 表示按字数兜底分节。 */
    val chapterRuleId: String? = null,
    /** 用户自定义分章正则。 */
    val chapterRegex: String? = null,
    val totalChapters: Int = 0,
    val totalChars: Long = 0L,
    /** EPUB 目录树 JSON（嵌套 卷/章），TXT 为 null。 */
    val tocJson: String? = null,
    val language: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val groupId: Long? = null,
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val finished: Boolean = false,
    val addedAt: Long = 0L,
    val lastReadAt: Long = 0L
)
