package io.github.chuck852.linksonda.updater

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterHttpCancellationTest {
    @Test
    fun slowDripBodyIsBoundedByTheRequestDeadline() = runBlocking {
        SlowDripServer().use { server ->
            val started = System.nanoTime()
            val result = HttpUpdaterHttpClient(
                allowTestSource = true,
                getTimeoutMs = 300,
            ).get(server.url)

            assertTrue(result is UpdaterHttpResult.Failure)
            assertTrue(elapsedMs(started) < 2_000)
        }
    }

    @Test
    fun cancellationInterruptsABlockingBodyRead() = runBlocking {
        SlowDripServer().use { server ->
            val job = launch(Dispatchers.Default) {
                HttpUpdaterHttpClient(
                    allowTestSource = true,
                    getTimeoutMs = 30_000,
                ).get(server.url)
            }
            delay(250)
            val started = System.nanoTime()
            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(elapsedMs(started) < 2_000)
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private class SlowDripServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val url: String = "http://127.0.0.1:${server.localPort}/release.json"
        private val worker = thread(isDaemon = true, name = "slow-updater-response") {
            runCatching {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val output = socket.getOutputStream()
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: application/json\r\n\r\n"
                            .toByteArray(),
                    )
                    output.flush()
                    repeat(1000) {
                        output.write('x'.code)
                        output.flush()
                        Thread.sleep(100)
                    }
                }
            }
        }

        override fun close() {
            server.close()
            worker.interrupt()
        }
    }
}
