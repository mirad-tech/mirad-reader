package com.mirad.reader.data

import android.content.Context
import com.mirad.reader.data.io.BookContentAccess
import java.io.File

/** 应用私有目录布局。 */
class AppPaths(private val context: Context) {

    val root: File get() = context.filesDir

    val booksDir: File get() = ensure(File(root, "books"))

    val coversDir: File get() = ensure(File(root, "covers"))

    val fontsDir: File get() = ensure(File(root, "fonts"))

    val backgroundsDir: File get() = ensure(File(root, "backgrounds"))

    fun bookDir(token: String): File = ensure(File(booksDir, token))

    fun originalFile(token: String, extension: String): File = File(bookDir(token), "original.$extension")

    fun contentFile(token: String): File = File(bookDir(token), "content.utf8.txt")

    fun coverFile(token: String): File = File(coversDir, "$token.jpg")

    /**
     * 清理应用内部书籍目录与封面缩略图：
     * [token] 为空或不属于 `booksDir` 下的有效内部子目录时不删除任何正文文件，确保绝不触碰外部原文件。
     */
    fun deleteBookFiles(token: String?, coverPath: String?) {
        if (!token.isNullOrBlank()) {
            runCatching {
                val target = File(booksDir, token).canonicalFile
                if (target.parentFile == booksDir.canonicalFile) {
                    target.deleteRecursively()
                }
            }
        }
        coverPath?.let { path ->
            runCatching {
                val cover = File(path).canonicalFile
                if (cover.parentFile == coversDir.canonicalFile) {
                    cover.delete()
                }
            }
        }
    }

    fun deleteManagedBookStorage(filePath: String?, contentPath: String?, coverPath: String?) {
        val tokens = listOfNotNull(
            BookContentAccess.managedBookToken(this, filePath),
            BookContentAccess.managedBookToken(this, contentPath)
        ).distinct()
        if (tokens.isEmpty()) {
            deleteBookFiles(null, coverPath)
        } else {
            tokens.forEach { token -> deleteBookFiles(token, null) }
            deleteBookFiles(null, coverPath)
        }
    }

    private fun ensure(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
