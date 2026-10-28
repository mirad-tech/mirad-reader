package com.mirad.reader.data.sources.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.mirad.reader.core.sources.RuleExecutor
import com.mirad.reader.core.sources.RuleRequest
import com.mirad.reader.core.sources.RuleResult
import com.mirad.reader.core.sources.rules.RuleEvaluationException
import com.mirad.reader.core.sources.rules.RuleLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 主进程只做序列化和 IPC；所有用户规则（含正则）在无权限隔离进程执行。 */
class SandboxRuleExecutor(context: Context) : RuleExecutor {
    private val context = context.applicationContext
    private val connectionLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: BoundService? = null
    private var idleRelease: Runnable? = null

    override suspend fun execute(request: RuleRequest): RuleResult = requestMutex.withLock {
        val bytes = withContext(Dispatchers.Default) { RuleWire.bytes(RuleWire.json.encodeToString(request)) }
        try {
            withTimeout(RuleLimits.REQUEST_TIMEOUT_MS) { invoke(bytes) }
        } catch (_: TimeoutCancellationException) {
            synchronized(connectionLock) { service?.let { discard(it) } }
            throw RuleEvaluationException("规则隔离请求超时，请重新尝试")
        } catch (failure: CancellationException) {
            synchronized(connectionLock) { service?.let { discard(it) } }
            throw failure
        }
    }

    private suspend fun invoke(bytes: ByteArray): RuleResult = coroutineScope {
        val requestId = nextRequest.incrementAndGet()
        val finished = CompletableDeferred<String?>()
        val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == RuleWire.FINISHED && message.data.getLong(RuleWire.REQUEST_ID) == requestId) {
                finished.complete(message.data.getString(RuleWire.ERROR))
            }
            true
        })
        var remote: Messenger? = null
        var connection: BoundService? = null
        var completed = false
        // Binder 传递后要关闭本地副本；普通管道不会把此关闭误当成远端的可靠状态消息。
        val input = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.createPipe()
        try {
            val lease = acquire(finished)
            connection = lease
            remote = lease.connected.await()
            remote.send(Message.obtain(null, RuleWire.RUN).apply {
                replyTo = callback
                data = Bundle().apply {
                    putLong(RuleWire.REQUEST_ID, requestId)
                    putParcelable(RuleWire.INPUT, input[0])
                    putParcelable(RuleWire.OUTPUT, output[1])
                }
            })
            input[0].close()
            output[1].close()
            val writer = async { pipeIo(input[1]) { ParcelFileDescriptor.AutoCloseOutputStream(it).use { stream -> stream.write(bytes) } } }
            val reader = async {
                pipeIo(output[0]) { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(RuleWire::read)
                }
            }
            finished.await()?.let { throw RuleEvaluationException(it) }
            val text = reader.await()
            writer.await()
            if (text.isEmpty()) throw RuleEvaluationException("规则隔离服务返回了空响应")
            val reply = try { RuleWire.json.decodeFromString<SandboxReply>(text) } catch (failure: Exception) {
                throw RuleEvaluationException(RuleWire.diagnostic("响应解码", failure))
            }
            reply.error?.let { throw RuleEvaluationException(it) }
            val result = reply.result ?: throw RuleEvaluationException("规则隔离服务未返回结果")
            completed = true
            result
        } finally {
            if (!completed) {
                runCatching {
                    remote?.send(Message.obtain(null, RuleWire.CANCEL).apply {
                        data = Bundle().apply { putLong(RuleWire.REQUEST_ID, requestId) }
                    })
                }
            }
            (input + output).forEach { runCatching { it.close() } }
            connection?.let { release(it, completed) }
        }
    }

    /** 请求串行；连接锁仅保护绑定和空闲释放，不覆盖规则执行或管道读写。 */
    private fun acquire(finished: CompletableDeferred<String?>): BoundService = synchronized(connectionLock) {
        cancelIdleRelease()
        service?.takeIf { it.remote?.binder?.isBinderAlive == false }?.let { discard(it) }
        val connection = service ?: BoundService().also { created ->
            service = created
            try {
                created.bound = context.bindService(Intent(context, SourceRuleService::class.java), created, Context.BIND_AUTO_CREATE)
                if (!created.bound) throw RuleEvaluationException("无法启动规则隔离服务")
            } catch (failure: Exception) {
                discard(created, failure)
                throw failure
            }
        }
        connection.pending = finished
        connection
    }

    private fun release(connection: BoundService, healthy: Boolean) = synchronized(connectionLock) {
        connection.pending = null
        if (!healthy) {
            discard(connection)
        } else if (service === connection && !connection.closed) {
            cancelIdleRelease()
            val expiry = object : Runnable {
                override fun run() = synchronized(connectionLock) {
                    // 已移除但正在投递的旧定时器，也不能释放后续请求或替代连接。
                    if (idleRelease === this && service === connection && connection.pending == null) {
                        idleRelease = null
                        discard(connection)
                    }
                }
            }
            idleRelease = expiry
            mainHandler.postDelayed(expiry, IDLE_RELEASE_MS)
        }
    }

    private fun cancelIdleRelease() {
        idleRelease?.let(mainHandler::removeCallbacks)
        idleRelease = null
    }

    /** 调用方持有 connectionLock；只解绑指定对象，旧死亡回调不影响新连接。 */
    private fun discard(connection: BoundService, failure: Throwable = RuleEvaluationException("规则隔离连接已释放")) {
        if (connection.closed) return
        connection.closed = true
        if (service === connection) {
            service = null
            cancelIdleRelease()
        }
        connection.connected.completeExceptionally(failure)
        connection.pending?.completeExceptionally(failure)
        connection.pending = null
        if (connection.bound) {
            connection.bound = false
            context.unbindService(connection)
        }
    }

    private inner class BoundService : ServiceConnection {
        val connected = CompletableDeferred<Messenger>()
        var remote: Messenger? = null
        var pending: CompletableDeferred<String?>? = null
        var bound = false
        var closed = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) = synchronized(connectionLock) {
            if (!closed && service === this) {
                val messenger = Messenger(binder)
                remote = messenger
                connected.complete(messenger)
            }
            Unit
        }

        override fun onServiceDisconnected(name: ComponentName) = synchronized(connectionLock) {
            discard(this, RuleEvaluationException("规则隔离进程已停止，可能因执行超时或数据过大"))
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

        override fun onNullBinding(name: ComponentName) = synchronized(connectionLock) {
            discard(this, RuleEvaluationException("无法连接规则隔离服务"))
        }
    }

    /** 取消时立即关闭 FD，使阻塞读写实际退出，不依赖线程中断来关闭 Linux 管道。 */
    private suspend fun <T> pipeIo(descriptor: ParcelFileDescriptor, action: (ParcelFileDescriptor) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { descriptor.close() } }
            io.execute {
                try {
                    val value = action(descriptor)
                    if (continuation.isActive) continuation.resume(value)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }

    private companion object {
        const val IDLE_RELEASE_MS = 10_000L
        // 全应用同一条计算队列；下载并发只并发网络，不并发不可信规则。
        val requestMutex = Mutex()
        val nextRequest = AtomicLong()
        val io = Executors.newCachedThreadPool { runnable -> Thread(runnable, "source-rule-pipe").apply { isDaemon = true } }
    }
}
