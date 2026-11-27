package io.github.chuck852.linksonda.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext

internal data class HttpCall(
    val index: Int,
    val method: String,
    val path: String,
    val headers: Map<String, String>,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

internal data class HttpResponseSpec(
    val status: Int = 200,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
    val delayBeforeHeadersMillis: Long = 0,
    val bodyChunkMillis: Long = 0,
    val bodyChunkSize: Int = 0,
    val abort: Boolean = false,
)

internal class LocalHttpServer(
    bindAddress: String = "127.0.0.1",
    https: Boolean = false,
    sslContext: SSLContext = TestTls.serverContext,
    private val handler: (HttpCall) -> HttpResponseSpec,
) : AutoCloseable {
    private val address = InetAddress.getByName(bindAddress)
    private val server: HttpServer = if (https) {
        HttpsServer.create(InetSocketAddress(address, 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(sslContext)
        }
    } else {
        HttpServer.create(InetSocketAddress(address, 0), 0)
    }
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "test-http-${server.address.port}").apply { isDaemon = true }
    }
    private val callCounter = AtomicInteger()
    private val calls = CopyOnWriteArrayList<HttpCall>()
    private val finished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()

    init {
        server.createContext("/") { exchange ->
            val call = HttpCall(
                index = callCounter.incrementAndGet(),
                method = exchange.requestMethod,
                path = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""),
                headers = exchange.requestHeaders.entries.associate { entry ->
                    entry.key to entry.value.joinToString(", ")
                },
            )
            calls.add(call)
            try {
                respond(exchange, handler(call))
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                runCatching {
                    exchange.sendResponseHeaders(500, -1)
                }
            } finally {
                exchange.close()
                finished.countDown()
            }
        }
        server.executor = executor
        server.start()
    }

    val host: String = bindAddress
    val port: Int get() = server.address.port
    val scheme: String = if (https) "https" else "http"

    fun url(path: String = "/"): String {
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$scheme://$hostPart:$port$path"
    }

    fun awaitCalls(count: Int, timeoutMillis: Long = 3_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (calls.size < count && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        return calls.size >= count
    }

    fun calls(): List<HttpCall> = calls.toList()

    fun failure(): Throwable? = failure.get()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        finished.await(1, TimeUnit.SECONDS)
    }

    private fun respond(exchange: HttpExchange, response: HttpResponseSpec) {
        if (response.abort) return
        if (response.delayBeforeHeadersMillis > 0) {
            Thread.sleep(response.delayBeforeHeadersMillis)
        }
        response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        val noBody = exchange.requestMethod == "HEAD" ||
            response.status == 204 ||
            response.status == 304 ||
            response.body.isEmpty()
        if (noBody) {
            exchange.sendResponseHeaders(response.status, -1)
            return
        }
        exchange.sendResponseHeaders(response.status, response.body.size.toLong())
        if (response.bodyChunkSize > 0) {
            var offset = 0
            while (offset < response.body.size) {
                val length = minOf(response.bodyChunkSize, response.body.size - offset)
                exchange.responseBody.write(response.body, offset, length)
                exchange.responseBody.flush()
                offset += length
                if (offset < response.body.size && response.bodyChunkMillis > 0) {
                    Thread.sleep(response.bodyChunkMillis)
                }
            }
        } else {
            exchange.responseBody.write(response.body)
        }
    }
}
