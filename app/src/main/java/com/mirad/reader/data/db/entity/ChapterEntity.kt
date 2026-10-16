package com.mirad.reader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 章节。TXT 用字节区间定位归一化正文；EPUB 用 zip 内 href + 锚点定位。
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "index"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    val index: Int,
    val title: String,
    /** TXT：归一化正文中的起始字节偏移。 */
    val startByte: Long = 0L,
    /** TXT：结束字节偏移（不含）。 */
    val endByte: Long = 0L,
    /** EPUB：zip 内相对路径。 */
    val href: String? = null,
    /** EPUB：文件内锚点。 */
    val anchor: String? = null,
    val charCount: Long = 0L,
    /** TXT：章节标题行（含换行）的字符数，渲染时用于把标题行单独呈现。 */
    val titleLineChars: Int = 0,
    /** 是否为卷/部级节点（TXT 分卷时使用）。 */
    val isVolume: Boolean = false
)
