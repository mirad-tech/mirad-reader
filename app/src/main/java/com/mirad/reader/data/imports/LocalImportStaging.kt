package com.mirad.reader.data.imports

import android.util.Log
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.io.BookContentAccess
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 标记只属于本地导入。重启后以数据库仍引用的文件为准，收尾被打断的清理。
 * 严格限定仅清理 `paths.booksDir` 下的内部托管目录与 `paths.coversDir` 下的封面，绝不触碰外部用户原文件。
 */
internal class LocalImportStaging(private val paths: AppPaths) {
    private val markers get() = File(paths.root, "local-import-staging")

    fun isMarked(token: String): Boolean = File(markers, token).isFile

    fun mark(token: String) {
        markers.mkdirs()
        File(markers, token).writeText("")
    }

    fun beforeReplacement(token: String, old: BookEntity) {
        val managedToken = BookContentAccess.managedBookToken(paths, old.filePath)
            ?: BookContentAccess.managedBookToken(paths, old.contentPath)
        if (managedToken != null) {
            markers.mkdirs()
            File(markers, token).writeText(managedToken)
        }
    }

    fun recover(books: List<BookEntity>) {
        val referencedTokens = referencedManagedTokens(books)
        val covers = books.mapNotNull { it.coverPath }.map(::File).toSet()
        markers.listFiles().orEmpty().filter { it.isFile && validToken(it.name) }.forEach { marker ->
            clean(marker, referencedTokens, covers)
        }
    }

    fun finish(token: String, books: List<BookEntity>) {
        val referencedTokens = referencedManagedTokens(books)
        val covers = books.mapNotNull { it.coverPath }.map(::File).toSet()
        clean(File(markers, token), referencedTokens, covers)
    }

    private fun referencedManagedTokens(books: List<BookEntity>): Set<String> =
        books.flatMap {
            listOfNotNull(
                BookContentAccess.managedBookToken(paths, it.filePath),
                BookContentAccess.managedBookToken(paths, it.contentPath)
            )
        }.toSet()

    private fun clean(marker: File, referencedTokens: Set<String>, covers: Set<File>) {
        if (!marker.isFile) return
        try {
            val previous = marker.readText().takeIf(::validToken)
            val tokens = listOfNotNull(marker.name, previous).distinct()
            for (token in tokens) {
                val directory = File(paths.booksDir, token)
                val cover = paths.coverFile(token)
                if (token !in referencedTokens && directory.exists() && !directory.deleteRecursively()) {
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
