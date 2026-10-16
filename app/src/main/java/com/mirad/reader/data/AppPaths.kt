package com.mirad.reader.data

import android.content.Context
import java.io.File

/** 应用私有目录布局。 */
class AppPaths(private val context: Context) {

    val root: File get() = context.filesDir

    val booksDir: File get() = ensure(File(root, "books"))

    val coversDir: File get() = ensure(File(root, "covers"))

    val fontsDir: File get() = ensure(File(root, "fonts"))

    val backgroundsDir: File get() = ensure(File(root, "backgrounds"))

    /** 只读目录，调试用样书扫描。 */
    val samplesDir: File get() = ensure(File(context.getExternalFilesDir(null) ?: root, "samples"))

    fun bookDir(token: String): File = ensure(File(booksDir, token))

    fun originalFile(token: String, extension: String): File = File(bookDir(token), "original.$extension")

    fun contentFile(token: String): File = File(bookDir(token), "content.utf8.txt")

    fun coverFile(token: String): File = File(coversDir, "$token.jpg")

    fun deleteBookFiles(token: String, coverPath: String?) {
        runCatching { File(booksDir, token).deleteRecursively() }
        coverPath?.let { runCatching { File(it).delete() } }
    }

    private fun ensure(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
