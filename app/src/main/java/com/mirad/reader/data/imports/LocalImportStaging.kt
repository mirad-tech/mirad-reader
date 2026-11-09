package com.mirad.reader.data.imports

import android.util.Log
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import java.io.File
import java.io.IOException
import java.util.UUID

/** 标记只属于本地导入。重启后以数据库仍引用的文件为准，收尾被打断的清理。 */
internal class LocalImportStaging(private val paths: AppPaths) {
    private val markers get() = File(paths.root, "local-import-staging")

    fun isMarked(token: String): Boolean = File(markers, token).isFile

    fun mark(token: String) {
        markers.mkdirs()
        File(markers, token).writeText("")
    }

    fun beforeReplacement(token: String, old: BookEntity) {
        val directory = File(old.filePath).parentFile ?: return
        if (directory.canonicalFile.parentFile == paths.booksDir.canonicalFile && validToken(directory.name)) {
            File(markers, token).writeText(directory.name)
        }
    }

    fun recover(books: List<BookEntity>) {
        val bodyDirectories = books.flatMap { listOfNotNull(it.filePath, it.contentPath) }
            .mapNotNull { File(it).parentFile }.toSet()
        val covers = books.mapNotNull { it.coverPath }.map(::File).toSet()
        markers.listFiles().orEmpty().filter { it.isFile && validToken(it.name) }.forEach { marker ->
            clean(marker, bodyDirectories, covers)
        }
    }

    fun finish(token: String, books: List<BookEntity>) {
        val bodyDirectories = books.flatMap { listOfNotNull(it.filePath, it.contentPath) }
            .mapNotNull { File(it).parentFile }.toSet()
        val covers = books.mapNotNull { it.coverPath }.map(::File).toSet()
        clean(File(markers, token), bodyDirectories, covers)
    }

    private fun clean(marker: File, bodyDirectories: Set<File>, covers: Set<File>) {
        if (!marker.isFile) return
        try {
            val previous = marker.readText().takeIf(::validToken)
            val tokens = listOfNotNull(marker.name, previous)
            for (token in tokens) {
                val directory = File(paths.booksDir, token)
                val cover = paths.coverFile(token)
                if (directory !in bodyDirectories && directory.exists() && !directory.deleteRecursively()) {
                    throw IOException("无法清理导入正文")
                }
                if (cover !in covers && cover.exists() && !cover.delete()) throw IOException("无法清理导入封面")
            }
            if (!marker.delete()) throw IOException("无法清理导入标记")
        } catch (e: IOException) {
            Log.w("LocalImportStaging", "下次启动时重试导入清理", e)
        }
    }

    private fun validToken(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
