package com.mirad.reader.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.mirad.reader.data.io.BookContentAccess

/** SAF URI → 导入来源，持久化读取权限并尽量取到可读的文件名。 */
object ImportSources {

    fun fromUris(context: Context, uris: List<Uri>): List<ImportSource> = uris.map { uri ->
        BookContentAccess.tryTakePersistableReadPermission(context, uri)
        ImportSource.FromUri(uri, nameOf(context, uri))
    }

    fun fromTreeUri(
        context: Context,
        treeUri: Uri,
        isSupported: (String) -> Boolean
    ): List<ImportSource> {
        BookContentAccess.tryTakePersistableReadPermission(context, treeUri)
        return runCatching {
            DocumentFile.fromTreeUri(context, treeUri)?.listFiles()
                ?.filter { it.isFile && it.name != null && isSupported(it.name!!) }
                ?.map { ImportSource.FromUri(it.uri, it.name!!) }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun nameOf(context: Context, uri: Uri): String {
        val fromQuery = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()
        if (!fromQuery.isNullOrBlank()) return fromQuery
        val last = uri.lastPathSegment ?: return "未命名文件"
        return last.substringAfterLast('/')
    }
}
