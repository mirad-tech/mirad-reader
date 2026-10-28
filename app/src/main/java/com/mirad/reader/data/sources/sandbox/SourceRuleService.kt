package com.mirad.reader.data.sources.sandbox

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import com.mirad.reader.core.sources.rules.RuleLimits
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors

/** 必须在 Manifest 以 exported=false、isolatedProcess=true、独立 process 注册。 */
class SourceRuleService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "source-rule-compute") }
    private val watchdog = Timer("source-rule-watchdog", true)
    @Volatile private var activeRequest: Long? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            RuleWire.RUN -> runRequest(message)
            RuleWire.CANCEL -> if (activeRequest == message.data.getLong(RuleWire.REQUEST_ID)) terminateProcess()
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        val isolated = if (Build.VERSION.SDK_INT >= 28) Process.isIsolated() else Process.myUid() != applicationInfo.uid
        check(isolated) { "规则服务必须运行在独立 isolatedProcess 中" }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun runRequest(message: Message) {
        val data = message.data
        val callback = message.replyTo
        val requestId = data.getLong(RuleWire.REQUEST_ID)
        data.classLoader = ParcelFileDescriptor::class.java.classLoader
        @Suppress("DEPRECATION")
        val input = data.getParcelable<ParcelFileDescriptor>(RuleWire.INPUT)
        @Suppress("DEPRECATION")
        val output = data.getParcelable<ParcelFileDescriptor>(RuleWire.OUTPUT)
        if (input == null || output == null) {
            input?.close()
            output?.close()
            replyFinished(callback, requestId, "规则隔离服务未收到完整的文件描述符")
            return
        }
        if (activeRequest != null) {
            input.close()
            output.close()
            replyFinished(callback, requestId, "规则隔离进程正在处理另一项请求")
            return
        }
        activeRequest = requestId
        val timeout = object : TimerTask() {
            override fun run() {
                if (activeRequest == requestId) terminateProcess()
            }
        }
        watchdog.schedule(timeout, RuleLimits.PROCESS_TIMEOUT_MS)
        worker.execute {
            var responseStream: ParcelFileDescriptor.AutoCloseOutputStream? = null
            var failureMessage: String? = null
            try {
                val reply = try {
                    val text = ParcelFileDescriptor.AutoCloseInputStream(input).use(RuleWire::read)
                    RuleWire.evaluate(text)
                } catch (failure: Exception) {
                    SandboxReply(error = RuleWire.diagnostic("请求读取", failure))
                }
                val bytes = try { RuleWire.encode(reply) } catch (failure: Exception) {
                    RuleWire.encode(SandboxReply(error = RuleWire.diagnostic("响应编码", failure)))
                }
                val stream = ParcelFileDescriptor.AutoCloseOutputStream(output)
                responseStream = stream
                stream.write(bytes)
            } catch (failure: Exception) {
                failureMessage = RuleWire.diagnostic("响应写入", failure)
            } finally {
                // 先结束计算状态，再关闭管道发送 EOF；主进程此后才能完成读回并发起下一次请求。
                activeRequest = null
                timeout.cancel()
                runCatching { input.close() }
                runCatching { responseStream?.close() }
                runCatching { output.close() }
                replyFinished(callback, requestId, failureMessage)
            }
        }
    }

    private fun replyFinished(callback: Messenger?, requestId: Long, error: String?) {
        runCatching {
            callback?.send(Message.obtain(null, RuleWire.FINISHED).apply {
                data = Bundle().apply {
                    putLong(RuleWire.REQUEST_ID, requestId)
                    putString(RuleWire.ERROR, error)
                }
            })
        }
    }

    override fun onDestroy() {
        // 客户端丢失时也不遗留计算线程；正常取消另有带请求编号的 CANCEL。
        if (activeRequest != null) terminateProcess()
        watchdog.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun terminateProcess() {
        Process.killProcess(Process.myPid())
    }
}
