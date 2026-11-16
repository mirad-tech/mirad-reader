package com.mirad.reader.data.imports

import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.data.io.BookContentAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

internal class IncomingSourceChanged(cause: Throwable? = null) : Exception("所选文件已变化或无法读取，请重新导入", cause)

/** 完整原始字节的版本证据；与仅用于快速筛选的采样指纹分开保存。 */
internal data class SourceVersion(val byteCount: Long, val sha256: String) {
    suspend fun verify(access: BookContentAccess, path: String) {
        val actual = try {
            capture(access, path)
        } catch (e: java.io.IOException) {
            throw IncomingSourceChanged(e)
        } catch (e: SecurityException) {
            throw IncomingSourceChanged(e)
        }
        if (actual != this) throw IncomingSourceChanged()
    }

    companion object {
        suspend fun capture(access: BookContentAccess, path: String): SourceVersion {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            access.openStream(path).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    bytes += count
                }
            }
            return SourceVersion(bytes, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}

internal class BookSourceVersionStore(private val preferences: PreferenceDao?) {
    private fun key(bookId: Long) = "book_source_version_v1_$bookId"

    suspend fun get(bookId: Long): SourceVersion? {
        val raw = preferences?.get(key(bookId)) ?: return null
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2) return null
        val count = parts[0].toLongOrNull() ?: return null
        if (count < 0 || !parts[1].matches(Regex("[0-9a-f]{64}"))) return null
        return SourceVersion(count, parts[1])
    }

    suspend fun save(bookId: Long, version: SourceVersion) {
        preferences?.put(PreferenceEntity(key(bookId), "${version.byteCount}|${version.sha256}", System.currentTimeMillis()))
    }

    suspend fun remove(bookId: Long) {
        preferences?.remove(key(bookId))
    }
}
