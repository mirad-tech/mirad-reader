package com.mirad.reader.data.imports

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** SAF URI → 导入来源，并尽量取到可读的文件名。 */
object ImportSources {

    fun fromUris(context: Context, uris: List<Uri>): List<ImportSource> = uris.map { uri ->
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        ImportSource.FromUri(uri, nameOf(context, uri))
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
