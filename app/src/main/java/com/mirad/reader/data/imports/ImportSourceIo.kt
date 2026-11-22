package com.mirad.reader.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mirad.reader.data.imports.ImportPipeline.Companion.MAX_TXT_BYTES
import com.mirad.reader.data.io.BookContentAccess
import java.io.File
import java.io.InputStream
/** 读取导入来源：按文件头识别格式、获取并校验大小，以及复制到应用目录。 */
internal class ImportSourceIo(
    private val context: Context,
    private val contentAccess: BookContentAccess
) {
    fun guessExtension(source: ImportSource): String {
        val isZipHeader = runCatching {
            openInput(source).use { input ->
                val head = ByteArray(4)
                val read = input.read(head)
                read == 4 &&
                    head[0] == 0x50.toByte() &&
                    head[1] == 0x4B.toByte() &&
                    head[2] == 0x03.toByte() &&
                    head[3] == 0x04.toByte()
            }
        }.getOrDefault(false)

        return ImportPipeline.detectExtension(source.displayName, isZipHeader)
    }

    fun verifyReadableSize(path: String, declaredSize: Long): Long {
        val known = if (declaredSize > 0L) declaredSize else contentAccess.size(path)
        if (known > MAX_TXT_BYTES) {
            throw ImportException("文件超过 ${MAX_TXT_BYTES / 1024 / 1024}MB 上限，已中止导入")
        }
        if (known > 0L) {
            if (!contentAccess.isReadable(path)) throw ImportException("无法读取所选文件")
            return known
        }
        var counted = 0L
        contentAccess.openStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                counted += n
                if (counted > MAX_TXT_BYTES) {
                    throw ImportException("文件超过 ${MAX_TXT_BYTES / 1024 / 1024}MB 上限，已中止导入")
                }
            }
        }
        if (counted == 0L) throw ImportException("文件内容为空")
        return counted
    }

    fun copyTo(source: ImportSource, target: File, onProgress: (Float) -> Unit): Long {
        target.parentFile?.mkdirs()
        val total = sizeOf(source)
        var copied = 0L
        openInput(source).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (copied > MAX_TXT_BYTES) {
                        runCatching { target.delete() }
                        throw ImportException("文件超过 ${MAX_TXT_BYTES / 1024 / 1024}MB 上限，已中止导入")
                    }
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        if (copied == 0L) throw ImportException("无法读取文件内容")
        return copied
    }

    private fun openInput(source: ImportSource): InputStream = when (source) {
        is ImportSource.FromUri -> {
            val uriStr = source.uriString
            if (contentAccess.isReadable(uriStr)) contentAccess.openStream(uriStr)
            else source.uri?.let { context.contentResolver.openInputStream(it) } ?: throw ImportException("无法打开所选文件")
        }
        is ImportSource.FromFile -> source.file.inputStream()
    }

    fun sizeOf(source: ImportSource): Long = when (source) {
        is ImportSource.FromFile -> source.file.length()
        is ImportSource.FromUri -> {
            val viaAccess = contentAccess.size(source.uriString)
            if (viaAccess >= 0L) viaAccess else source.uri?.let { querySize(it) } ?: -1L
        }
    }

    private fun querySize(uri: Uri): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) return afd.length
            }
        }
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1L
    }
}
