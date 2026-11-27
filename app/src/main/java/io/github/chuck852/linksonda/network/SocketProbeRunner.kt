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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException
import java.time.Instant
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Bounded TCP Connect and TLS handshake runner.
 *
 * TLS uses the platform trust store and HTTPS endpoint identification. SNI is sent only for DNS
 * names. The TLS handshake is part of the socket attempt authorized before connecting.
 */
class SocketProbeRunner internal constructor(
    private val hostResolver: HostResolver,
    private val sslContext: SSLContext,
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
        return try {
            withTimeout(request.config.timeout) {
                execute(request, probe, admission, startNanos, phases)
            }
        } catch (timeout: TimeoutCancellationException) {
            completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.TIMEOUT))
        }
    }

    private suspend fun execute(
        request: ProbeRunRequest,
        probe: ProbeRequest,
        admission: ProbeAdmission,
        startNanos: Long,
        phases: MutableMap<ProbePhase, Duration>,
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

        val address: InetAddress
        if (isIpLiteral(host)) {
            address = try {
                InetAddress.getByName(host)
            } catch (error: UnknownHostException) {
                return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
            }
            if (!address.matches(endpoint.family)) {
                return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
            }
        } else {
            val decision = admission.authorize(
                ProbeAttempt(endpointKey = "dns:$host", infrastructureKey = "system-dns"),
            )
            if (decision is AdmissionDecision.Denied) {
                return skipped(request, decision)
            }

            val dnsStart = System.nanoTime()
            val addresses = try {
                hostResolver.resolve(host, endpoint.family)
            } catch (cancelled: CancellationException) {
                record(phases, ProbePhase.DNS, dnsStart)
                throw cancelled
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
                return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
            }
            record(phases, ProbePhase.DNS, dnsStart)
            address = addresses.firstOrNull { it.matches(endpoint.family) }
                ?: return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
        }

        val target = if (tls != null) serverName else host
        val scheme = if (tls != null) "tls" else "tcp"
        val decision = admission.authorize(
            ProbeAttempt(
                endpointKey = "$scheme:$target:${endpoint.port}",
                infrastructureKey = "$scheme:$target",
            ),
        )
        if (decision is AdmissionDecision.Denied) {
            return skipped(request, decision)
        }

        val socket = try {
            Socket()
        } catch (error: Exception) {
            return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.TCP))
        }
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
                return completed(
                    request,
                    startNanos,
                    phases,
                    connectFailure(error),
                )
            }
            record(phases, ProbePhase.TCP_CONNECT, connectStart)

            val family = address.family()
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
                val sslSocket = createSslSocket(socket, serverName, endpoint.port, tls.minimumVersion)
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
                    tlsFailure(error),
                    ProbeObservations(connectedIpFamily = family),
                )
            }
        } finally {
            socket.closeQuietly()
        }
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

    private fun connectFailure(error: Throwable): ProbeOutcome = when (error) {
        is SocketTimeoutException -> ProbeOutcome.Failed(FailureReason.TIMEOUT)
        is NoRouteToHostException -> ProbeOutcome.Failed(FailureReason.UNREACHABLE)
        is ConnectException -> ProbeOutcome.Failed(FailureReason.REFUSED)
        is SecurityException -> ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
        else -> ProbeOutcome.Failed(FailureReason.TCP)
    }

    private fun tlsFailure(error: Throwable): ProbeOutcome = when (error) {
        is SocketTimeoutException -> ProbeOutcome.Failed(FailureReason.TIMEOUT)
        is SecurityException -> ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
        else -> ProbeOutcome.Failed(FailureReason.TLS)
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
        return host.contains(':') || IPV4.matches(host)
    }

    private fun tlsVersion(protocol: String): TlsVersion? = when (protocol) {
        "TLSv1.2" -> TlsVersion.TLS_1_2
        "TLSv1.3" -> TlsVersion.TLS_1_3
        else -> null
    }
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private fun defaultSslContext(): SSLContext = try {
    SSLContext.getDefault()
} catch (error: NoSuchAlgorithmException) {
    throw IllegalStateException("Default TLS context is unavailable", error)
}
