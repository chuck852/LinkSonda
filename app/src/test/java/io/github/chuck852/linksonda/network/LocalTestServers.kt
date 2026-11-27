package io.github.chuck852.linksonda.network

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

internal object TestTls {
    private const val PASSWORD = "changeit"

    private val store: KeyStore by lazy { generateStore("dns:localhost,ip:127.0.0.1") }
    private val storeWithoutIpSan: KeyStore by lazy { generateStore("dns:localhost") }
    val serverContext: SSLContext by lazy { context(store) }
    val clientContext: SSLContext by lazy { context(store) }
    val serverContextWithoutIpSan: SSLContext by lazy { context(storeWithoutIpSan) }
    val clientContextWithoutIpSan: SSLContext by lazy { context(storeWithoutIpSan) }
    val clientTrustManager: X509TrustManager by lazy { trustManager(store) }

    private fun context(store: KeyStore): SSLContext {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, PASSWORD.toCharArray())
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, arrayOf(trustManager(store)), null)
        }
    }

    private fun trustManager(store: KeyStore): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private fun generateStore(san: String): KeyStore {
        val directory = Files.createTempDirectory("linksonda-tls-test").toFile()
        val file = File(directory, "test.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        check(keytool.isFile) { "keytool not found at $keytool" }
        val process = ProcessBuilder(
            keytool.absolutePath,
            "-genkeypair",
            "-alias", "test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "2",
            "-dname", "CN=localhost",
            "-ext", "SAN=$san",
            "-storetype", "PKCS12",
            "-keystore", file.absolutePath,
            "-storepass", PASSWORD,
            "-keypass", PASSWORD,
            "-noprompt",
        ).redirectErrorStream(true).start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "keytool timed out" }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "keytool failed: $output" }
        return KeyStore.getInstance("PKCS12").apply {
            file.inputStream().use { load(it, PASSWORD.toCharArray()) }
        }
    }
}

internal class TcpServer(
    bindAddress: String = "127.0.0.1",
    private val onAccepted: (Socket) -> Unit = {},
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName(bindAddress))
    private val accepted = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val closing = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>()
    private val worker = thread(isDaemon = true, name = "test-tcp-${server.localPort}") {
        try {
            server.accept().use { socket ->
                accepted.countDown()
                onAccepted(socket)
            }
        } catch (error: Throwable) {
            if (!closing.get()) failure.set(error)
            accepted.countDown()
        } finally {
            finished.countDown()
        }
    }

    val port: Int get() = server.localPort

    fun awaitAccepted(timeoutMillis: Long = 3_000): Boolean =
        accepted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitFinished(timeoutMillis: Long = 3_000): Boolean =
        finished.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun failure(): Throwable? = failure.get()

    override fun close() {
        closing.set(true)
        server.close()
        worker.join(2_000)
    }
}

internal class TlsServer(
    bindAddress: String = "127.0.0.1",
    enabledProtocols: Array<String>? = null,
    serverContext: SSLContext = TestTls.serverContext,
    private val onHandshake: (SSLSocket) -> Unit = {},
) : AutoCloseable {
    private val server = (serverContext.serverSocketFactory.createServerSocket(
        0,
        50,
        InetAddress.getByName(bindAddress),
    ) as SSLServerSocket).apply {
        if (enabledProtocols != null) this.enabledProtocols = enabledProtocols
    }
    private val accepted = CountDownLatch(1)
    private val handshakeDone = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val closing = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>()
    val sni = AtomicReference<String?>(null)
    private val worker = thread(isDaemon = true, name = "test-tls-${server.localPort}") {
        try {
            server.accept().use { raw ->
                val socket = raw as SSLSocket
                socket.soTimeout = 3_000
                accepted.countDown()
                socket.startHandshake()
                val session = socket.session as ExtendedSSLSession
                sni.set(session.requestedServerNames.filterIsInstance<SNIHostName>().firstOrNull()?.asciiName)
                handshakeDone.countDown()
                onHandshake(socket)
            }
        } catch (error: Throwable) {
            if (!closing.get()) failure.set(error)
            accepted.countDown()
        } finally {
            handshakeDone.countDown()
            finished.countDown()
        }
    }

    val port: Int get() = server.localPort

    fun awaitAccepted(timeoutMillis: Long = 3_000): Boolean =
        accepted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitHandshake(timeoutMillis: Long = 3_000): Boolean =
        handshakeDone.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitFinished(timeoutMillis: Long = 3_000): Boolean =
        finished.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun handshakeFailure(): Throwable? = failure.get()

    override fun close() {
        closing.set(true)
        server.close()
        worker.join(2_000)
    }
}

internal fun unusedLocalPort(): Int =
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

internal fun ipv6LoopbackAvailable(): Boolean = try {
    ServerSocket(0, 1, InetAddress.getByName("::1")).use { true }
} catch (_: Exception) {
    false
}
