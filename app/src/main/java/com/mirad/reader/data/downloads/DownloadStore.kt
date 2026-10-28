package com.mirad.reader.data.downloads

import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.core.sources.BookDownloadTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 每章独立落盘；原子替换保证进程退出后不会把半章当作已完成。 */
internal class DownloadStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Pair<List<BookDownloadTask>, List<String>> {
        if (!root.exists()) return emptyList<BookDownloadTask>() to emptyList()
        val errors = mutableListOf<String>()
        val tasks = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val file = File(dir, "task.json")
            if (!file.exists()) return@mapNotNull null
            runCatching {
                require(UUID.fromString(dir.name).toString() == dir.name) { "任务编号无效" }
                require(file.length() <= MAX_MANIFEST_BYTES) { "任务清单过大" }
                json.decodeFromString<BookDownloadTask>(file.readText()).also {
                    require(it.id == dir.name && it.catalog.chapters.isNotEmpty()) { "任务清单不完整" }
                    require(it.completedChapters.all { index -> index in it.catalog.chapters.indices }) { "任务进度无效" }
                }
            }.getOrElse {
                errors += "${dir.name.take(8)}：${it.message ?: "任务读取失败"}"
                null
            }
        }.sortedBy { it.createdAt }
        return tasks to errors
    }

    fun save(task: BookDownloadTask) {
        val bytes = json.encodeToString(task).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "目录或书源规则过大，无法保存下载任务" }
        writeAtomic(File(directory(task.id), "task.json"), bytes)
    }

    fun completed(task: BookDownloadTask): Set<Int> = task.catalog.chapters.indices.filterTo(linkedSetOf()) {
        chapterFile(task.id, it).let { file -> file.isFile && file.length() in 1..ZipEntryReader.MAX_CHAPTER_BYTES.toLong() }
    }

    fun writeChapter(id: String, index: Int, text: String) {
        require(text.isNotBlank()) { "章节正文为空" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ZipEntryReader.MAX_CHAPTER_BYTES) { "章节超过 8MB 上限" }
        writeAtomic(chapterFile(id, index), bytes)
    }

    fun readChapter(id: String, index: Int): String {
        val file = chapterFile(id, index)
        require(file.isFile && file.length() in 1..ZipEntryReader.MAX_CHAPTER_BYTES.toLong()) { "章节缓存缺失或超限" }
        return file.readText(Charsets.UTF_8).also { require(it.isNotBlank()) { "章节缓存为空" } }
    }

    fun epubFile(id: String) = File(directory(id), "book.epub")

    fun clearPayload(id: String) {
        // 只清本任务的平面缓存文件，保留任务清单，不递归触及书架目录。
        directory(id).listFiles().orEmpty().filter {
            it.isFile && (it.name.matches(Regex("chapter-\\d+\\.txt(\\.new)?")) ||
                it.name == "book.epub" || it.name == "book.epub.new")
        }.forEach { check(it.delete() || !it.exists()) { "临时文件清理失败" } }
    }

    private fun chapterFile(id: String, index: Int): File {
        require(index >= 0)
        return File(directory(id), "chapter-$index.txt")
    }

    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "任务编号无效" }
        return File(root, id).apply { check(isDirectory || mkdirs()) { "无法创建下载目录" } }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 24 * 1024 * 1024

        internal fun writeAtomic(file: File, bytes: ByteArray) {
            file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建保存目录" } }
            val pending = File(file.parentFile, file.name + ".new")
            try {
                FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                pending.delete()
            }
        }
    }
}
