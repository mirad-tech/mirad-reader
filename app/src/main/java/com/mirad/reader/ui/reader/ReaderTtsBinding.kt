package com.mirad.reader.ui.reader

import android.app.Activity
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.Composable
import com.mirad.reader.tts.ReaderTtsActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.tts.SentenceSplitter
import com.mirad.reader.tts.TtsBus
import com.mirad.reader.tts.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.State
import kotlinx.coroutines.CoroutineScope
import com.mirad.reader.reader.ReaderSessionState

/** 句子缓存、接章与高亮绑定；控制器与协程仍由阅读页面持有。 */
@Composable
internal fun rememberReaderTts(
    tts: TtsController,
    ttsStateHolder: State<TtsBus.TtsState>,
    session: ReaderSession,
    book: BookEntity,
    config: ReaderConfig,
    stateHolder: State<ReaderSessionState>,
    scope: CoroutineScope,
    onNotice: (String) -> Unit
): (Boolean) -> Unit {
    val state by stateHolder
    val ttsState by ttsStateHolder
    var ttsSentences by remember { mutableStateOf<List<SentenceSplitter.Sentence>>(emptyList()) }
    var ttsChapterIndex by remember { mutableStateOf(-1) }

    suspend fun ensureTtsSentences(): List<SentenceSplitter.Sentence> {
        val chapter = session.state.value.chapterIndex
        if (ttsChapterIndex == chapter && ttsSentences.isNotEmpty()) return ttsSentences
        val text = session.jsText() ?: session.chapterText(chapter)
        val sentences = SentenceSplitter.split(text)
        ttsSentences = sentences
        ttsChapterIndex = chapter
        return sentences
    }

    /**
     * Android 13+ 的前台通知需要 POST_NOTIFICATIONS 运行时授权，否则朗读时通知栏不会出现播放控制。
     * 只在用户真正开始朗读时请求一次；用户拒绝也不阻断朗读本身（只是没有通知栏控制）。
     */
    val permissionActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val activity = permissionActivity ?: return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        @Suppress("DEPRECATION")
        activity.requestPermissions(arrayOf(permission), 4127)
    }

    fun startTts(fromBeginning: Boolean) {
        // 朗读与自动阅读都控制阅读位置：启动朗读先暂停自动阅读，且不自动恢复。
        session.pauseAutoRead()
        ensureNotificationPermission()
        val targetChapter = session.state.value.chapterIndex
        val targetBookId = book.id
        scope.launch {
            val sentences = ensureTtsSentences()
            if (sentences.isEmpty()) {
                onNotice("本章没有可朗读的内容")
                return@launch
            }
            // 若异步等待期间章节已改变，直接作废在途请求，绝不能错位播放
            if (!ReaderTtsActions.isRequestValid(targetBookId, book.id, targetChapter, session.state.value.chapterIndex)) {
                return@launch
            }
            val currentOffset = session.state.value.offset
            val startIndex = ReaderTtsActions.computeStartIndex(sentences, currentOffset, fromBeginning)
            tts.play(
                bookId = targetBookId,
                chapterIndex = targetChapter,
                title = state.chapterTitle,
                sentences = sentences,
                startIndex = startIndex,
                rate = config.ttsSpeechRate,
                pitch = config.ttsPitch
            )
        }
    }

    // 朗读与自动阅读互斥：通知栏/耳机等外部入口恢复朗读时，也要暂停自动阅读。
    LaunchedEffect(ttsState.playing) {
        if (ttsState.playing) session.pauseAutoRead()
    }

    // 朗读时高亮当前句并跟随滚动（严格核对 bookId 与 chapterIndex，防止旧章朗读驱动新章位置）
    LaunchedEffect(ttsState.active, ttsState.bookId, ttsState.chapterIndex, ttsState.sentenceStart, ttsState.sentenceEnd, state.ready, state.chapterIndex) {
        if (!state.ready) return@LaunchedEffect
        val isCurrentChapterTts = ttsState.active && ttsState.bookId == book.id && ttsState.chapterIndex == state.chapterIndex
        if (isCurrentChapterTts) {
            session.evaluatePublic("MiradReader.markTts(${ttsState.sentenceStart}, ${ttsState.sentenceEnd});")
            session.evaluatePublic("MiradReader.gotoOffset(${ttsState.sentenceStart}, true);")
        } else {
            session.evaluatePublic("MiradReader.clearTts();")
        }
    }

    // 本章读完后自动进入下一章（必须严格核对已读完的章是否就是当前屏幕上的章）
    LaunchedEffect(ttsState.chapterFinished) {
        if (!ttsState.chapterFinished) return@LaunchedEffect
        val finishedChapter = ttsState.chapterIndex
        val finishedBookId = ttsState.bookId
        tts.consumeChapterFinished()
        if (!ReaderTtsActions.canAutoAdvanceChapter(
                finishedBookId = finishedBookId,
                currentBookId = book.id,
                finishedChapter = finishedChapter,
                currentChapter = session.state.value.chapterIndex,
                autoNextChapterEnabled = config.ttsAutoNextChapter
            )
        ) return@LaunchedEffect
        val next = finishedChapter + 1
        if (next < session.chapters.size) {
            // 接章任务委托给独立协程作用域，绝不因消费事件后的 key 变化而被自身取消
            scope.launch {
                ttsSentences = emptyList()
                ttsChapterIndex = -1
                session.gotoChapter(next, 0)
                var waited = 0
                while (!session.state.value.ready && waited < 4000) {
                    if (session.state.value.chapterIndex != next) return@launch
                    kotlinx.coroutines.delay(60)
                    waited += 60
                }
                kotlinx.coroutines.delay(120)
                if (session.state.value.chapterIndex == next) {
                    startTts(true)
                }
            }
        } else {
            onNotice("已读完最后一章")
        }
    }

    return ::startTts
}
