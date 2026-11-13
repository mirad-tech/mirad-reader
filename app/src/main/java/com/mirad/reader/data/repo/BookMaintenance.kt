package com.mirad.reader.data.repo

import androidx.room.withTransaction
import com.mirad.reader.core.epub.EpubParser
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.html.Html
import com.mirad.reader.core.model.ChapterTextMap
import com.mirad.reader.core.model.ProgressMath
import com.mirad.reader.core.model.TocJson
import com.mirad.reader.core.text.ChapterDetector
import com.mirad.reader.core.text.ChapterSlice
import com.mirad.reader.core.text.ChapterSplitter
import com.mirad.reader.core.text.StreamingTxtProcessor
import com.mirad.reader.core.text.TextCodec
import com.mirad.reader.core.text.TextNormalizer
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.SearchFtsDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.imports.BookEditionUpdater
import com.mirad.reader.data.imports.EditionRelation
import com.mirad.reader.data.imports.FileFingerprint
import com.mirad.reader.data.imports.ImportPipeline
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.io.MissingBookSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 已导入书籍的维护操作：
 * - 重新识别章节（零拷贝模式下仅按需加载含进度/书签/划线的章节，避免整本拼串占用大内存）
 * - 重新识别编码（零拷贝模式下直接重扫原文件更新章节区间，不产生临时正文副本）
 * - 原文件移动/重命名/权限失效后的「重新关联文件」（自动探测新文件编码，无持久授权时自动回退保存单份副本）
 */
class BookMaintenance internal constructor(
    private val books: BookRepository,
    private val paths: AppPaths,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val searchFtsDao: SearchFtsDao? = null,
    private val database: AppDatabase? = null,
    private val contentAccess: BookContentAccess = BookContentAccess()
) {
    constructor(
        books: BookRepository,
        paths: AppPaths,
        database: AppDatabase,
        contentAccess: BookContentAccess = BookContentAccess()
    ) : this(
        books = books,
        paths = paths,
        bookmarkDao = database.bookmarkDao(),
        highlightDao = database.highlightDao(),
        searchFtsDao = database.searchFtsDao(),
        database = database,
        contentAccess = contentAccess
    )

    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        val db = database
        return if (db != null) db.withTransaction { block() } else block()
    }

    private val textProvider = ChapterTextProvider(paths, contentAccess)

    @ConsistentCopyVisibility
    data class ChapterPreview internal constructor(
        val slices: List<ChapterSlice>,
        val automatic: Boolean,
        val reason: String,
        internal val bookId: Long,
        internal val digest: String,
        internal val oldChapters: List<ChapterEntity>,
        internal val ruleId: String?
    )

    suspend fun previewChapters(book: BookEntity): ChapterPreview = withContext(Dispatchers.IO) {
        require(book.format != BookFormat.EPUB) { "EPUB 不需要重新识别章节" }
        val current = books.getBook(book.id) ?: error("书籍已不存在")
        if (!current.contentPath.isNullOrBlank()) {
            val bytes = normalizedFile(current).readBytes()
            val text = bytes.toString(Charsets.UTF_8)
            check(text.isNotBlank()) { "没有可识别的正文" }
            val detection = ChapterDetector.detect(text)
            return@withContext ChapterPreview(
                slices = ChapterSplitter.split(text, detection),
                automatic = detection.marks.isEmpty(),
                reason = detection.reason,
                bookId = book.id,
                digest = sha256Hex(bytes),
                oldChapters = books.getChapters(book.id),
                ruleId = detection.rule?.id
            )
        }

        ensureSourceReadable(current)
        val streamResult = StreamingTxtProcessor.indexRaw(
            openStream = { contentAccess.openStream(current.filePath) },
            declaredBytes = contentAccess.size(current.filePath),
            manualEncoding = current.encoding,
            maxRawBytes = ImportPipeline.MAX_TXT_BYTES
        )
        check(streamResult.totalChars > 0L && streamResult.slices.isNotEmpty()) { "没有可识别的正文" }
        val automatic = streamResult.ruleId == null
        val reason = if (automatic) {
            "未识别到规律的章节标题，已按字数自动分节"
        } else {
            "识别到 ${streamResult.slices.size} 节（规则：${streamResult.ruleId}）"
        }
        ChapterPreview(
            slices = streamResult.slices,
            automatic = automatic,
            reason = reason,
            bookId = book.id,
            digest = rawSourceDigest(current),
            oldChapters = books.getChapters(book.id),
            ruleId = streamResult.ruleId
        )
    }

    suspend fun applyChapters(book: BookEntity, preview: ChapterPreview): Int =
        books.contentLock(book.id).withLock { applyChaptersLocked(book, preview) }

    private suspend fun applyChaptersLocked(book: BookEntity, preview: ChapterPreview): Int = withContext(Dispatchers.IO) {
        require(book.id == preview.bookId && book.format != BookFormat.EPUB)
        runInTransaction {
            val current = books.getBook(book.id) ?: error("书籍已不存在")
            val slices = preview.slices
            check(slices.isNotEmpty()) { "无法切分正文" }

            val bookmarks = bookmarkDao.getAllForBook(book.id)
            val highlights = highlightDao.getAllForBook(book.id)
            val progress = books.getProgress(book.id)

            if (!current.contentPath.isNullOrBlank()) {
                val bytes = normalizedFile(current).readBytes()
                check(sha256Hex(bytes) == preview.digest && books.getChapters(book.id) == preview.oldChapters) {
                    "正文或目录已变化，请重新预览"
                }
                val text = bytes.toString(Charsets.UTF_8)
                val totalChars = text.length.toLong()
                val newMaps = slices.map { slice ->
                    ChapterTextMap(
                        text.substring(slice.startChar, slice.endChar),
                        slice.title,
                        slice.titleLineChars,
                        slice.isVolume,
                        slice.startChar
                    )
                }
                var sourceStart = 0
                var previousEnd = 0L
                var validPrefix = true
                val oldMaps = preview.oldChapters.map { chapter ->
                    val valid = validPrefix &&
                        chapter.startByte == previousEnd &&
                        chapter.endByte >= chapter.startByte &&
                        chapter.endByte <= bytes.size
                    previousEnd = chapter.endByte
                    validPrefix = valid
                    if (!valid) null else {
                        val body = bytes.copyOfRange(chapter.startByte.toInt(), chapter.endByte.toInt()).toString(Charsets.UTF_8)
                        validPrefix = body.toByteArray(Charsets.UTF_8)
                            .contentEquals(bytes.copyOfRange(chapter.startByte.toInt(), chapter.endByte.toInt()))
                        val map = ChapterTextMap(body, chapter.title, chapter.titleLineChars, chapter.isVolume, sourceStart)
                        sourceStart += body.length
                        map.takeIf { validPrefix }
                    }
                }
                fun point(chapter: Int, offset: Int): Pair<Int, Int>? {
                    val source = oldMaps.getOrNull(chapter)?.sourceAt(offset) ?: return null
                    return newMaps.mapIndexedNotNull { index, map -> map.domAt(source)?.let { index to it } }.lastOrNull()
                }
                return@runInTransaction commitMappedChapters(
                    current = current,
                    slices = slices,
                    totalChars = totalChars,
                    ruleId = preview.ruleId,
                    bookmarks = bookmarks,
                    highlights = highlights,
                    progress = progress,
                    point = ::point,
                    oldSourceStart = { oldMaps.getOrNull(it)?.sourceStart },
                    migrateHighlight = { hl ->
                        oldMaps.getOrNull(hl.chapterIndex)?.migrateRange(hl.startOffset, hl.endOffset, hl.text, newMaps)
                    }
                )
            }

            // 零拷贝模式：仅按需加载实际存在进度、书签、划线的章节，避免 80MB 全书拼串
            ensureSourceReadable(current)
            check(rawSourceDigest(current) == preview.digest && books.getChapters(book.id) == preview.oldChapters) {
                "正文或目录已变化，请重新预览"
            }

            val totalChars = slices.last().endChar.toLong()
            val totalSourceBytes = contentAccess.size(current.filePath)
            val neededOldIndices = buildSet {
                progress?.chapterIndex?.let(::add)
                bookmarks.forEach { if (it.chapterIndex >= 0) add(it.chapterIndex) }
                highlights.forEach { if (it.chapterIndex >= 0) add(it.chapterIndex) }
            }

            val oldMapsByIndex = HashMap<Int, ChapterTextMap>(neededOldIndices.size)
            val oldSourceStarts = IntArray(preview.oldChapters.size) { -1 }
            var sourceStart = 0
            var previousEnd = 0L
            var validPrefix = true
            for (i in preview.oldChapters.indices) {
                val chapter = preview.oldChapters[i]
                val byteValid = validPrefix &&
                    chapter.startByte == previousEnd &&
                    chapter.endByte >= chapter.startByte &&
                    chapter.charCount >= 0L &&
                    (totalSourceBytes < 0L || chapter.endByte <= totalSourceBytes)
                previousEnd = chapter.endByte
                if (!byteValid) {
                    validPrefix = false
                    continue
                }
                if (chapter.index in neededOldIndices) {
                    val body = textProvider.readTxtTextOrNull(current, chapter)
                    if (body == null || body.length.toLong() != chapter.charCount) {
                        validPrefix = false
                        continue
                    }
                    oldSourceStarts[i] = sourceStart
                    oldMapsByIndex[chapter.index] = ChapterTextMap(
                        body,
                        chapter.title,
                        chapter.titleLineChars,
                        chapter.isVolume,
                        sourceStart
                    )
                    sourceStart += body.length
                } else {
                    oldSourceStarts[i] = sourceStart
                    sourceStart += chapter.charCount.toInt()
                }
            }

            val newMapsCache = HashMap<Int, ChapterTextMap?>()
            fun loadNewMap(newIndex: Int): ChapterTextMap? {
                if (newIndex !in slices.indices) return null
                if (newMapsCache.containsKey(newIndex)) return newMapsCache[newIndex]
                val slice = slices[newIndex]
                val rawLen = (slice.endByte - slice.startByte)
                val loaded = if (rawLen in 0..Int.MAX_VALUE) {
                    val rawBytes = if (rawLen == 0L) ByteArray(0)
                    else contentAccess.readRange(current.filePath, slice.startByte, rawLen.toInt())
                    rawBytes?.let {
                        val body = StreamingTxtProcessor.decodeRawSlice(
                            rawBytes = it,
                            charsetName = current.encoding,
                            stripLeadingBom = slice.startByte == 0L
                        )
                        ChapterTextMap(body, slice.title, slice.titleLineChars, slice.isVolume, slice.startChar)
                    }
                } else null
                newMapsCache[newIndex] = loaded
                return loaded
            }

            fun findSliceIndex(sourcePos: Int): Int {
                var lo = 0
                var hi = slices.lastIndex
                var found = 0
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (slices[mid].startChar <= sourcePos) {
                        found = mid
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                return found
            }

            fun point(chapter: Int, offset: Int): Pair<Int, Int>? {
                val source = oldMapsByIndex[chapter]?.sourceAt(offset) ?: return null
                val primary = findSliceIndex(source)
                val candidates = buildList {
                    if (primary > 0 && slices[primary - 1].endChar >= source) add(primary - 1)
                    add(primary)
                    if (primary + 1 < slices.size && slices[primary + 1].startChar <= source) add(primary + 1)
                }
                return candidates.mapNotNull { idx ->
                    loadNewMap(idx)?.domAt(source)?.let { idx to it }
                }.lastOrNull()
            }

            commitMappedChapters(
                current = current,
                slices = slices,
                totalChars = totalChars,
                ruleId = preview.ruleId,
                bookmarks = bookmarks,
                highlights = highlights,
                progress = progress,
                point = ::point,
                oldSourceStart = { oldSourceStarts.getOrNull(it)?.takeIf { pos -> pos >= 0 } },
                migrateHighlight = migrate@{ hl ->
                    val oldMap = oldMapsByIndex[hl.chapterIndex] ?: return@migrate null
                    val firstSource = oldMap.sourceAt(hl.startOffset) ?: return@migrate null
                    val lastSource = oldMap.sourceAt(hl.endOffset) ?: return@migrate null
                    if (lastSource <= firstSource) return@migrate null
                    val firstSlice = (findSliceIndex(firstSource) - 1).coerceAtLeast(0)
                    val lastSlice = (findSliceIndex(lastSource) + 1).coerceAtMost(slices.lastIndex)
                    val subTargets = ArrayList<ChapterTextMap>(lastSlice - firstSlice + 1)
                    for (idx in firstSlice..lastSlice) {
                        val map = loadNewMap(idx) ?: return@migrate null
                        subTargets.add(map)
                    }
                    oldMap.migrateRange(hl.startOffset, hl.endOffset, hl.text, subTargets)
                        ?.map { range -> range.copy(chapter = firstSlice + range.chapter) }
                }
            )
        }
    }

    private suspend fun commitMappedChapters(
        current: BookEntity,
        slices: List<ChapterSlice>,
        totalChars: Long,
        ruleId: String?,
        bookmarks: List<com.mirad.reader.data.db.entity.BookmarkEntity>,
        highlights: List<com.mirad.reader.data.db.entity.HighlightEntity>,
        progress: com.mirad.reader.data.db.entity.ProgressEntity?,
        point: (Int, Int) -> Pair<Int, Int>?,
        oldSourceStart: (Int) -> Int?,
        migrateHighlight: (com.mirad.reader.data.db.entity.HighlightEntity) -> List<ChapterTextMap.Range>?
    ): Int {
        val chapters = slices.mapIndexed { i, slice ->
            ChapterEntity(
                bookId = current.id,
                index = i,
                title = slice.title,
                startByte = slice.startByte,
                endByte = slice.endByte,
                charCount = slice.charCount.toLong(),
                titleLineChars = slice.titleLineChars,
                isVolume = slice.isVolume
            )
        }
        searchFtsDao?.deleteForBook(current.id)
        books.saveChapters(current.id, chapters)
        books.updateIndexInfo(current.id, chapters.size, totalChars, ruleId, null, current.encoding)
        bookmarks.forEach { bookmark ->
            val mapped = point(bookmark.chapterIndex, bookmark.charOffset)
            bookmarkDao.update(
                if (mapped == null) bookmark.copy(chapterIndex = -1)
                else bookmark.copy(
                    chapterIndex = mapped.first,
                    charOffset = mapped.second,
                    chapterTitle = chapters[mapped.first].title
                )
            )
        }
        highlights.forEach { highlight ->
            val ranges = migrateHighlight(highlight)
            if (ranges == null) highlightDao.update(highlight.copy(chapterIndex = -1))
            else ranges.forEachIndexed { i, range ->
                val mapped = highlight.copy(
                    id = if (i == 0) highlight.id else 0,
                    chapterIndex = range.chapter,
                    startOffset = range.start,
                    endOffset = range.end,
                    text = range.text
                )
                if (i == 0) highlightDao.update(mapped) else highlightDao.insert(mapped)
            }
        }
        if (progress != null) {
            val mapped = point(progress.chapterIndex, progress.charOffset)
                ?: oldSourceStart(progress.chapterIndex)?.let { oldStart ->
                    val index = slices.indexOfLast { it.startChar <= oldStart }.coerceAtLeast(0)
                    index to 0
                } ?: (0 to 0)
            books.saveProgress(
                progress.copy(chapterIndex = mapped.first, charOffset = mapped.second, pageIndex = 0, pageCount = 0)
            )
        }
        return chapters.size
    }

    private fun normalizedFile(book: BookEntity): File {
        val file = File(book.contentPath ?: error("找不到归一化正文，请重新导入"))
        check(file.isFile) { "找不到归一化正文，请重新导入" }
        return file
    }

    private fun rawSourceDigest(book: BookEntity): String {
        val fp = FileFingerprint.of(contentAccess, book.filePath)
            ?: throw MissingBookSourceException("原文件已移动或无法读取，请重新关联文件")
        return "$fp|${book.encoding.orEmpty()}"
    }

    private fun ensureSourceReadable(book: BookEntity) {
        if (!contentAccess.isReadable(book.filePath)) {
            throw MissingBookSourceException("原文件已移动或无法读取，请重新关联文件")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    data class ReindexResult(
        val encoding: String,
        val source: String,
        val chapters: Int,
        val chars: Long
    )

    /** 候选编码的一项预览，供用户肉眼挑正确的那一个。 */
    data class Preview(
        val charset: String,
        val source: String,
        val text: String
    )

    /** 用原文件重新解码并重建章节索引。零拷贝模式下不写任何正文副本，直接更新原文件字节区间索引。 */
    suspend fun redetect(book: BookEntity, charsetHint: String? = null): ReindexResult {
        return books.contentLock(book.id).withLock {
            withContext(Dispatchers.IO) {
                if (book.format == BookFormat.EPUB) {
                    throw IllegalStateException("EPUB 不需要重新识别编码")
                }
                val oldBook = books.getBook(book.id) ?: error("书籍已不存在")
                check(oldBook.filePath == book.filePath) { "正文已更新，请重新打开编码设置" }
                ensureSourceReadable(oldBook)

                // 零拷贝模式（contentPath == null）：直接流式重扫原文件，零磁盘写入
                if (oldBook.contentPath.isNullOrBlank()) {
                    val sampleBytes = contentAccess.readPrefix(oldBook.filePath, SAMPLE_PREVIEW_BYTES)
                        ?: throw MissingBookSourceException("无法读取原文件")
                    val totalSize = contentAccess.size(oldBook.filePath)
                    val sampleDecoded = TextCodec.decode(
                        bytes = sampleBytes,
                        manual = charsetHint,
                        isPrefix = totalSize > sampleBytes.size
                    )
                    val streamResult = StreamingTxtProcessor.indexRaw(
                        openStream = { contentAccess.openStream(oldBook.filePath) },
                        declaredBytes = totalSize,
                        manualEncoding = charsetHint,
                        maxRawBytes = ImportPipeline.MAX_TXT_BYTES
                    )
                    if (streamResult.totalChars <= 0L || streamResult.slices.isEmpty()) {
                        throw IllegalStateException("重新解码后没有正文内容")
                    }
                    currentCoroutineContext().ensureActive()
                    val indexed = runInTransaction {
                        searchFtsDao?.deleteForBook(book.id)
                        val result = persistChapters(book, streamResult.slices)
                        books.updateIndexInfo(
                            book.id,
                            streamResult.slices.size,
                            streamResult.totalChars,
                            streamResult.ruleId,
                            oldBook.chapterRegex,
                            streamResult.charset
                        )
                        result
                    }
                    return@withContext ReindexResult(
                        encoding = streamResult.charset,
                        source = sampleDecoded.source,
                        chapters = indexed,
                        chars = streamResult.totalChars
                    )
                }

                // 历史旧书兼容路径（已有 content.utf8.txt）
                val bytes = contentAccess.openStream(oldBook.filePath).use { it.readBytes() }
                val decoded = TextCodec.decode(bytes, manual = charsetHint)
                val normalized = TextNormalizer.normalize(decoded.text)
                if (normalized.isBlank()) throw IllegalStateException("重新解码后没有正文内容")
                val target = File(oldBook.contentPath)
                val staging = ContentReplacer.stagingFile(target)
                staging.parentFile?.mkdirs()
                staging.writeText(normalized, Charsets.UTF_8)
                val oldChapters = books.getChapters(book.id)
                try {
                    currentCoroutineContext().ensureActive()
                    val detection = ChapterDetector.detect(normalized)
                    val slices = ChapterSplitter.split(normalized, detection)
                    if (slices.isEmpty()) throw IllegalStateException("无法切分正文")
                    currentCoroutineContext().ensureActive()
                    var indexed = 0
                    ReindexCoordinator(
                        commitNewIndex = {
                            indexed = runInTransaction {
                                searchFtsDao?.deleteForBook(book.id)
                                val result = persistChapters(book, slices)
                                books.updateIndexInfo(
                                    book.id,
                                    slices.size,
                                    normalized.length.toLong(),
                                    detection.rule?.id,
                                    oldBook.chapterRegex,
                                    decoded.charsetName
                                )
                                result
                            }
                        },
                        restorePreviousIndex = {
                            runInTransaction {
                                books.saveChapters(book.id, oldChapters)
                                books.updateIndexInfo(
                                    book.id,
                                    oldBook.totalChapters,
                                    oldBook.totalChars,
                                    oldBook.chapterRuleId,
                                    oldBook.chapterRegex,
                                    oldBook.encoding
                                )
                            }
                        }
                    ).commitAndPublish(staging, target)
                    ReindexResult(decoded.charsetName, decoded.source, indexed, normalized.length.toLong())
                } catch (t: Throwable) {
                    if (t !is ReindexRecoveryException) staging.delete()
                    throw t
                }
            }
        }
    }

    /**
     * 各候选编码的前若干字（仅采样前 64KB，避免大文件整本读入内存）。
     */
    suspend fun previews(book: BookEntity, maxChars: Int = 100): List<Preview> =
        withContext(Dispatchers.IO) {
            if (book.format == BookFormat.EPUB) return@withContext emptyList()
            val bytes = contentAccess.readPrefix(book.filePath, SAMPLE_PREVIEW_BYTES)
                ?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
            val totalSize = contentAccess.size(book.filePath)
            val isPrefix = totalSize > bytes.size
            val auto = TextCodec.decode(bytes, isPrefix = isPrefix)
            val autoPreview = Preview(
                charset = auto.charsetName,
                source = "自动检测（${auto.source}）",
                text = auto.text.take(maxChars).replace('\n', ' ')
            )
            val candidates = listOf("UTF-8", "GB18030", "BIG5", "UTF-16LE", "UTF-16BE")
            val list = ArrayList<Preview>(candidates.size + 1)
            list.add(autoPreview)
            for (name in candidates) {
                val decoded = runCatching { TextCodec.decode(bytes, manual = name, isPrefix = isPrefix) }.getOrNull() ?: continue
                list.add(
                    Preview(
                        charset = decoded.charsetName,
                        source = "手动指定",
                        text = decoded.text.take(maxChars).replace('\n', ' ')
                    )
                )
            }
            list
        }

    /**
     * 重新关联移动/重命名后的原文件：
     * - 自动探测新文件编码（避免跨编码替换文件后乱码）；
     * - 对具备持久权限的 `content://` URI 直接零拷贝引用，对无持久权限的临时 URI 回退保存单份内部副本；
     * - 对已知与未知大小的数据源统一执行 `MAX_TXT_BYTES` 流式上限拦截；
     * - 保留或自动迁移既有阅读进度、书签与划线，并在成功提交后回收旧内部副本。
     */
    suspend fun relinkSource(
        bookId: Long,
        newPath: String,
        newSourceUri: String? = newPath
    ): BookEntity = books.contentLock(bookId).withLock {
        withContext(Dispatchers.IO) {
            val oldBook = books.getBook(bookId) ?: error("书籍已不存在")
            if (!contentAccess.isReadable(newPath)) {
                throw MissingBookSourceException("无法读取所选文件")
            }
            val head = contentAccess.readPrefix(newPath, 4) ?: ByteArray(0)
            if (head.isEmpty()) throw IllegalArgumentException("所选文件内容为空")
            val isZip = head.size == 4 &&
                head[0] == 0x50.toByte() &&
                head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() &&
                head[3] == 0x04.toByte()

            if (oldBook.format == BookFormat.EPUB && !isZip) {
                throw IllegalArgumentException("原书为 EPUB 格式，请选择对应的 EPUB 文件")
            }
            if (oldBook.format == BookFormat.TXT && isZip) {
                throw IllegalArgumentException("原书为 TXT 格式，请选择对应的 TXT 文件")
            }

            val declaredSize = contentAccess.size(newPath)
            if (declaredSize > ImportPipeline.MAX_TXT_BYTES) {
                throw IllegalArgumentException("文件超过 ${ImportPipeline.MAX_TXT_BYTES / 1024 / 1024}MB 上限")
            }

            val directReference = !BookContentAccess.isContentUri(newPath) ||
                contentAccess.canReferenceUriDirectly(newPath)

            var stagedFallbackToken: String? = null
            var committed = false
            try {
                val effectivePath: String
                val verifiedSize: Long
                if (directReference) {
                    effectivePath = newPath
                    verifiedSize = if (declaredSize > 0L) declaredSize else verifyStreamSize(effectivePath)
                } else {
                    val token = UUID.randomUUID().toString()
                    stagedFallbackToken = token
                    val ext = if (isZip) ImportPipeline.EPUB_EXT else ImportPipeline.TXT_EXT
                    val target = paths.originalFile(token, ext)
                    verifiedSize = copyBounded(newPath, target)
                    effectivePath = target.absolutePath
                }

                val updatedBook: BookEntity
                val newChapters: List<ChapterEntity>

                if (oldBook.format == BookFormat.EPUB) {
                    contentAccess.openZip(effectivePath).use { zip ->
                        val info = EpubParser(zip).parse()
                        var totalChars = 0L
                        val chapters = ArrayList<ChapterEntity>(info.spine.size)
                        for (item in info.spine) {
                            val html = zip.readText(item.href, maxBytes = ZipEntryReader.MAX_CHAPTER_BYTES)
                            val plain = html?.let { Html.toPlainText(it) }.orEmpty()
                            totalChars += plain.length
                            chapters.add(
                                ChapterEntity(
                                    bookId = bookId,
                                    index = item.index,
                                    title = info.chapterTitles[item.index] ?: "第 ${item.index + 1} 节",
                                    href = item.href,
                                    charCount = plain.length.toLong()
                                )
                            )
                        }
                        newChapters = chapters
                        updatedBook = oldBook.copy(
                            filePath = effectivePath,
                            contentPath = null,
                            sourceUri = newSourceUri,
                            fileSize = verifiedSize,
                            tocJson = if (info.toc.isEmpty()) null else TocJson.encode(info.toc),
                            totalChapters = chapters.size,
                            totalChars = totalChars
                        )
                    }
                } else {
                    // manualEncoding 传 null，对新关联文件重新探测编码，防止 GBK <-> UTF-8 更换后乱码
                    val streamResult = StreamingTxtProcessor.indexRaw(
                        openStream = { contentAccess.openStream(effectivePath) },
                        declaredBytes = verifiedSize,
                        manualEncoding = null,
                        maxRawBytes = ImportPipeline.MAX_TXT_BYTES
                    )
                    if (streamResult.totalBytes > ImportPipeline.MAX_TXT_BYTES) {
                        throw IllegalArgumentException("文件超过 ${ImportPipeline.MAX_TXT_BYTES / 1024 / 1024}MB 上限")
                    }
                    if (streamResult.slices.isEmpty()) throw IllegalArgumentException("无法切分所选 TXT 正文")
                    newChapters = streamResult.slices.mapIndexed { idx, slice ->
                        ChapterEntity(
                            bookId = bookId,
                            index = idx,
                            title = slice.title,
                            startByte = slice.startByte,
                            endByte = slice.endByte,
                            charCount = slice.charCount.toLong(),
                            titleLineChars = slice.titleLineChars,
                            isVolume = slice.isVolume
                        )
                    }
                    updatedBook = oldBook.copy(
                        filePath = effectivePath,
                        contentPath = null,
                        sourceUri = newSourceUri,
                        fileSize = streamResult.totalBytes.takeIf { it > 0L } ?: verifiedSize,
                        encoding = streamResult.charset,
                        chapterRuleId = streamResult.ruleId,
                        chapterRegex = streamResult.regex,
                        totalChapters = newChapters.size,
                        totalChars = streamResult.totalChars
                    )
                }

                currentCoroutineContext().ensureActive()

                val oldManagedTokens = listOfNotNull(
                    BookContentAccess.managedBookToken(paths, oldBook.filePath),
                    BookContentAccess.managedBookToken(paths, oldBook.contentPath)
                ).distinct()
                val newManagedToken = BookContentAccess.managedBookToken(paths, effectivePath)

                val saved = withContext(NonCancellable) {
                    val result = runInTransaction {
                        val latest = books.getBook(bookId) ?: error("书籍已不存在")
                        val oldChapters = books.getChapters(bookId)
                        val oldProgress = books.getProgress(bookId)
                        val oldBookmarks = bookmarkDao.getAllForBook(bookId).sortedBy { it.id }
                        val oldHighlights = highlightDao.getAllForBook(bookId).sortedBy { it.id }

                        val identicalStructure = oldChapters.size == newChapters.size &&
                            oldChapters.indices.all { idx ->
                                oldChapters[idx].charCount == newChapters[idx].charCount &&
                                    oldChapters[idx].title == newChapters[idx].title
                            }

                        val finalBook = latest.copy(
                            filePath = updatedBook.filePath,
                            contentPath = null,
                            sourceUri = updatedBook.sourceUri,
                            fileSize = updatedBook.fileSize,
                            encoding = updatedBook.encoding,
                            chapterRuleId = updatedBook.chapterRuleId,
                            chapterRegex = updatedBook.chapterRegex,
                            totalChapters = updatedBook.totalChapters,
                            totalChars = updatedBook.totalChars,
                            tocJson = updatedBook.tocJson
                        )

                        searchFtsDao?.deleteForBook(bookId)
                        books.saveChapters(bookId, newChapters)
                        books.updateBook(finalBook)

                        if (!identicalStructure) {
                            val mapped = BookEditionUpdater.map(
                                old = BookEditionUpdater.Records(latest, oldChapters, oldProgress, oldBookmarks, oldHighlights),
                                incoming = finalBook,
                                chapters = newChapters,
                                relation = EditionRelation.UNKNOWN,
                                textProvider = textProvider
                            )
                            if (mapped.progress != null) {
                                books.saveProgress(mapped.progress)
                            } else if (oldProgress != null) {
                                val clampedIdx = oldProgress.chapterIndex.coerceIn(0, (newChapters.size - 1).coerceAtLeast(0))
                                val maxOff = newChapters.getOrNull(clampedIdx)?.charCount?.toInt() ?: 0
                                val clampedOff = oldProgress.charOffset.coerceIn(0, maxOff)
                                books.saveProgress(
                                    oldProgress.copy(
                                        chapterIndex = clampedIdx,
                                        charOffset = clampedOff,
                                        pageIndex = 0,
                                        pageCount = 0,
                                        percent = ProgressMath.percent(
                                            newChapters.map { it.charCount.coerceAtLeast(1L) },
                                            clampedIdx,
                                            clampedOff
                                        )
                                    )
                                )
                            }
                            for (bm in mapped.bookmarks) bookmarkDao.update(bm)
                            for (hl in mapped.highlights) {
                                if (hl.id == 0L) highlightDao.insert(hl) else highlightDao.update(hl)
                            }
                        }
                        finalBook
                    }
                    committed = true
                    result
                }

                oldManagedTokens.filter { it != newManagedToken }.forEach { oldToken ->
                    paths.deleteBookFiles(oldToken, null)
                }
                saved
            } catch (t: Throwable) {
                if (!committed && stagedFallbackToken != null) {
                    withContext(NonCancellable) {
                        paths.deleteBookFiles(stagedFallbackToken, null)
                    }
                }
                throw t
            }
        }
    }

    private fun verifyStreamSize(path: String): Long {
        var counted = 0L
        contentAccess.openStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                counted += n
                if (counted > ImportPipeline.MAX_TXT_BYTES) {
                    throw IllegalArgumentException("文件超过 ${ImportPipeline.MAX_TXT_BYTES / 1024 / 1024}MB 上限")
                }
            }
        }
        if (counted == 0L) throw IllegalArgumentException("所选文件内容为空")
        return counted
    }

    private fun copyBounded(sourcePath: String, target: File): Long {
        target.parentFile?.mkdirs()
        var copied = 0L
        contentAccess.openStream(sourcePath).use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    copied += n
                    if (copied > ImportPipeline.MAX_TXT_BYTES) {
                        runCatching { target.delete() }
                        throw IllegalArgumentException("文件超过 ${ImportPipeline.MAX_TXT_BYTES / 1024 / 1024}MB 上限")
                    }
                    output.write(buf, 0, n)
                }
            }
        }
        if (copied == 0L) {
            runCatching { target.delete() }
            throw IllegalArgumentException("所选文件内容为空")
        }
        return copied
    }

    /** 把切分结果写入章节表并把越界进度夹回有效范围（须在事务内调用）。 */
    private suspend fun persistChapters(book: BookEntity, slices: List<ChapterSlice>): Int {
        val chapters = slices.mapIndexed { i, slice ->
            ChapterEntity(
                bookId = book.id,
                index = i,
                title = slice.title,
                startByte = slice.startByte,
                endByte = slice.endByte,
                charCount = slice.charCount.toLong(),
                titleLineChars = slice.titleLineChars,
                isVolume = slice.isVolume
            )
        }
        books.saveChapters(book.id, chapters)
        clampProgress(book.id, chapters)
        return chapters.size
    }

    private suspend fun clampProgress(bookId: Long, chapters: List<ChapterEntity>) {
        val progress = books.getProgress(bookId) ?: return
        val index = if (chapters.getOrNull(progress.chapterIndex) != null) {
            progress.chapterIndex
        } else {
            (chapters.size - 1).coerceAtLeast(0)
        }
        val maxOffset = chapters.getOrNull(index)?.charCount?.toInt() ?: 0
        val offset = progress.charOffset.coerceIn(0, maxOffset)
        if (index == progress.chapterIndex && offset == progress.charOffset) return
        books.saveProgress(
            progress.copy(chapterIndex = index, charOffset = offset, pageIndex = 0)
        )
    }

    companion object {
        private const val SAMPLE_PREVIEW_BYTES = 64 * 1024
    }
}

/**
 * 正文发布的文件操作：每次操作独占临时文件，发布采用"直改 → 备份-替换-回滚"两级策略。
 * 保留供历史旧书 `content.utf8.txt` 重新识别路径及回归单测使用。
 */
internal object ContentReplacer {

    fun stagingFile(target: File): File =
        File(target.parentFile, target.name + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp")

    fun publish(staging: File, target: File, rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }): Boolean {
        if (rename(staging, target)) return true
        val backup = File(target.parentFile, target.name + ".bak")
        if (backup.exists()) backup.delete()
        if (target.exists() && !rename(target, backup)) return false
        if (rename(staging, target)) {
            backup.delete()
            return true
        }
        if (backup.exists()) rename(backup, target)
        return false
    }
}

internal class ReindexRecoveryException(message: String, cause: Throwable) : IllegalStateException(message, cause)

internal class ReindexCoordinator(
    private val commitNewIndex: suspend () -> Unit,
    private val restorePreviousIndex: suspend () -> Unit
) {
    suspend fun commitAndPublish(
        staging: File,
        target: File,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
    ) {
        commitNewIndex()
        if (ContentReplacer.publish(staging, target, rename)) return
        try {
            withContext(NonCancellable) { restorePreviousIndex() }
        } catch (restoreError: Throwable) {
            throw ReindexRecoveryException(
                "正文替换失败且恢复旧目录失败；新正文保留于 ${staging.name}，原正文保留于 ${target.path}",
                restoreError
            )
        }
        runCatching { staging.delete() }
        throw IllegalStateException("正文文件替换失败，已恢复原正文与原目录，请重试重新识别")
    }
}
