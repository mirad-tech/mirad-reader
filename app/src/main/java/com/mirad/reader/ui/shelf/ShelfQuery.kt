package com.mirad.reader.ui.shelf

import com.mirad.reader.data.repo.ShelfBook

/**
 * 书架的筛选与排序（纯函数，可单元测试）。
 *
 * 书架默认排除隐藏书籍，再按分组和关键词筛选。
 */
object ShelfQuery {

    /** 伪分组 id：未分组。 */
    const val GROUP_NONE = -1L

    fun apply(
        all: List<ShelfBook>,
        sort: ShelfSort,
        groupId: Long? = null,
        query: String = "",
        includeHidden: Boolean = false
    ): List<ShelfBook> {
        val keyword = query.trim()
        return all
            .asSequence()
            .filter { includeHidden || !it.book.isHidden }
            .filter {
                when (groupId) {
                    null -> true
                    GROUP_NONE -> it.book.groupId == null
                    else -> it.book.groupId == groupId
                }
            }
            .filter {
                keyword.isEmpty() ||
                    it.book.title.contains(keyword, ignoreCase = true) ||
                    (it.book.author?.contains(keyword, ignoreCase = true) == true)
            }
            .sortedWith(comparator(sort))
            .toList()
    }

    fun comparator(sort: ShelfSort): Comparator<ShelfBook> = when (sort) {
        ShelfSort.RECENT -> compareByDescending<ShelfBook> { it.book.isPinned }
            .thenByDescending { it.lastReadAt }
            .thenByDescending { it.book.addedAt }
        ShelfSort.ADDED -> compareByDescending<ShelfBook> { it.book.isPinned }
            .thenByDescending { it.book.addedAt }
        ShelfSort.TITLE -> compareByDescending<ShelfBook> { it.book.isPinned }
            .thenBy { it.book.title }
        ShelfSort.AUTHOR -> compareByDescending<ShelfBook> { it.book.isPinned }
            .thenBy { it.book.author.orEmpty() }
        ShelfSort.SIZE -> compareByDescending<ShelfBook> { it.book.isPinned }
            .thenByDescending { it.book.fileSize }
    }
}
