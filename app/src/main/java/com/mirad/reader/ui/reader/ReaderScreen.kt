package com.mirad.reader.ui.reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.OrientationKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.StatsMath
import com.mirad.reader.core.model.ThemePalette
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.repo.SearchHit
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.reader.SelectionAction
import com.mirad.reader.tts.SentenceSplitter
import com.mirad.reader.tts.TtsBus
import com.mirad.reader.tts.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun ReaderScreen(
    container: AppContainer,
    bookId: Long,
    systemDark: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        val loaded = container.books.getBook(bookId)
        if (loaded == null) {
            error = "书籍不存在"
            return@LaunchedEffect
        }
        val config = container.settings.effective(bookId)
        val created = ReaderSession(container, loaded, scope)
        runCatching { created.prepare(config, systemDark) }
            .onFailure { error = it.message ?: "打开失败" }
        book = loaded
        session = created
    }

    val current = session
    val currentBook = book
    if (error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
        }
        return
    }
    if (current == null || currentBook == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    DisposableEffect(current) {
        onDispose { current.release() }
    }

    ReaderContent(
        container = container,
        session = current,
        book = currentBook,
        systemDark = systemDark,
        onBack = onBack
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    container: AppContainer,
    session: ReaderSession,
    book: BookEntity,
    systemDark: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state by session.state.collectAsState()
    val config = state.config
    val palette = remember(config.theme, config.backgroundColor, systemDark) {
        ThemePaletteResolver.resolve(config, systemDark)
    }

    var chromeVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var pendingNote by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var noteDraft by remember { mutableStateOf("") }

    val tts = remember { TtsController(container.context) }
    DisposableEffect(Unit) { onDispose { tts.stop() } }

    val ttsState by TtsBus.state.collectAsState()
    var ttsSentences by remember { mutableStateOf<List<SentenceSplitter.Sentence>>(emptyList()) }
    var ttsChapterIndex by remember { mutableStateOf(-1) }

    val applyConfig: ((ReaderConfig) -> ReaderConfig) -> Unit = { transform ->
        val updated = transform(state.config)
        session.applyConfigToWebView(updated, systemDark)
        scope.launch { container.settings.saveBookConfig(book.id, updated) }
    }

    suspend fun ensureTtsSentences(): List<SentenceSplitter.Sentence> {
        val chapter = session.state.value.chapterIndex
        if (ttsChapterIndex == chapter && ttsSentences.isNotEmpty()) return ttsSentences
        val text = session.jsText() ?: session.chapterText(chapter)
        val sentences = SentenceSplitter.split(text)
        ttsSentences = sentences
        ttsChapterIndex = chapter
        return sentences
    }

    fun startTts(fromBeginning: Boolean) {
        scope.launch {
            val sentences = ensureTtsSentences()
            if (sentences.isEmpty()) {
                toast = "本章没有可朗读的内容"
                return@launch
            }
            val currentOffset = session.state.value.offset
            val startIndex = if (fromBeginning) 0 else {
                sentences.indexOfLast { it.start <= currentOffset }.coerceAtLeast(0)
            }
            tts.play(
                bookId = book.id,
                chapterIndex = session.state.value.chapterIndex,
                title = state.chapterTitle,
                sentences = sentences,
                startIndex = startIndex,
                rate = config.ttsSpeechRate,
                pitch = config.ttsPitch
            )
        }
    }

    // 朗读时高亮当前句并跟随滚动
    LaunchedEffect(ttsState.active, ttsState.sentenceStart, ttsState.sentenceEnd, state.ready) {
        if (!state.ready) return@LaunchedEffect
        if (ttsState.active) {
            session.evaluatePublic("MiradReader.markTts(${ttsState.sentenceStart}, ${ttsState.sentenceEnd});")
            session.evaluatePublic("MiradReader.gotoOffset(${ttsState.sentenceStart}, true);")
        } else {
            session.evaluatePublic("MiradReader.clearTts();")
        }
    }

    // 本章读完后自动进入下一章
    LaunchedEffect(ttsState.chapterFinished) {
        if (!ttsState.chapterFinished) return@LaunchedEffect
        tts.consumeChapterFinished()
        if (!config.ttsAutoNextChapter) return@LaunchedEffect
        val next = session.state.value.chapterIndex + 1
        if (next < session.chapters.size) {
            ttsSentences = emptyList()
            ttsChapterIndex = -1
            session.gotoChapter(next, 0)
            kotlinx.coroutines.delay(1200)
            startTts(true)
        } else {
            toast = "已读完最后一章"
        }
    }

    // 屏幕亮度 / 常亮 / 方向 / 全屏
    ApplyWindowSettings(config)

    // 前后台切换时结算阅读时长（后台计时不算入统计）
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner, session) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> session.endSession()
                androidx.lifecycle.Lifecycle.Event.ON_START -> session.startSession()
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // 覆盖翻页需要软件层才能可靠地把 WebView 内容画进位图
    LaunchedEffect(config.pageAnim) {
        session.webView?.setLayerType(
            if (config.pageAnim == PageAnim.COVER) View.LAYER_TYPE_SOFTWARE
            else View.LAYER_TYPE_HARDWARE,
            null
        )
    }

    // COVER 翻页动画的旧页位图
    var overlay by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var overlayX by remember { mutableFloatStateOf(0f) }
    var animating by remember { mutableStateOf(false) }

    fun performTurn(delta: Int) {
        val view = session.webView
        val useOverlay = config.pageAnim == PageAnim.COVER && view != null && state.ready && !animating
        if (!useOverlay) {
            val switched = session.turnPage(delta)
            if (!switched && session.state.value.ready) {
                val s = session.state.value
                if ((delta > 0 && s.page >= s.pages - 1 && s.chapterIndex >= s.chapterCount - 1) ||
                    (delta < 0 && s.page <= 0 && s.chapterIndex <= 0)
                ) {
                    toast = if (delta > 0) "已经是最后一章" else "已经是第一章"
                }
            }
            return
        }
        captureView(view!!) { bitmap ->
            if (bitmap == null) {
                session.turnPage(delta)
                return@captureView
            }
            overlay = bitmap.asImageBitmap()
            overlayX = 0f
            animating = true
            session.turnPage(delta)
            val distance = if (delta > 0) -view.width.toFloat() else view.width.toFloat()
            android.animation.ValueAnimator.ofFloat(0f, distance).apply {
                duration = 260
                addUpdateListener { overlayX = it.animatedValue as Float }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        overlay = null
                        overlayX = 0f
                        animating = false
                        bitmap.recycle()
                    }
                })
                start()
            }
        }
    }

    session.onTap = tap@{ x, y ->
        val view = session.webView ?: return@tap
        val width = view.width.toFloat()
        val ratio = if (width > 0) x / width else 0.5f
        when (config.tapZone) {
            "NONE" -> chromeVisible = !chromeVisible
            "TWO" -> if (ratio < 0.5f) performTurn(-1) else performTurn(1)
            else -> when {
                ratio < 0.32f -> performTurn(-1)
                ratio > 0.68f -> performTurn(1)
                else -> chromeVisible = !chromeVisible
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            showToc || showSettings || showProgress || showNotes || showSearch || showTts -> {
                showToc = false; showSettings = false; showProgress = false
                showNotes = false; showSearch = false; showTts = false
            }
            chromeVisible -> chromeVisible = false
            else -> {
                session.saveProgressNow()
                tts.stop()
                onBack()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                ReaderWebView(ctx).also { view ->
                    session.attach(view)
                    view.onSelectionAction = { action ->
                        val selection = session.state.value.selection
                        if (selection == null || selection.end <= selection.start) {
                            toast = "请先选择文字"
                        } else {
                            when (action) {
                                SelectionAction.HIGHLIGHT -> {
                                    session.addHighlight(selection.start, selection.end, selection.text) { ok ->
                                        toast = if (ok) "已划线" else "划线失败"
                                    }
                                    session.clearSelection()
                                }
                                SelectionAction.NOTE -> {
                                    pendingNote = Triple(selection.start, selection.end, selection.text)
                                    noteDraft = ""
                                }
                                SelectionAction.SEARCH -> {
                                    showSearch = true
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = config.horizontalPaddingDp.dp,
                    vertical = 0.dp
                )
                .background(Color(palette.background)),
            update = { }
        )

        // 旧页位图覆盖层（覆盖翻页）
        val overlayBitmap = overlay
        if (overlayBitmap != null) {
            Image(
                bitmap = overlayBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = config.horizontalPaddingDp.dp)
                    .graphicsLayer { translationX = overlayX }
            )
        }

        // 页眉页脚（始终显示，非交互）
        HeaderFooter(config, palette, state.chapterTitle, state.page, state.pages, state.percent)

        // 顶部/底部工具栏
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                title = state.chapterTitle.ifBlank { book.title },
                subtitle = book.title,
                hasBookmark = state.hasBookmarkHere,
                onBack = { session.saveProgressNow(); onBack() },
                onBookmark = {
                    session.toggleBookmark { added -> toast = if (added) "已添加书签" else "已取消书签" }
                },
                onNotes = { showNotes = true },
                onSearch = { showSearch = true },
                onToc = { showToc = true },
                onSettings = { showSettings = true },
                onToggleTts = {
                    if (tts.isSpeaking) {
                        tts.pause()
                    } else {
                        showTts = true
                    }
                },
                speaking = tts.isSpeaking
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                percent = state.percent,
                page = state.page,
                pages = state.pages,
                chapterIndex = state.chapterIndex,
                chapterCount = state.chapterCount,
                onToc = { showToc = true },
                onProgress = { showProgress = true },
                onSettings = { showSettings = true },
                onNotes = { showNotes = true },
                onSearch = { showSearch = true },
                onTts = { if (tts.isSpeaking) tts.pause() else showTts = true },
                speaking = tts.isSpeaking,
                onSliderChange = { session.jumpToPercent(it) }
            )
        }

        if (!state.ready) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(palette.background).copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        if (showTts) {
            TtsSheet(
                tts = tts,
                onStart = { resume ->
                    if (resume && ttsState.active) tts.resume() else startTts(false)
                },
                onClose = { showTts = false }
            )
        }

        toast?.let { message ->
            LaunchedEffect(message) {
                delay(1600)
                toast = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    if (showToc) {
        TocSheet(
            chapters = session.chapters,
            currentIndex = state.chapterIndex,
            onDismiss = { showToc = false },
            onSelect = { index ->
                session.gotoChapter(index)
                showToc = false
            }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            container = container,
            bookId = book.id,
            config = config,
            onChange = applyConfig,
            onResetGlobal = {
                scope.launch {
                    container.settings.clearBookConfig(book.id)
                    val global = container.settings.currentGlobal()
                    session.applyConfigToWebView(global, systemDark)
                }
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showProgress) {
        ProgressSheet(
            percent = state.percent,
            chapterIndex = state.chapterIndex,
            chapterCount = state.chapterCount,
            chapterTitle = state.chapterTitle,
            page = state.page,
            pages = state.pages,
            onJump = { session.jumpToPercent(it) },
            onJumpChapter = { index ->
                if (index in session.chapters.indices) session.gotoChapter(index)
            },
            onDismiss = { showProgress = false }
        )
    }

    if (showNotes) {
        NotesSheet(
            container = container,
            session = session,
            book = book,
            onDismiss = { showNotes = false },
            onJump = { chapterIndex, offset ->
                if (chapterIndex != session.state.value.chapterIndex) {
                    session.gotoChapter(chapterIndex, offset)
                } else {
                    session.jumpToOffset(offset, mark = true)
                }
                showNotes = false
            }
        )
    }

    if (showSearch) {
        SearchSheet(
            container = container,
            session = session,
            book = book,
            onDismiss = { showSearch = false },
            onJump = { hit ->
                if (hit.chapterIndex != session.state.value.chapterIndex) {
                    session.gotoChapter(hit.chapterIndex, hit.charOffset)
                    // 章节加载后由 findText 就近精确定位
                    scope.launch {
                        kotlinx.coroutines.delay(900)
                        session.findTextAndJump(hit.match, hit.charOffset)
                    }
                } else {
                    session.findTextAndJump(hit.match, hit.charOffset)
                }
                showSearch = false
            }
        )
    }

    pendingNote?.let { (start, end, text) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingNote = null },
            title = { Text("写笔记") },
            text = {
                Column {
                    Text(
                        text = text.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        label = { Text("笔记内容") },
                        modifier = Modifier.height(120.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    session.addHighlight(start, end, text, note = noteDraft.ifBlank { null }) { ok ->
                        toast = if (ok) "已保存笔记" else "保存失败"
                    }
                    session.clearSelection()
                    pendingNote = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingNote = null }) { Text("取消") }
            }
        )
    }
}

/** 亮度 / 常亮 / 方向 / 全屏。 */
@Composable
private fun ApplyWindowSettings(config: ReaderConfig) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(activity, config.brightness, config.keepScreenOn, config.fullScreen) {
        val window = activity?.window
        if (window != null) {
            val params = window.attributes
            params.screenBrightness = if (config.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else config.brightness.coerceIn(0.01f, 1f)
            window.attributes = params
            if (config.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = when (config.orientation) {
                OrientationKey.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationKey.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (config.fullScreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = activity?.window ?: return@onDispose
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = w.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = params
            val controller = WindowInsetsControllerCompat(w, w.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** 页眉页脚：章节名 / 时间 / 电量 / 页码 / 进度。 */
@Composable
private fun HeaderFooter(
    config: ReaderConfig,
    palette: ThemePalette,
    chapterTitle: String,
    page: Int,
    pages: Int,
    percent: Float
) {
    val clock = rememberClock(config.showClock)
    val battery = rememberBattery(config.showBattery)
    val textColor = Color(palette.secondaryText)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = (config.horizontalPaddingDp + 4).dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (config.showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (config.showChapterTitleInHeader) chapterTitle else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    maxLines = 1
                )
                Row {
                    if (config.showBattery) {
                        Text("$battery%  ", style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                    if (config.showClock) {
                        Text(clock, style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                }
            }
        }
        if (config.showFooter) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${(percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
                if (config.showPageNumber) {
                    Text(
                        text = "${page + 1} / ${pages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberClock(enabled: Boolean): String {
    var time by remember { mutableStateOf(currentTime()) }
    LaunchedEffect(enabled) {
        while (enabled) {
            time = currentTime()
            delay(20_000)
        }
    }
    return time
}

private fun currentTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun rememberBattery(enabled: Boolean): Int {
    val context = LocalContext.current
    var level by remember { mutableStateOf(readBattery(context)) }
    LaunchedEffect(enabled) {
        while (enabled) {
            level = readBattery(context)
            delay(60_000)
        }
    }
    return level
}

private fun readBattery(context: Context): Int = runCatching {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (level >= 0 && scale > 0) level * 100 / scale else -1
}.getOrDefault(-1)

/** 用软件层捕获当前页位图，用于覆盖翻页动画；失败时返回 null（自动退化为普通翻页）。 */
private fun captureView(view: View, onResult: (Bitmap?) -> Unit) {
    if (view.width <= 0 || view.height <= 0) {
        onResult(null)
        return
    }
    val bitmap = runCatching {
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return onResult(null)
    val success = runCatching {
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        hasVisibleContent(bitmap)
    }.getOrDefault(false)
    if (success) {
        onResult(bitmap)
    } else {
        bitmap.recycle()
        onResult(null)
    }
}

/** 抽样检查位图是否有实际内容，避免把"空白快照"当成有效动画帧。 */
private fun hasVisibleContent(bitmap: Bitmap): Boolean {
    val stepX = (bitmap.width / 12).coerceAtLeast(1)
    val stepY = (bitmap.height / 12).coerceAtLeast(1)
    var visible = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) > 8) visible++
            x += stepX
        }
        y += stepY
    }
    return visible >= 6
}
