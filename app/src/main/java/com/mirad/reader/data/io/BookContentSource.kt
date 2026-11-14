package com.mirad.reader.data.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.mirad.reader.core.epub.ZipEntryReader
import com.mirad.reader.data.AppPaths
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID

/** 原文件已移动、被删除或读取权限失效时抛出，供阅读器与书架区分引导「重新关联文件」。 */
class MissingBookSourceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 书籍底层数据源统一访问层：
 * 屏蔽本地 [File]（内部单份副本、历史旧书、JVM 单测）与 Android SAF 持久 `content://` URI 的差异，
 * 支持零拷贝区间随机读取与无解压落盘的 EPUB 随机读取。
 */
class BookContentAccess internal constructor(
    private val context: Context? = null,
    private val uriBackend: UriBackend? = context?.let(::AndroidUriBackend)
) {
    constructor(context: Context?) : this(context, context?.let(::AndroidUriBackend))
    constructor() : this(null, null)

    internal interface UriBackend {
        fun canReferenceDirectly(uriString: String): Boolean
        fun isReadable(uriString: String): Boolean
        fun size(uriString: String): Long
        fun lastModified(uriString: String): Long?
        fun openStream(uriString: String): InputStream
        fun readRange(uriString: String, startByte: Long, length: Int): ByteArray?
        fun openZip(uriString: String): ZipEntryReader
    }

    fun canReferenceUriDirectly(uri: Uri): Boolean =
        canReferenceUriDirectly(uri.toString())

    fun canReferenceUriDirectly(uriString: String): Boolean {
        if (!isContentUri(uriString)) return false
        return uriBackend?.canReferenceDirectly(uriString) == true
    }

    fun isReadable(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (isContentUri(path)) return uriBackend?.isReadable(path) == true
        val file = resolveLocalFile(path) ?: return false
        return file.isFile && file.canRead()
    }

    fun size(path: String): Long {
        if (isContentUri(path)) return uriBackend?.size(path) ?: -1L
        val file = resolveLocalFile(path) ?: return -1L
        return if (file.isFile) file.length() else -1L
    }

    fun lastModified(path: String?): Long? {
        if (path.isNullOrBlank()) return null
        if (isContentUri(path)) return uriBackend?.lastModified(path)
        val file = resolveLocalFile(path) ?: return null
        return if (file.isFile) file.lastModified() else null
    }

    fun openStream(path: String): InputStream {
        if (isContentUri(path)) {
            val backend = uriBackend ?: throw MissingBookSourceException("无法访问外部文件：$path")
            return try {
                backend.openStream(path)
            } catch (e: MissingBookSourceException) {
                throw e
            } catch (e: Exception) {
                throw MissingBookSourceException("原文件已移动或无法读取", e)
            }
        }
        val file = resolveLocalFile(path) ?: throw MissingBookSourceException("原文件路径无效")
        if (!file.isFile) throw MissingBookSourceException("原文件已移动或不存在")
        return try {
            FileInputStream(file)
        } catch (e: Exception) {
            throw MissingBookSourceException("无法读取原文件", e)
        }
    }

    fun readRange(path: String, startByte: Long, length: Int): ByteArray? {
        if (startByte < 0L || length < 0) return null
        if (length == 0) return ByteArray(0)
        if (isContentUri(path)) {
            return uriBackend?.readRange(path, startByte, length)
        }
        val file = resolveLocalFile(path) ?: return null
        if (!file.isFile) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (startByte + length > raf.length()) return null
                raf.seek(startByte)
                val buf = ByteArray(length)
                raf.readFully(buf)
                buf
            }
        }.getOrNull()
    }

    fun openZip(path: String): ZipEntryReader {
        if (isContentUri(path)) {
            val backend = uriBackend ?: throw MissingBookSourceException("无法访问外部 EPUB 文件")
            return try {
                backend.openZip(path)
            } catch (e: MissingBookSourceException) {
                throw e
            } catch (e: Exception) {
                throw MissingBookSourceException("原 EPUB 文件已移动或损坏", e)
            }
        }
        val file = resolveLocalFile(path) ?: throw MissingBookSourceException("原 EPUB 路径无效")
        if (!file.isFile) throw MissingBookSourceException("原 EPUB 文件已移动或不存在")
        return ZipEntryReader.open(file)
    }

    fun readPrefix(path: String, maxBytes: Int): ByteArray? {
        if (maxBytes <= 0) return ByteArray(0)
        return runCatching {
            openStream(path).use { input ->
                val buf = ByteArray(maxBytes)
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buf, total, maxBytes - total)
                    if (n <= 0) break
                    total += n
                }
                if (total == maxBytes) buf else buf.copyOf(total)
            }
        }.getOrNull()
    }

    private class AndroidUriBackend(private val context: Context) : UriBackend {

        private val resolver get() = context.contentResolver

        override fun canReferenceDirectly(uriString: String): Boolean = runCatching {
            val uri = Uri.parse(uriString)
            if (!hasPersistedReadGrant(uri)) return false
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (pfd.statSize <= 0L) {
                    val chSize = runCatching { FileInputStream(pfd.fileDescriptor).channel.size() }.getOrDefault(-1L)
                    chSize > 0L
                } else {
                    true
                }
            } ?: false
        }.getOrDefault(false)

        private fun hasPersistedReadGrant(target: Uri): Boolean {
            val grants = runCatching { resolver.persistedUriPermissions }.getOrDefault(emptyList())
            if (grants.any { it.isReadPermission && it.uri == target }) return true
            val targetAuth = target.authority ?: return false
            val targetTreeId = runCatching {
                if (DocumentsContract.isTreeUri(target)) DocumentsContract.getTreeDocumentId(target) else null
            }.getOrNull() ?: return false
            return grants.any { perm ->
                perm.isReadPermission &&
                    perm.uri.authority == targetAuth &&
                    runCatching {
                        DocumentsContract.isTreeUri(perm.uri) &&
                            DocumentsContract.getTreeDocumentId(perm.uri) == targetTreeId
                    }.getOrDefault(false)
            }
        }

        override fun isReadable(uriString: String): Boolean = runCatching {
            val uri = Uri.parse(uriString)
            val pfd = resolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfd.close()
                true
            } else {
                resolver.openInputStream(uri)?.use { true } ?: false
            }
        }.getOrDefault(false)

        override fun size(uriString: String): Long {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return -1L
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    if (pfd.statSize >= 0L) return pfd.statSize
                    val chSize = FileInputStream(pfd.fileDescriptor).channel.size()
                    if (chSize >= 0L) return chSize
                }
            }
            runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length >= 0L) return afd.length
                }
            }
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) return cursor.getLong(index)
                }
            }
            return -1L
        }

        override fun lastModified(uriString: String): Long? = runCatching {
            val uri = Uri.parse(uriString)
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else null
            }
        }.getOrNull()

        override fun openStream(uriString: String): InputStream {
            val uri = Uri.parse(uriString)
            return try {
                resolver.openInputStream(uri)
                    ?: throw MissingBookSourceException("原文件已移动或无法打开")
            } catch (e: SecurityException) {
                throw MissingBookSourceException("原文件的读取授权已失效，请重新关联文件", e)
            } catch (e: FileNotFoundException) {
                throw MissingBookSourceException("原文件已移动或被删除，请重新关联文件", e)
            }
        }

        override fun readRange(uriString: String, startByte: Long, length: Int): ByteArray? = runCatching {
            val uri = Uri.parse(uriString)
            val pfd = resolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { fis ->
                    readChannelRange(fis.channel, startByte, length)
                }
            } else {
                resolver.openInputStream(uri)?.use { stream ->
                    skipFully(stream, startByte)
                    val out = ByteArray(length)
                    var off = 0
                    while (off < length) {
                        val n = stream.read(out, off, length - off)
                        if (n < 0) return@use null
                        off += n
                    }
                    out
                }
            }
        }.getOrNull()

        override fun openZip(uriString: String): ZipEntryReader {
            val uri = Uri.parse(uriString)
            val pfd = try {
                resolver.openFileDescriptor(uri, "r")
                    ?: throw MissingBookSourceException("无法打开原 EPUB 文件")
            } catch (e: SecurityException) {
                throw MissingBookSourceException("原 EPUB 文件的读取授权已失效，请重新关联文件", e)
            } catch (e: FileNotFoundException) {
                throw MissingBookSourceException("原 EPUB 文件已移动或被删除，请重新关联文件", e)
            }
            val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            return ZipEntryReader.open(fis.channel, Closeable { fis.close() })
        }
    }

    companion object {
        const val CONTENT_SCHEME_PREFIX = "content://"
        const val FILE_SCHEME_PREFIX = "file://"

        fun isContentUri(path: String?): Boolean =
            !path.isNullOrBlank() && path.startsWith(CONTENT_SCHEME_PREFIX)

        fun isExternalReference(paths: AppPaths, path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            if (isContentUri(path)) return true
            return managedBookToken(paths, path) == null
        }

        /**
         * 严格核验 [path] 是否位于应用私有 `files/books/<uuid>/` 托管目录内：
         * 只有返回非 null 的内部 `<uuid>` 时，删除书籍或替换清理才允许删除对应正文目录；
         * 手机外部文件与 `content://` URI 永远返回 null，物理杜绝误删用户原文件。
         */
        fun managedBookToken(paths: AppPaths, path: String?): String? {
            if (path.isNullOrBlank() || isContentUri(path)) return null
            return runCatching {
                val file = resolveLocalFile(path) ?: return null
                val parent = file.canonicalFile.parentFile ?: return null
                val booksRoot = paths.booksDir.canonicalFile
                if (parent.parentFile == booksRoot && isValidUuid(parent.name)) parent.name else null
            }.getOrNull()
        }

        /**
         * 核验 [coverPath] 是否位于应用私有 `files/covers/<uuid>.jpg` 托管目录内并返回 `<uuid>`，
         * 供零拷贝书籍更新封面或删除时安全回收不再引用的旧封面文件。
         */
        fun managedCoverToken(paths: AppPaths, coverPath: String?): String? {
            if (coverPath.isNullOrBlank() || isContentUri(coverPath)) return null
            return runCatching {
                val file = resolveLocalFile(coverPath)?.canonicalFile ?: return null
                if (file.parentFile != paths.coversDir.canonicalFile) return null
                val stem = file.name.substringBeforeLast('.')
                if (isValidUuid(stem)) stem else null
            }.getOrNull()
        }

        fun resolveLocalFile(path: String): File? {
            if (isContentUri(path)) return null
            if (path.startsWith(FILE_SCHEME_PREFIX)) {
                val uriPath = runCatching { Uri.parse(path).path }.getOrNull()
                return if (!uriPath.isNullOrBlank()) File(uriPath) else File(path.removePrefix(FILE_SCHEME_PREFIX))
            }
            return File(path)
        }

        fun tryTakePersistableReadPermission(context: Context, uri: Uri): Boolean = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        }.getOrDefault(false)

        internal fun readChannelRange(channel: FileChannel, startByte: Long, length: Int): ByteArray? {
            if (startByte < 0L || length < 0) return null
            if (length == 0) return ByteArray(0)
            val size = channel.size()
            if (size >= 0L && startByte + length > size) return null
            val out = ByteArray(length)
            val buf = ByteBuffer.wrap(out)
            var pos = startByte
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos)
                if (n < 0) return null
                pos += n
            }
            return out
        }

        private fun skipFully(stream: InputStream, bytesToSkip: Long) {
            var remaining = bytesToSkip
            val scratch = ByteArray(8192)
            while (remaining > 0L) {
                val skipped = stream.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else {
                    val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                    val n = stream.read(scratch, 0, toRead)
                    if (n < 0) throw EOFException()
                    remaining -= n
                }
            }
        }

        private fun isValidUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
