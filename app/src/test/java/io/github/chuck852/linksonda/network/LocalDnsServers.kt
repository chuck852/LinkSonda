package io.github.chuck852.linksonda.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class UdpDnsServer(
    bindAddress: String = "127.0.0.1",
    port: Int = 0,
    private val handler: (ByteArray) -> ByteArray? = { it },
) : AutoCloseable {
    private val socket = DatagramSocket(port, InetAddress.getByName(bindAddress))
    private val received = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val closing = AtomicBoolean(false)
    private val query = AtomicReference<ByteArray?>()
    private val failure = AtomicReference<Throwable?>()
    private val worker = thread(isDaemon = true, name = "test-dns-udp-${socket.localPort}") {
        try {
            val packet = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(packet)
            val bytes = packet.data.copyOfRange(0, packet.length)
            query.set(bytes)
            received.countDown()
            handler(bytes)?.let { response ->
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            }
        } catch (error: Throwable) {
            if (!closing.get()) failure.set(error)
            received.countDown()
        } finally {
            finished.countDown()
        }
    }

    val port: Int get() = socket.localPort

    fun awaitReceived(timeoutMillis: Long = 3_000): Boolean =
        received.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitFinished(timeoutMillis: Long = 3_000): Boolean =
        finished.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun query(): ByteArray? = query.get()

    fun failure(): Throwable? = failure.get()

    override fun close() {
        closing.set(true)
        socket.close()
        worker.join(2_000)
    }
}

internal class TcpDnsServer(
    bindAddress: String = "127.0.0.1",
    port: Int = 0,
    private val handler: (ByteArray) -> ByteArray? = { it },
    private val responseChunkDelayMillis: Long = 0,
) : AutoCloseable {
    private val server = ServerSocket(port, 50, InetAddress.getByName(bindAddress))
    private val accepted = CountDownLatch(1)
    private val received = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val closing = AtomicBoolean(false)
    private val query = AtomicReference<ByteArray?>()
    private val failure = AtomicReference<Throwable?>()
    private val worker = thread(isDaemon = true, name = "test-dns-tcp-${server.localPort}") {
        try {
            server.accept().use { socket ->
                accepted.countDown()
                val input = DataInputStream(socket.getInputStream())
                val length = input.readUnsignedShort()
                val bytes = ByteArray(length).also(input::readFully)
                query.set(bytes)
                received.countDown()
                val response = handler(bytes)
                if (response != null) {
                    DataOutputStream(socket.getOutputStream()).use { output ->
                        output.writeShort(response.size)
                        output.flush()
                        if (responseChunkDelayMillis == 0L) {
                            output.write(response)
                            output.flush()
                        } else {
                            response.forEach { byte ->
                                Thread.sleep(responseChunkDelayMillis)
                                output.write(byte.toInt())
                                output.flush()
                            }
                        }
                    }
                } else {
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        } catch (error: Throwable) {
            if (!closing.get()) failure.set(error)
            accepted.countDown()
            received.countDown()
        } finally {
            finished.countDown()
        }
    }

    val port: Int get() = server.localPort

    fun awaitAccepted(timeoutMillis: Long = 3_000): Boolean =
        accepted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitReceived(timeoutMillis: Long = 3_000): Boolean =
        received.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitFinished(timeoutMillis: Long = 3_000): Boolean =
        finished.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun query(): ByteArray? = query.get()

    fun failure(): Throwable? = failure.get()

    override fun close() {
        closing.set(true)
        release.countDown()
        server.close()
        worker.join(2_500)
    }
}
