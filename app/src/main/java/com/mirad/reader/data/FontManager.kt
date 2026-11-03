package com.mirad.reader.data

import android.content.Context
import android.util.Log
import com.mirad.reader.core.model.ReadingFont
import com.mirad.reader.core.model.ReadingFonts
import java.io.File
import java.util.Locale

/**
 * 字体文件管理：内置（assets/fonts）+ 历史已导入（filesDir/fonts）。
 * 不再接收新字体导入，保留旧文件的读取和删除。
 */
class FontManager(
    private val context: Context,
    private val paths: AppPaths
) {

    private val supportedExtensions = setOf("ttf", "otf", "ttc", "otc", "woff", "woff2")

    /** 可用字体：内置在前，导入在后。 */
    fun available(): List<ReadingFont> = bundled() + imported()

    /** assets/fonts 下的字体。 */
    fun bundled(): List<ReadingFont> {
        val names = runCatching { context.assets.list(BUNDLED_DIR)?.toList() }.getOrNull().orEmpty()
        return names
            .filter { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in supportedExtensions }
            .sorted()
            .map { name ->
                ReadingFont(
                    label = ReadingFonts.bundledLabel(name),
                    assetName = "$BUNDLED_DIR/$name",
                    note = ReadingFonts.bundledNote(name)
                )
            }
    }

    /** 用户导入到 filesDir/fonts 的字体。 */
    fun imported(): List<ReadingFont> {
        val dir = paths.fontsDir
        val files = dir.listFiles()?.filter {
            it.isFile && it.extension.lowercase(Locale.ROOT) in supportedExtensions &&
                !ReadingFonts.isRetiredPreviewFont(it.name)
        }.orEmpty()
        return files.sortedBy { it.name.lowercase(Locale.ROOT) }.map { file ->
            ReadingFont(
                label = file.nameWithoutExtension,
                filePath = file.absolutePath,
                note = "已导入 · ${formatSize(file.length())}"
            )
        }
    }

    fun delete(font: ReadingFont): Boolean {
        val path = font.filePath ?: return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    companion object {
        const val BUNDLED_DIR = "fonts"
    }
}

/** 应用启动时在 IO 线程清理指定的历史测试字体，其他文件及子目录保持原样。 */
internal fun removeRetiredPreviewFonts(directory: File) {
    directory.listFiles().orEmpty()
        .filter { it.isFile && ReadingFonts.isRetiredPreviewFont(it.name) }
        .forEach { file ->
            if (!file.delete()) Log.w("FontManager", "历史测试字体删除失败：${file.name}")
        }
}
