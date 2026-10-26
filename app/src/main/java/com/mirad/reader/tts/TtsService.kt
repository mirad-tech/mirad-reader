package com.mirad.reader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.mirad.reader.MainActivity
import com.mirad.reader.R
import java.util.Locale

/**
 * 后台朗读服务：持有系统 TTS 引擎，按句朗读并在通知栏提供控制。
 */
class TtsService : Service(), TextToSpeech.OnInitListener {

    private var engine: TextToSpeech? = null
    private var engineReady = false

    private var texts: List<String> = emptyList()
    private var starts: IntArray = IntArray(0)
    private var ends: IntArray = IntArray(0)
    private var index = 0
    private var playing = false
    /** 本章最后一句已读完：此时"继续"应从头播本章，而不是把最后一句再读一遍。 */
    private var chapterDone = false
    private var title: String = "墨读"
    /** 引擎尚未就绪时的待播放请求：就绪后自动续播，用户不必再点一次。 */
    private var pendingPlay = false
    private var pendingRate = 1f
    private var pendingPitch = 1f
    /** 只清理本服务实际接收的负载，不能清空尚未送达的新请求。 */
    private var playlistToken: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        engineReady = status == TextToSpeech.SUCCESS
        if (!engineReady) {
            // 初始化失败要结束等待：否则界面会永久停在"正在播放"
            pendingPlay = false
            playing = false
            TtsBus.update { it.copy(playing = false, error = "语音引擎初始化失败") }
            return
        }
        engine?.language = Locale.CHINA
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (!playing) return
                if (index >= texts.size - 1) {
                    // 本章读完：服务侧同步退出播放态，否则通知栏的"继续"会把最后一句再读一遍
                    playing = false
                    chapterDone = true
                    TtsBus.markChapterFinished()
                    notifyUpdate()
                } else {
                    index++
                    speakCurrent()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                playing = false
                TtsBus.update { it.copy(playing = false, error = "朗读出错") }
                notifyUpdate()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                playing = false
                TtsBus.update { it.copy(playing = false, error = "朗读出错（$errorCode）") }
                notifyUpdate()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // QUEUE_FLUSH 换句（上一句/下一句/换章）同样会触发 onStop：
                // 只有用户主动暂停/停止（playing 已为 false）才同步到 bus，
                // 否则会把刚开始的下一句误标为已暂停。
                if (!playing) TtsBus.update { it.copy(playing = false) }
            }
        })
        // 只续播"初始化期间用户确实请求过"的播放；服务因别的原因启动时不应自动开口
        if (pendingPlay) {
            pendingPlay = false
            playOrDefer()
        }
    }

    /** 引擎就绪就立刻朗读；未就绪则记下这一请求，等 onInit 回调再续播。 */
    private fun playOrDefer() {
        if (texts.isEmpty()) return
        if (engineReady) {
            playing = true
            speakCurrent()
        } else {
            playing = true
            pendingPlay = true
            TtsBus.update { it.copy(active = true, playing = true) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TtsBus.ACTION_PLAY -> {
                // 先解析负载再亮通知：通知文案要读 texts/index，顺序反了会先闪一条空列表的通知。
                // 无论成功与否都必须 startForeground（服务是以 startForegroundService 拉起的）。
                val request = TtsBus.resolvePlayRequest(intent.getStringExtra(TtsBus.EXTRA_TOKEN))
                if (request is TtsBus.PlayRequest.Ready) readPlaylist(intent, request.playlist)
                startForeground(NOTIFICATION_ID, buildNotification())
                when (request) {
                    TtsBus.PlayRequest.Superseded -> return START_NOT_STICKY
                    TtsBus.PlayRequest.Missing -> {
                        pendingPlay = false
                        playing = false
                        TtsBus.update {
                            it.copy(active = false, playing = false, error = "朗读请求已失效，请重新开始朗读")
                        }
                        // 仅结束本次启动；较新的 start 请求可能还未送达 onStartCommand。
                        if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                        return START_NOT_STICKY
                    }
                    is TtsBus.PlayRequest.Ready -> playOrDefer()
                }
            }
            TtsBus.ACTION_PAUSE -> {
                pendingPlay = false
                playing = false
                engine?.stop()
                TtsBus.update { it.copy(playing = false) }
                notifyUpdate()
            }
            TtsBus.ACTION_RESUME -> {
                if (texts.isNotEmpty()) {
                    if (chapterDone) {
                        index = 0
                        chapterDone = false
                    }
                    playOrDefer()
                }
            }
            TtsBus.ACTION_NEXT -> {
                if (index < texts.size - 1) {
                    index++
                    chapterDone = false
                    playOrDefer()
                }
            }
            TtsBus.ACTION_PREV -> {
                if (index > 0) {
                    index--
                    chapterDone = false
                    playOrDefer()
                }
            }
            TtsBus.ACTION_STOP -> {
                stopEverything(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 从进程内负载恢复播放列表；token 不匹配说明请求已失效。
     *
     * 旧实现把整章句子塞进 Intent，EPUB「整本书一个 spine」时会因为超过 Binder 事务上限
     * 直接崩溃；现在只传 token，负载由 [TtsBus.playlist] 持有。
     */
    private fun readPlaylist(intent: Intent, playlist: TtsBus.Playlist) {
        playlistToken = playlist.token
        texts = playlist.texts
        starts = playlist.starts
        ends = playlist.ends
        index = intent.getIntExtra(TtsBus.EXTRA_INDEX, 0).coerceIn(0, (texts.size - 1).coerceAtLeast(0))
        // 新的播放列表：上一章的"已读完"标记不能带过来，否则暂停后继续会误从头播。
        chapterDone = false
        title = playlist.title
        val rate = intent.getFloatExtra(TtsBus.EXTRA_RATE, 1f)
        val pitch = intent.getFloatExtra(TtsBus.EXTRA_PITCH, 1f)
        // 记下用户选的语速/音调：引擎晚就绪时 onInit 要用它们，而不是默认值
        pendingRate = rate
        pendingPitch = pitch
        engine?.setSpeechRate(rate.coerceIn(0.4f, 2.5f))
        engine?.setPitch(pitch.coerceIn(0.5f, 2f))
        TtsBus.update {
            TtsBus.TtsState(
                active = true,
                playing = true,
                bookId = playlist.bookId,
                chapterIndex = playlist.chapterIndex,
                index = index,
                total = texts.size,
                sentenceStart = starts.getOrElse(index) { 0 },
                sentenceEnd = ends.getOrElse(index) { 0 }
            )
        }
    }

    private fun speakCurrent() {
        if (!engineReady || texts.isEmpty()) return
        val text = texts.getOrNull(index) ?: return
        TtsBus.update {
            it.copy(
                active = true,
                playing = true,
                index = index,
                total = texts.size,
                chapterFinished = false,
                sentenceStart = starts.getOrElse(index) { 0 },
                sentenceEnd = ends.getOrElse(index) { 0 },
                error = null
            )
        }
        val speakResult = engine?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
        if (speakResult != TextToSpeech.SUCCESS) {
            playing = false
            TtsBus.update { it.copy(playing = false, error = "语音引擎拒绝播放") }
        }
        notifyUpdate()
    }

    private fun stopEverything(startId: Int) {
        pendingPlay = false
        playing = false
        engine?.stop()
        // 播放列表可能是一整章文本，停止后立即释放，不必等 GC。
        if (TtsBus.clearPlaylist(playlistToken)) TtsBus.reset()
        if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        pendingPlay = false
        playing = false
        engine?.stop()
        engine?.shutdown()
        engine = null
        // 保留"朗读已失效"这类错误提示：界面刚收到就被 reset 掉的话，用户看不到任何解释。
        if (TtsBus.clearPlaylist(playlistToken)) {
            if (TtsBus.state.value.error == null) {
                TtsBus.reset()
            } else {
                TtsBus.update { it.copy(active = false, playing = false) }
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "朗读控制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "朗读时的播放控制"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在朗读")
            .setContentText("${title}（${index + 1}/${texts.size}）")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "上一句", action(TtsBus.ACTION_PREV, 1))
        if (playing) {
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", action(TtsBus.ACTION_PAUSE, 2))
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "继续", action(TtsBus.ACTION_RESUME, 3))
        }
        builder.addAction(android.R.drawable.ic_media_next, "下一句", action(TtsBus.ACTION_NEXT, 4))
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(TtsBus.ACTION_STOP, 5))
        return builder.build()
    }

    private fun action(actionName: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TtsService::class.java).setAction(actionName)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun notifyUpdate() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val CHANNEL_ID = "mirad_tts"
        private const val NOTIFICATION_ID = 0x7A11
        private const val UTTERANCE_ID = "mirad-tts"

        fun intent(context: Context, action: String): Intent =
            Intent(context, TtsService::class.java).setAction(action)
    }
}
