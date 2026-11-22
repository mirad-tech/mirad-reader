package com.mirad.reader.data.db.entity

import androidx.room.Entity

/**
 * 全书搜索的章节正文缓存（普通表，可随时删除重建）。
 *
 * 首次搜索某本书时一次性写入，之后的搜索在库内用 instr 找出候选章节，命中位置仍在原文上计算。
 * 数据库版本 3 及以前这里是 FTS4 虚拟表：中文没有分词，倒排索引从未被查询用上，只多占空间，4 起改为普通表。
 *
 * [contentLower] 是大小写折叠后的正文，只在与原文不同时存储；为空表示与 [content] 相同。
 */
@Entity(
    tableName = "chapter_search",
    primaryKeys = ["bookId", "chapterIndex"]
)
data class ChapterSearchEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val content: String,
    val contentLower: String? = null
)
