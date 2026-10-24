package com.mirad.reader.data.repo

import androidx.room.withTransaction
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.core.model.ChapterTextMap
import com.mirad.reader.core.text.ChapterSlice
import java.security.MessageDigest
import com.mirad.reader.core.text.ChapterDetector
import com.mirad.reader.core.text.ChapterSplitter
import com.mirad.reader.core.text.TextCodec
import com.mirad.reader.core.text.TextNormalizer
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 已导入书籍的维护操作。
 *
 * 存在的原因：TXT 正文在导入时就解码成 UTF-8 写进了 `content.utf8.txt`，
 * 所以解码器后来修好了，**已经导入的书仍然是乱的**，必须用原始文件重新解码。
 * （EPUB 不在此列：正文是显示时从 zip 现读的，修解码器即刻生效。）
 */
class BookMaintenance(
    private val books: BookRepository,
    private val paths: AppPaths,
    private val database: AppDatabase
) {

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
        val bytes = normalizedFile(current).readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        check(text.isNotBlank()) { "没有可识别的正文" }
        val detection = ChapterDetector.detect(text)
        ChapterPreview(ChapterSplitter.split(text, detection), detection.marks.isEmpty(), detection.reason,
            book.id, digest(bytes), books.getChapters(book.id), detection.rule?.id)
    }

    suspend fun applyChapters(book: BookEntity, preview: ChapterPreview): Int = withContext(Dispatchers.IO) {
        require(book.id == preview.bookId && book.format != BookFormat.EPUB)
        database.withTransaction {
            val current = books.getBook(book.id) ?: error("书籍已不存在")
            val bytes = normalizedFile(current).readBytes()
            check(digest(bytes) == preview.digest && books.getChapters(book.id) == preview.oldChapters) {
                "正文或目录已变化，请重新预览"
            }
            val text = bytes.toString(Charsets.UTF_8)
            val slices = preview.slices
            check(slices.isNotEmpty()) { "无法切分正文" }
            val newMaps = slices.map { slice ->
                ChapterTextMap(text.substring(slice.startChar, slice.endChar), slice.title,
                    slice.titleLineChars, slice.isVolume, slice.startChar)
            }
            var sourceStart = 0
            var previousEnd = 0L
            var validPrefix = true
            val oldMaps = preview.oldChapters.map { chapter ->
                // Refuse malformed historical byte indexes rather than fabricate an anchor.
                val valid = validPrefix && chapter.startByte == previousEnd && chapter.endByte >= chapter.startByte && chapter.endByte <= bytes.size
                previousEnd = chapter.endByte
                validPrefix = valid
                if (!valid) null else {
                    val body = bytes.copyOfRange(chapter.startByte.toInt(), chapter.endByte.toInt()).toString(Charsets.UTF_8)
                    validPrefix = body.toByteArray(Charsets.UTF_8).contentEquals(bytes.copyOfRange(chapter.startByte.toInt(), chapter.endByte.toInt()))
                    val map = ChapterTextMap(body, chapter.title, chapter.titleLineChars, chapter.isVolume, sourceStart)
                    sourceStart += body.length
                    map.takeIf { validPrefix }
                }
            }
            fun point(chapter: Int, offset: Int): Pair<Int, Int>? {
                val source = oldMaps.getOrNull(chapter)?.sourceAt(offset) ?: return null
                return newMaps.mapIndexedNotNull { index, map -> map.domAt(source)?.let { index to it } }.lastOrNull()
            }
            val bookmarks = database.bookmarkDao().getAllForBook(book.id)
            val highlights = database.highlightDao().getAllForBook(book.id)
            val progress = books.getProgress(book.id)
            val chapters = slices.mapIndexed { i, slice ->
                ChapterEntity(bookId = book.id, index = i, title = slice.title,
                    startByte = slice.startByte, endByte = slice.endByte, charCount = slice.charCount.toLong(),
                    titleLineChars = slice.titleLineChars, isVolume = slice.isVolume)
            }
            database.searchFtsDao().deleteForBook(book.id)
            books.saveChapters(book.id, chapters)
            books.updateIndexInfo(book.id, chapters.size, text.length.toLong(), preview.ruleId, null, current.encoding)
            bookmarks.forEach { bookmark ->
                val mapped = point(bookmark.chapterIndex, bookmark.charOffset)
                database.bookmarkDao().update(if (mapped == null) bookmark.copy(chapterIndex = -1)
                    else bookmark.copy(chapterIndex = mapped.first, charOffset = mapped.second, chapterTitle = chapters[mapped.first].title))
            }
            highlights.forEach { highlight ->
                val ranges = oldMaps.getOrNull(highlight.chapterIndex)?.migrateRange(
                    highlight.startOffset, highlight.endOffset, highlight.text, newMaps)
                if (ranges == null) database.highlightDao().update(highlight.copy(chapterIndex = -1))
                else ranges.forEachIndexed { i, range ->
                    val mapped = highlight.copy(id = if (i == 0) highlight.id else 0,
                        chapterIndex = range.chapter, startOffset = range.start, endOffset = range.end, text = range.text)
                    if (i == 0) database.highlightDao().update(mapped) else database.highlightDao().insert(mapped)
                }
            }
            if (progress != null) {
                val mapped = point(progress.chapterIndex, progress.charOffset)
                    ?: oldMaps.getOrNull(progress.chapterIndex)?.let { old ->
                        val index = slices.indexOfLast { it.startChar <= old.sourceStart }.coerceAtLeast(0)
                        index to 0
                    } ?: (0 to 0)
                books.saveProgress(progress.copy(chapterIndex = mapped.first, charOffset = mapped.second, pageIndex = 0, pageCount = 0))
            }
            chapters.size
        }
    }

    private fun normalizedFile(book: BookEntity): File {
        val file = File(book.contentPath ?: error("找不到归一化正文，请重新导入"))
        check(file.isFile) { "找不到归一化正文，请重新导入" }
        return file
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

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

    /** 用原始文件重新解码并重建章节索引。同一本书串行执行：发布、失败恢复与清理同临界区，不影响其他书。 */
    suspend fun redetect(book: BookEntity, charsetHint: String? = null): ReindexResult {
        val lock = redetectLocks.computeIfAbsent(book.id) { Mutex() }
        return lock.withLock {
            withContext(Dispatchers.IO) {
                val bytes = rawBytes(book)
                val decoded = TextCodec.decode(bytes, manual = charsetHint)
                val normalized = TextNormalizer.normalize(decoded.text)
                if (normalized.isBlank()) throw IllegalStateException("重新解码后没有正文内容")
                val target = contentFile(book)
                // 每次操作独占临时文件：被取消的旧任务（取消只在挂起点生效，秒级的同步分章
                // 计算会把清理推迟到计算之后）只能删掉自己的输出，动不了并发新任务的文件。
                val staging = ContentReplacer.stagingFile(target)
                staging.parentFile?.mkdirs()
                staging.writeText(normalized, Charsets.UTF_8)
                // 旧状态快照：正文替换失败时把章节与书籍字段恢复到本次操作之前，
                // 保证"完整旧状态或完整新状态"，绝不留下新索引配旧正文的混合状态。
                val oldBook = books.getBook(book.id) ?: book
                val oldChapters = books.getChapters(book.id)
                try {
                    // 耗时计算结束后、发布前检查取消：已取消的任务不得继续提交结果。
                    currentCoroutineContext().ensureActive()
                    // 检测与切分是纯 CPU 计算（大书可达秒级），放在事务外执行，避免长时间持有数据库写锁。
                    val detection = ChapterDetector.detect(normalized)
                    val slices = ChapterSplitter.split(normalized, detection)
                    if (slices.isEmpty()) throw IllegalStateException("无法切分正文")
                    currentCoroutineContext().ensureActive()
                    var indexed = 0
                    ReindexCoordinator(
                        commitNewIndex = {
                            indexed = database.withTransaction {
                                database.searchFtsDao().deleteForBook(book.id)
                                val result = persistChapters(book, slices)
                                books.updateBook(
                                    book.copy(
                                        encoding = decoded.charsetName,
                                        chapterRuleId = detection.rule?.id,
                                        totalChapters = slices.size,
                                        totalChars = normalized.length.toLong()
                                    )
                                )
                                result
                            }
                        },
                        restorePreviousIndex = {
                            database.withTransaction {
                                books.saveChapters(book.id, oldChapters)
                                books.updateBook(oldBook)
                            }
                        }
                    ).commitAndPublish(staging, target)
                    ReindexResult(decoded.charsetName, decoded.source, indexed, normalized.length.toLong())
                } catch (t: Throwable) {
                    // 取消保持取消语义；只清理本次操作独占的临时文件（DB 事务已随异常回滚）。
                    // 恢复失败的例外：staging 与原正文是诊断与补救现场，不随清理删除。
                    if (t !is ReindexRecoveryException) staging.delete()
                    throw t
                }
            }
        }
    }

    private val redetectLocks = ConcurrentHashMap<Long, Mutex>()

    /**
     * 各候选编码的前若干字。用于「编码」对话框：选中即预览，用户一眼就能看出哪个是对的。
     */
    suspend fun previews(book: BookEntity, maxChars: Int = 100): List<Preview> =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { rawBytes(book) }.getOrNull() ?: return@withContext emptyList()
            val auto = TextCodec.decode(bytes)
            val autoPreview = Preview(
                charset = auto.charsetName,
                source = "自动检测（${auto.source}）",
                text = auto.text.take(maxChars).replace('\n', ' ')
            )
            val candidates = listOf("UTF-8", "GB18030", "BIG5", "UTF-16LE", "UTF-16BE")
            val list = ArrayList<Preview>(candidates.size + 1)
            list.add(autoPreview)
            for (name in candidates) {
                val decoded = runCatching { TextCodec.decode(bytes, manual = name) }.getOrNull() ?: continue
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

    private fun rawBytes(book: BookEntity): ByteArray {
        if (book.format == BookFormat.EPUB) {
            // EPUB 是显示时现读现解码，没有需要重写的正文文件
            throw IllegalStateException("EPUB 不需要重新识别编码")
        }
        val file = File(book.filePath)
        if (!file.exists()) throw IllegalStateException("找不到原始文件，请重新导入")
        return file.readBytes()
    }

    private fun contentFile(book: BookEntity): File = paths.contentFile(books.tokenOf(book))

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

    /**
     * 重新分章后章节数与各章长度都会变，把越界的阅读进度夹回有效范围。
     * 注意也要夹 `charOffset`：章节还在但变短了的话，旧偏移会落到正文之外。
     */
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
}

/**
 * 正文发布的文件操作：每次操作独占临时文件，发布采用"直改 → 备份-替换-回滚"两级策略。
 *
 * 纯 JVM 文件逻辑（不依赖 Android），rename 可注入以便单测模拟失败序列；
 * 任何失败路径下原正文都保持在 target 或 .bak 中可读，staging 永不被删除。
 */
internal object ContentReplacer {

    /** 每次操作独占的临时文件名（随机段）：被取消的旧任务清理时不可能删到并发新任务的输出。 */
    fun stagingFile(target: File): File =
        File(target.parentFile, target.name + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp")

    /**
     * 把 staging 发布为 target。返回 false 表示发布失败：此时原正文已恢复到 target
     * （或保留在 .bak，恢复 rename 也失败的最坏情形），staging 完整保留供重试。
     */
    fun publish(staging: File, target: File, rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }): Boolean {
        if (rename(staging, target)) return true
        val backup = File(target.parentFile, target.name + ".bak")
        // 上一次失败留下的旧备份先清掉（同书操作已互斥，不存在并发冲突）
        if (backup.exists()) backup.delete()
        if (target.exists() && !rename(target, backup)) return false
        if (rename(staging, target)) {
            backup.delete()
            return true
        }
        // 新正文没上位：立即把原正文放回去，保证 target 可读
        if (backup.exists()) rename(backup, target)
        return false
    }
}

/**
 * 正文替换失败且恢复旧目录也失败时抛出：staging 与原正文是仅存的补救现场，
 * 最外层异常清理（redetect 的 catch）不得删除它们。
 */
internal class ReindexRecoveryException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * 重新识别的发布协调器：提交新索引 → 原子替换正文 → 失败恢复一致旧状态。
 * 从 redetect 提取的极小协调边界，生产与测试共用同一段编排。
 *
 * 不变量：
 * - 正常返回 = 完整新状态（新索引 + 新正文）；
 * - [commitNewIndex] 抛出（含取消）= 数据库未提交，完整旧状态；staging 清理由调用方负责；
 * - 正文替换失败 = 以 NonCancellable 恢复旧索引后抛出，完整旧状态；staging 已清理；
 * - 恢复也失败 = 抛 [ReindexRecoveryException]，staging 与原正文全部保留待人工/重试补救。
 */
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
            // 补偿性 DB 写入按项目纪律包 NonCancellable：发布失败的恢复不能被取消打断，
            // 否则会留下"新索引配旧正文"的混合状态。
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
