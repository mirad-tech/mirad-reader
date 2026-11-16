package com.mirad.reader.data.db

/** 章节与正文发布事务共同更新，供短暂的笔记撤销判断定位是否仍属于当前版本。 */
internal const val BOOK_CONTENT_GENERATION_PREFIX = "book_content_generation_v1_"

internal fun bookContentGenerationKey(bookId: Long): String = "$BOOK_CONTENT_GENERATION_PREFIX$bookId"
