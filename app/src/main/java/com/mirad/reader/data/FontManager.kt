package com.mirad.reader.data

import android.content.Context
import android.net.Uri
import com.mirad.reader.core.model.ReadingFont
import com.mirad.reader.core.model.ReadingFonts
import java.io.File
import java.util.Locale

/**
 * 字体文件管理：内置（assets/fonts）+ 用户导入（filesDir/fonts）。
 *
 * 中文字体文件很大，不便全部内置，因此把「导入自己的字体」作为一等功能。
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
            it.isFile && it.extension.lowercase(Locale.ROOT) in supportedExtensions
        }.orEmpty()
        return files.sortedBy { it.name.lowercase(Locale.ROOT) }.map { file ->
            ReadingFont(
                label = file.nameWithoutExtension,
                filePath = file.absolutePath,
                note = "已导入 · ${formatSize(file.length())}"
            )
        }
    }

    /** 把 SAF 选中的字体复制到私有目录，返回可用的 [ReadingFont]。 */
    fun import(uri: Uri): ReadingFont? {
        val displayName = queryDisplayName(uri) ?: "font-${System.currentTimeMillis()}.ttf"
        val extension = displayName.substringAfterLast('.', "ttf").lowercase(Locale.ROOT)
        if (extension !in supportedExtensions) return null
        val safeName = sanitize(displayName.substringBeforeLast('.')) + "." + extension
        val target = File(paths.fontsDir, uniqueName(safeName))
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!ok || target.length() == 0L) {
            runCatching { target.delete() }
            return null
        }
        return ReadingFont(
            label = target.nameWithoutExtension,
            filePath = target.absolutePath,
            note = "已导入 · ${formatSize(target.length())}"
        )
    }

    fun delete(font: ReadingFont): Boolean {
        val path = font.filePath ?: return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }

    private fun uniqueName(name: String): String {
        var candidate = name
        var index = 1
        while (File(paths.fontsDir, candidate).exists()) {
            candidate = name.substringBeforeLast('.') + "-$index." + name.substringAfterLast('.')
            index++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return cleaned.ifBlank { "font" }.take(48)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    companion object {
        const val BUNDLED_DIR = "fonts"
    }
}
