package com.mirad.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.mirad.reader.data.imports.ExternalImportUris
import com.mirad.reader.data.imports.ImportSource
import com.mirad.reader.ui.AppRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 外部导入用的作用域：provider 查询一律在 IO，避免阻塞主线程。 */
    private val externalImportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 音量键翻页：阅读页注册了 handler 时消费按键（上=上一页/上滚、下=下一页/下滚），
     * 其他界面不拦截，音量键照常工作。
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val delta = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> -1
            else -> 0
        }
        if (delta != 0) {
            if (com.mirad.reader.reader.ReaderKeys.dispatch(delta)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MiradApp).container
        // 只在冷启动消费外部 Intent。配置变更/进程重建时 savedInstanceState != null，
        // 跳过以免同一次分享被重复入队；用户再次分享是新 Intent，走 onNewIntent，不受影响。
        if (savedInstanceState == null || intent?.action == com.mirad.reader.data.downloads.BookDownloadNotifications.ACTION_OPEN) {
            importFromExternalIntent(intent, container)
        }
        setContent {
            val systemDark = isSystemInDarkTheme()
            AppRoot(container = container, systemDark = systemDark)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        importFromExternalIntent(intent, (application as MiradApp).container)
    }

    override fun onResume() {
        super.onResume()
        val container = (application as MiradApp).container
        externalImportScope.launch {
            try { container.downloadManager.restoreScheduling() }
            catch (error: Exception) { container.downloadManager.reportRunnerFailure(error) }
        }
    }

    override fun onDestroy() {
        externalImportScope.cancel()
        super.onDestroy()
    }

    /**
     * ACTION_VIEW / ACTION_SEND 的文件导入：和书架里的「导入文件」共用 ImportPipeline，
     * 所以格式识别、去重、失败提示与导入结果展示完全一致。
     *
     * 这里只做 **廉价的 Intent 拆解**；所有 ContentResolver 访问（探测可读性、取文件名、
     * 申请持久授权）都在 IO 线程完成，慢速 provider 不会阻塞界面。
     */
    private fun importFromExternalIntent(intent: Intent?, container: AppContainer) {
        if (intent == null) return
        val action = intent.action
        if (action == com.mirad.reader.data.downloads.BookDownloadNotifications.ACTION_OPEN) {
            container.openDownloads.value = true
            intent.action = Intent.ACTION_MAIN
            return
        }
        if (action != ExternalImportUris.ACTION_VIEW && action != ExternalImportUris.ACTION_SEND) return

        val clipUris = intent.clipData?.let { clip ->
            (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri?.toString() }
        } ?: emptyList()

        val streamUris = if (intent.hasExtra(Intent.EXTRA_STREAM)) {
            runCatching {
                @Suppress("DEPRECATION")
                val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                @Suppress("DEPRECATION")
                val many = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                buildList {
                    if (single != null) add(single.toString())
                    if (many != null) addAll(many.mapNotNull { it?.toString() })
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val raw = ExternalImportUris.collect(
            action = action,
            data = intent.data?.toString(),
            clipUris = clipUris,
            streamUris = streamUris,
            text = intent.getStringExtra(Intent.EXTRA_TEXT)
        )
        if (raw.isEmpty()) {
            container.feedback.show("没有可导入的文件（只支持 txt / epub 文件）", com.mirad.reader.ui.common.FeedbackKind.ERROR)
            return
        }

        // 有显式读授权时，data / ClipData 里的 URI 才保证可读；用来区分"没授权"与"文件读不出来"
        val granted = ExternalImportUris.carriesReadGrant(intent.flags)
        val ordered = ExternalImportUris.prioritize(raw)
        externalImportScope.launch {
            val sources = withContext(Dispatchers.IO) { resolveImportSources(ordered) }
            if (sources.isEmpty()) {
                val message = if (granted) {
                    "无法读取分享的文件，请确认文件仍然存在"
                } else {
                    "没有读取权限，换个方式分享试试"
                }
                container.feedback.show(message, com.mirad.reader.ui.common.FeedbackKind.ERROR)
                return@launch
            }
            container.importer.import(sources)
        }
    }

    /** IO 线程：探测可读性（顺带拿到临时读取权限的作用域）、取文件名、尝试申请持久授权。 */
    private fun resolveImportSources(uris: List<String>): List<ImportSource.FromUri> = uris.mapNotNull { text ->
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return@mapNotNull null
        val readable = runCatching {
            val stream = contentResolver.openInputStream(uri) ?: return@runCatching false
            stream.close()
            true
        }.getOrDefault(false)
        if (!readable) return@mapNotNull null
        // 尽量申请持久读权限；多数分享方不支持，失败不能阻止本次导入
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "shared-file"
        ImportSource.FromUri(uri, name)
    }

    /** 用 OpenableColumns.DISPLAY_NAME 取文件名；取不到返回 null。 */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()
}
