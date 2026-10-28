package com.mirad.reader.data.sources

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.Executors

internal class SourceHttpFixture : Closeable {
    private val workers = Executors.newCachedThreadPool()
    val server: HttpServer = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).apply {
        executor = workers
        start()
    }
    val base = "http://127.0.0.1:${server.address.port}"

    fun route(path: String, block: (HttpExchange) -> Unit) { server.createContext(path, block) }

    override fun close() {
        server.stop(0)
        workers.shutdownNow()
    }
}

internal fun HttpExchange.reply(text: String, status: Int = 200, charset: Charset = Charsets.UTF_8) {
    val bytes = text.toByteArray(charset)
    responseHeaders.set("Content-Type", "text/html; charset=${charset.name()}")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
    close()
}
