package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeAttempt
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.ProbeRunner
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.TlsObservation
import io.github.chuck852.linksonda.domain.TlsVersion
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded TCP Connect and TLS handshake runner.
 *
 * TLS uses the platform trust store and HTTPS endpoint identification. SNI is sent only for DNS
 * names. The TLS handshake is part of the socket attempt authorized before connecting.
 */
internal class SocketProbeRunner internal constructor(
    private val hostResolver: HostResolver,
    private val sslContext: SSLContext,
    private val dnsExecutor: ExecutorService = DNS_EXECUTOR,
) : ProbeRunner {
    constructor() : this(SystemHostResolver(), defaultSslContext())

    override suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult {
        val probe = request.config.request
        if (probe !is ProbeRequest.TcpConnect && probe !is ProbeRequest.TlsHandshake) {
            return ProbeResult.Skipped(
                context = request.context,
                recordedAt = Instant.now(),
                reason = SkipReason.UNSUPPORTED,
            )
        }

        val startNanos = System.nanoTime()
        val phases = mutableMapOf<ProbePhase, Duration>()
        val state = RunState()
        val result = withTimeoutOrNull(request.config.timeout) {
            execute(request, probe, admission, startNanos, phases, state)
        }
        currentCoroutineContext().ensureActive()
        return result ?: completed(
            request = request,
            startNanos = startNanos,
            phases = phases,
            outcome = ProbeOutcome.Failed(FailureReason.TIMEOUT),
            observations = ProbeObservations(connectedIpFamily = state.connectedIpFamily),
        )
    }

    private suspend fun execute(
        request: ProbeRunRequest,
        probe: ProbeRequest,
        admission: ProbeAdmission,
        startNanos: Long,
        phases: MutableMap<ProbePhase, Duration>,
        state: RunState,
    ): ProbeResult {
        val endpoint = when (probe) {
            is ProbeRequest.TcpConnect -> probe.endpoint
            is ProbeRequest.TlsHandshake -> probe.endpoint
            else -> error("Unsupported request")
        }
        val host = normalizeHost(endpoint.host).lowercase().removeSuffix(".")
        val tls = probe as? ProbeRequest.TlsHandshake
        val serverName = tls?.serverName
            ?.let(::normalizeHost)
            ?.removeSuffix(".")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: host

        val candidates: List<InetAddress>
        if (isIpLiteral(host)) {
            val address = try {
                InetAddress.getByName(host)
            } catch (error: SecurityException) {
                return completed(
                    request,
                    startNanos,
                    phases,
                    ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
                )
            } catch (error: UnknownHostException) {
                return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
            }
            if (!address.matches(endpoint.family)) {
                return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
            }
            candidates = listOf(address)
        } else {
            val decision = admission.authorize(
                ProbeAttempt(endpointKey = host, infrastructureKey = "system-dns"),
            )
            if (decision is AdmissionDecision.Denied) {
                return skipped(request, decision)
            }

            val dnsStart = System.nanoTime()
            val addresses = try {
                resolveBounded(host, endpoint.family)
            } catch (cancelled: CancellationException) {
                record(phases, ProbePhase.DNS, dnsStart)
                throw cancelled
            } catch (error: DnsCapacityExceededException) {
                return completed(
                    request,
                    startNanos,
                    phases,
                    ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION),
                )
            } catch (error: UnknownHostException) {
                record(phases, ProbePhase.DNS, dnsStart)
                return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
            } catch (error: SecurityException) {
                record(phases, ProbePhase.DNS, dnsStart)
                return completed(
                    request,
                    startNanos,
                    phases,
                    ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
                )
            } catch (error: IOException) {
                record(phases, ProbePhase.DNS, dnsStart)
                return completed(
                    request,
                    startNanos,
                    phases,
                    if (error.isPermissionDenied()) {
                        ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
                    } else {
                        ProbeOutcome.Failed(FailureReason.DNS)
                    },
                )
            }
            record(phases, ProbePhase.DNS, dnsStart)
            candidates = addresses
                .filter { it.matches(endpoint.family) }
                .sortedBy { it.family() !in request.network.ipFamilies }
            if (candidates.isEmpty()) {
                return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
            }
        }

        var lastFailure: ProbeOutcome? = null
        for (address in candidates) {
            val decision = admission.authorize(
                ProbeAttempt(endpointKey = host, infrastructureKey = host),
            )
            if (decision is AdmissionDecision.Denied) {
                return skipped(request, decision)
            }

            val socket = try {
                Socket()
            } catch (error: Exception) {
                return completed(request, startNanos, phases, classifyConnectFailure(error))
            }
            var sslSocket: SSLSocket? = null
            try {
                val connectStart = System.nanoTime()
                try {
                    socket.connectCancellable(
                        address = address,
                        port = endpoint.port,
                        timeoutMillis = timeoutMillis(request.config.timeout),
                    )
                } catch (cancelled: CancellationException) {
                    record(phases, ProbePhase.TCP_CONNECT, connectStart)
                    throw cancelled
                } catch (error: Exception) {
                    record(phases, ProbePhase.TCP_CONNECT, connectStart)
                    val failure = classifyConnectFailure(error)
                    lastFailure = failure
                    if (failure is ProbeOutcome.Inconclusive) {
                        return completed(request, startNanos, phases, failure)
                    }
                    continue
                }
                record(phases, ProbePhase.TCP_CONNECT, connectStart)

                val family = address.family()
                state.connectedIpFamily = family
                if (tls == null) {
                    return completed(
                        request,
                        startNanos,
                        phases,
                        ProbeOutcome.Matched,
                        ProbeObservations(connectedIpFamily = family),
                    )
                }

                val handshakeStart = System.nanoTime()
                try {
                    socket.soTimeout = timeoutMillis(request.config.timeout)
                    sslSocket = createSslSocket(socket, serverName, endpoint.port, tls.minimumVersion)
                    sslSocket.startHandshakeCancellable()
                    record(phases, ProbePhase.TLS_HANDSHAKE, handshakeStart)
                    val version = tlsVersion(sslSocket.session.protocol)
                        ?: return completed(
                            request,
                            startNanos,
                            phases,
                            ProbeOutcome.Failed(FailureReason.TLS),
                            ProbeObservations(connectedIpFamily = family),
                        )
                    return completed(
                        request,
                        startNanos,
                        phases,
                        ProbeOutcome.Matched,
                        ProbeObservations(
                            connectedIpFamily = family,
                            tls = TlsObservation(version),
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    record(phases, ProbePhase.TLS_HANDSHAKE, handshakeStart)
                    throw cancelled
                } catch (error: Exception) {
                    record(phases, ProbePhase.TLS_HANDSHAKE, handshakeStart)
                    return completed(
                        request,
                        startNanos,
                        phases,
                        classifyTlsFailure(error),
                        ProbeObservations(connectedIpFamily = family),
                    )
                }
            } finally {
                sslSocket?.closeQuietly()
                socket.closeQuietly()
            }
        }
        return completed(
            request,
            startNanos,
            phases,
            lastFailure ?: ProbeOutcome.Failed(FailureReason.TCP),
        )
    }

    private fun createSslSocket(
        socket: Socket,
        serverName: String,
        port: Int,
        minimumVersion: TlsVersion?,
    ): SSLSocket {
        val sslSocket = sslContext.socketFactory.createSocket(socket, serverName, port, true) as SSLSocket
        val parameters = sslSocket.sslParameters
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        if (!isIpLiteral(serverName)) {
            parameters.serverNames = listOf(SNIHostName(serverName))
        }
        val wanted = when (minimumVersion) {
            TlsVersion.TLS_1_3 -> listOf("TLSv1.3")
            TlsVersion.TLS_1_2, null -> listOf("TLSv1.2", "TLSv1.3")
        }
        val enabled = wanted.filter { it in sslSocket.supportedProtocols }
        if (enabled.isEmpty()) {
            throw SSLHandshakeException("Requested TLS version is not supported")
        }
        parameters.protocols = enabled.toTypedArray()
        sslSocket.sslParameters = parameters
        return sslSocket
    }

    private fun completed(
        request: ProbeRunRequest,
        startNanos: Long,
        phases: Map<ProbePhase, Duration>,
        outcome: ProbeOutcome,
        observations: ProbeObservations = ProbeObservations(),
    ): ProbeResult.Completed = ProbeResult.Completed(
        context = request.context,
        recordedAt = Instant.now(),
        timing = ProbeTiming(
            elapsed = (System.nanoTime() - startNanos).nanoseconds,
            phases = phases.toMap(),
        ),
        outcome = outcome,
        observations = observations,
    )

    private fun skipped(
        request: ProbeRunRequest,
        decision: AdmissionDecision.Denied,
    ): ProbeResult.Skipped = ProbeResult.Skipped(
        context = request.context,
        recordedAt = Instant.now(),
        reason = decision.reason,
        nextAllowedAt = decision.nextAllowedAt,
    )

    private suspend fun resolveBounded(host: String, family: IpFamily?): List<InetAddress> =
        suspendCancellableCoroutine { continuation ->
            val future = try {
                dnsExecutor.submit {
                    try {
                        continuation.resumeWith(Result.success(hostResolver.resolve(host, family)))
                    } catch (error: Throwable) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                throw DnsCapacityExceededException()
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }

    private fun record(phases: MutableMap<ProbePhase, Duration>, phase: ProbePhase, startNanos: Long) {
        phases[phase] = (System.nanoTime() - startNanos).nanoseconds
    }

    private fun InetAddress.matches(family: IpFamily?): Boolean =
        family == null || family == this.family()

    private fun InetAddress.family(): IpFamily =
        if (this is Inet4Address) IpFamily.IPV4 else IpFamily.IPV6

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private fun timeoutMillis(timeout: Duration): Int =
        timeout.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    private suspend fun Socket.connectCancellable(
        address: InetAddress,
        port: Int,
        timeoutMillis: Int,
    ): Unit = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { closeQuietly() }
            try {
                connect(InetSocketAddress(address, port), timeoutMillis)
                continuation.resumeWith(Result.success(Unit))
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        }
    }

    private suspend fun SSLSocket.startHandshakeCancellable(): Unit = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { closeQuietly() }
            try {
                startHandshake()
                continuation.resumeWith(Result.success(Unit))
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        }
    }

    private fun normalizeHost(value: String): String {
        val host = value.trim()
        return if (host.length >= 2 && host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        val host = normalizeHost(value)
        val ipv4 = IPV4.matches(host) && host.split('.').all { it.toInt() in 0..255 }
        return host.count { it == ':' } >= 2 || ipv4
    }

    private fun tlsVersion(protocol: String): TlsVersion? = when (protocol) {
        "TLSv1.2" -> TlsVersion.TLS_1_2
        "TLSv1.3" -> TlsVersion.TLS_1_3
        else -> null
    }
}

private class RunState(var connectedIpFamily: IpFamily? = null)

private class DnsCapacityExceededException : Exception("DNS worker capacity exceeded")

internal fun classifyConnectFailure(error: Throwable): ProbeOutcome = when {
    error.isPermissionDenied() -> ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
    error is SocketTimeoutException -> ProbeOutcome.Failed(FailureReason.TIMEOUT)
    error is NoRouteToHostException -> ProbeOutcome.Failed(FailureReason.UNREACHABLE)
    error is ConnectException && error.message?.contains("timed out", ignoreCase = true) == true ->
        ProbeOutcome.Failed(FailureReason.TIMEOUT)
    error is ConnectException && error.message?.contains("unreachable", ignoreCase = true) == true ->
        ProbeOutcome.Failed(FailureReason.UNREACHABLE)
    error is ConnectException -> ProbeOutcome.Failed(FailureReason.REFUSED)
    else -> ProbeOutcome.Failed(FailureReason.TCP)
}

internal fun classifyTlsFailure(error: Throwable): ProbeOutcome = when {
    error.isPermissionDenied() -> ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
    error is SocketTimeoutException -> ProbeOutcome.Failed(FailureReason.TIMEOUT)
    else -> ProbeOutcome.Failed(FailureReason.TLS)
}

private fun Throwable.isPermissionDenied(): Boolean =
    this is SecurityException ||
        (this is SocketException && message?.let { text ->
            text.contains("EACCES", ignoreCase = true) ||
                text.contains("EPERM", ignoreCase = true) ||
                text.contains("permission denied", ignoreCase = true)
        } == true)

private const val DNS_WORKERS = 2
private const val DNS_QUEUE_CAPACITY = 8

private val DNS_EXECUTOR = ThreadPoolExecutor(
    DNS_WORKERS,
    DNS_WORKERS,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue<Runnable>(DNS_QUEUE_CAPACITY),
) { runnable ->
    Thread(runnable, "linksonda-dns").apply { isDaemon = true }
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private fun defaultSslContext(): SSLContext = try {
    SSLContext.getDefault()
} catch (error: NoSuchAlgorithmException) {
    throw IllegalStateException("Default TLS context is unavailable", error)
}
