package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsObservation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.DnsTransport
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ExecutorService
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
 * System resolver and direct DNS runner.
 *
 * The system API exposes only resolved addresses, so the system path records an answer count and
 * deliberately leaves RCODE and truncation unset. Direct DNS exchanges are parsed from the wire.
 */
internal class DnsProbeRunner internal constructor(
    hostResolver: HostResolver,
    dnsExecutor: ExecutorService = DNS_EXECUTOR,
) : ProbeRunner {
    constructor() : this(SystemHostResolver())

    private val resolver = BoundedHostResolver(hostResolver, dnsExecutor)
    private val random = SecureRandom()

    override suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult {
        val probe = request.config.request
        if (probe !is ProbeRequest.SystemDns && probe !is ProbeRequest.DirectDns) {
            return skipped(request, SkipReason.UNSUPPORTED)
        }

        val startNanos = System.nanoTime()
        val phases = mutableMapOf<ProbePhase, Duration>()
        val result = withTimeoutOrNull(request.config.timeout) {
            when (probe) {
                is ProbeRequest.SystemDns -> runSystem(request, probe, admission, startNanos, phases)
                is ProbeRequest.DirectDns -> runDirect(request, probe, admission, startNanos, phases)
            }
        }
        currentCoroutineContext().ensureActive()
        return result ?: completed(
            request = request,
            startNanos = startNanos,
            phases = phases,
            outcome = ProbeOutcome.Failed(FailureReason.TIMEOUT),
        )
    }

    private suspend fun runSystem(
        request: ProbeRunRequest,
        probe: ProbeRequest.SystemDns,
        admission: ProbeAdmission,
        startNanos: Long,
        phases: MutableMap<ProbePhase, Duration>,
    ): ProbeResult {
        val question = probe.question
        val family = when (question.recordType) {
            A -> IpFamily.IPV4
            AAAA -> IpFamily.IPV6
            else -> return skipped(request, SkipReason.UNSUPPORTED)
        }
        if (probe.expected.responseCode != 0) {
            return skipped(request, SkipReason.UNSUPPORTED)
        }

        val host = normalizeDnsName(question.name)
            ?: return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
        val decision = admission.authorize(
            ProbeAttempt(endpointKey = host, infrastructureKey = SYSTEM_DNS),
        )
        if (decision is AdmissionDecision.Denied) {
            return skipped(request, decision)
        }

        val dnsStart = System.nanoTime()
        val addresses = try {
            resolver.resolve(host, family)
        } catch (cancelled: CancellationException) {
            record(phases, ProbePhase.DNS, dnsStart)
            throw cancelled
        } catch (error: DnsCapacityExceededException) {
            return completed(request, startNanos, phases, ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION))
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

        val matching = addresses.filter { it.family() == family }
        if (matching.isEmpty()) {
            return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
        }
        return completed(
            request,
            startNanos,
            phases,
            ProbeOutcome.Matched,
            dns = DnsObservation(answerCount = matching.size),
        )
    }

    private suspend fun runDirect(
        request: ProbeRunRequest,
        probe: ProbeRequest.DirectDns,
        admission: ProbeAdmission,
        startNanos: Long,
        phases: MutableMap<ProbePhase, Duration>,
    ): ProbeResult {
        if (probe.expected.responseCode > MAX_DNS_RCODE) {
            return skipped(request, SkipReason.UNSUPPORTED)
        }
        val questionHost = normalizeDnsName(probe.question.name)
            ?: return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
        val resolverHost = normalizeHostKey(probe.resolver.host)
        if (resolverHost.isEmpty()) {
            return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
        }
        val resolverKey = resolverHost
        val addresses = when {
            isIpLiteral(resolverHost) -> {
                val address = try {
                    InetAddress.getByName(resolverHost)
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
                if (!address.matches(probe.resolver.family)) {
                    return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
                }
                listOf(address)
            }
            else -> {
                val decision = admission.authorize(
                    ProbeAttempt(endpointKey = resolverHost, infrastructureKey = SYSTEM_DNS),
                )
                if (decision is AdmissionDecision.Denied) {
                    return skipped(request, decision)
                }
                val dnsStart = System.nanoTime()
                val resolved = try {
                    resolver.resolve(resolverHost, probe.resolver.family)
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
                val matching = resolved
                    .filter { it.matches(probe.resolver.family) }
                    .sortedBy { it.family() !in request.network.ipFamilies }
                if (matching.isEmpty()) {
                    return completed(request, startNanos, phases, ProbeOutcome.Failed(FailureReason.DNS))
                }
                matching
            }
        }

        var lastFailure: DirectAttempt.Done? = null
        for ((index, address) in addresses.withIndex()) {
            val now = System.nanoTime()
            val remaining = request.config.timeout.inWholeNanoseconds - (now - startNanos)
            val addressCount = addresses.size - index
            val budget = (remaining.coerceAtLeast(MIN_ATTEMPT_NANOS) / addressCount)
                .coerceAtLeast(MIN_ATTEMPT_NANOS)
            val deadlineNanos = now + budget
            val attempt = when (probe.transport) {
                DnsTransport.UDP -> runUdp(
                    address = address,
                    port = probe.resolver.port,
                    question = probe.question,
                    expected = probe.expected,
                    questionHost = questionHost,
                    resolverKey = resolverKey,
                    admission = admission,
                    phases = phases,
                    deadlineNanos = deadlineNanos,
                )
                DnsTransport.TCP -> runTcp(
                    address = address,
                    port = probe.resolver.port,
                    question = probe.question,
                    expected = probe.expected,
                    questionHost = questionHost,
                    resolverKey = resolverKey,
                    admission = admission,
                    phases = phases,
                    deadlineNanos = deadlineNanos,
                    fallback = null,
                )
            }
            when (attempt) {
                is DirectAttempt.Denied -> return skipped(request, attempt.decision)
                is DirectAttempt.Done -> {
                    if (attempt.outcome == ProbeOutcome.Matched) {
                        return completed(
                            request,
                            startNanos,
                            phases,
                            attempt.outcome,
                            dns = attempt.observation,
                        )
                    }
                    lastFailure = attempt
                    if (!attempt.retryable || attempt.outcome !is ProbeOutcome.Failed) {
                        return completed(
                            request,
                            startNanos,
                            phases,
                            attempt.outcome,
                            dns = attempt.observation,
                        )
                    }
                }
            }
        }
        val failure = lastFailure
        return completed(
            request,
            startNanos,
            phases,
            failure?.outcome ?: ProbeOutcome.Failed(FailureReason.DNS),
            dns = failure?.observation,
        )
    }

    private suspend fun runUdp(
        address: InetAddress,
        port: Int,
        question: DnsQuestion,
        expected: DnsExpectation,
        questionHost: String,
        resolverKey: String,
        admission: ProbeAdmission,
        phases: MutableMap<ProbePhase, Duration>,
        deadlineNanos: Long,
    ): DirectAttempt {
        val decision = admission.authorize(
            ProbeAttempt(endpointKey = questionHost, infrastructureKey = resolverKey),
        )
        if (decision is AdmissionDecision.Denied) return DirectAttempt.Denied(decision)

        val socket = try {
            DatagramSocket()
        } catch (error: Exception) {
            return DirectAttempt.Done(classifyDnsIo(error), retryable = true)
        }
        try {
            try {
                socket.connect(InetSocketAddress(address, port))
                socket.soTimeout = remainingMillis(deadlineNanos)
            } catch (error: Exception) {
                return DirectAttempt.Done(classifyDnsIo(error), retryable = true)
            }

            val query = DnsWire.query(random.nextInt(0x10000), question)
            val dnsStart = System.nanoTime()
            val response = try {
                exchangeUdp(socket, query)
            } catch (cancelled: CancellationException) {
                record(phases, ProbePhase.DNS, dnsStart)
                throw cancelled
            } catch (error: Exception) {
                record(phases, ProbePhase.DNS, dnsStart)
                return DirectAttempt.Done(classifyDnsIo(error), retryable = true)
            }
            record(phases, ProbePhase.DNS, dnsStart)

            val message = try {
                DnsWire.parseResponse(response, expectedId = queryId(query), expected = question)
            } catch (error: MalformedDnsResponseException) {
                return DirectAttempt.Done(
                    ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE),
                    retryable = true,
                )
            }
            if (message.truncated) {
                socket.closeQuietly()
                return runTcp(
                    address = address,
                    port = port,
                    question = question,
                    expected = expected,
                    questionHost = questionHost,
                    resolverKey = resolverKey,
                    admission = admission,
                    phases = phases,
                    deadlineNanos = deadlineNanos,
                    fallback = DnsObservation(
                        responseCode = message.responseCode,
                        answerCount = message.answerCount,
                        truncated = true,
                    ),
                )
            }
            return evaluate(message, expected, truncated = false)
        } finally {
            socket.closeQuietly()
        }
    }

    private suspend fun runTcp(
        address: InetAddress,
        port: Int,
        question: DnsQuestion,
        expected: DnsExpectation,
        questionHost: String,
        resolverKey: String,
        admission: ProbeAdmission,
        phases: MutableMap<ProbePhase, Duration>,
        deadlineNanos: Long,
        fallback: DnsObservation?,
    ): DirectAttempt {
        val decision = admission.authorize(
            ProbeAttempt(endpointKey = questionHost, infrastructureKey = resolverKey),
        )
        if (decision is AdmissionDecision.Denied) return DirectAttempt.Denied(decision)

        val socket = try {
            Socket()
        } catch (error: Exception) {
            return DirectAttempt.Done(classifyConnectFailure(error), fallback, retryable = true)
        }
        try {
            val connectStart = System.nanoTime()
            try {
                socket.connectCancellable(
                    address = address,
                    port = port,
                    timeoutMillis = remainingMillis(deadlineNanos),
                )
            } catch (cancelled: CancellationException) {
                record(phases, ProbePhase.TCP_CONNECT, connectStart)
                throw cancelled
            } catch (error: Exception) {
                record(phases, ProbePhase.TCP_CONNECT, connectStart)
                return DirectAttempt.Done(classifyConnectFailure(error), fallback, retryable = true)
            }
            record(phases, ProbePhase.TCP_CONNECT, connectStart)
            socket.soTimeout = remainingMillis(deadlineNanos)

            val query = DnsWire.query(random.nextInt(0x10000), question)
            val dnsStart = System.nanoTime()
            val response = try {
                exchangeTcp(socket, query)
            } catch (cancelled: CancellationException) {
                record(phases, ProbePhase.DNS, dnsStart)
                throw cancelled
            } catch (error: Exception) {
                record(phases, ProbePhase.DNS, dnsStart)
                return DirectAttempt.Done(classifyDnsIo(error), fallback, retryable = true)
            }
            record(phases, ProbePhase.DNS, dnsStart)

            val message = try {
                DnsWire.parseResponse(response, expectedId = queryId(query), expected = question)
            } catch (error: MalformedDnsResponseException) {
                return DirectAttempt.Done(
                    ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE),
                    fallback,
                    retryable = true,
                )
            }
            if (message.truncated) {
                return DirectAttempt.Done(
                    ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE),
                    DnsObservation(
                        responseCode = message.responseCode,
                        answerCount = message.answerCount,
                        truncated = true,
                    ),
                )
            }
            return evaluate(message, expected, truncated = fallback != null)
        } finally {
            socket.closeQuietly()
        }
    }

    private fun evaluate(
        message: DnsMessage,
        expected: DnsExpectation,
        truncated: Boolean,
    ): DirectAttempt.Done {
        val observation = DnsObservation(
            responseCode = message.responseCode,
            answerCount = message.answerCount,
            truncated = truncated,
        )
        val outcome = when {
            message.responseCode != expected.responseCode ->
                ProbeOutcome.Failed(FailureReason.UNEXPECTED_DNS_RESPONSE_CODE)
            expected.requiresAnswer && message.matchingAnswerCount == 0 ->
                ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE)
            else -> ProbeOutcome.Matched
        }
        return DirectAttempt.Done(outcome, observation)
    }

    private suspend fun exchangeUdp(socket: DatagramSocket, query: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { socket.closeQuietly() }
                try {
                    socket.send(DatagramPacket(query, query.size))
                    val packet = DatagramPacket(ByteArray(MAX_DNS_MESSAGE), MAX_DNS_MESSAGE)
                    socket.receive(packet)
                    continuation.resumeWith(
                        Result.success(packet.data.copyOfRange(0, packet.length)),
                    )
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private suspend fun exchangeTcp(socket: Socket, query: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { socket.closeQuietly() }
                try {
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeShort(query.size)
                    output.write(query)
                    output.flush()
                    val input = DataInputStream(socket.getInputStream())
                    val length = input.readUnsignedShort()
                    if (length <= 0) throw MalformedDnsResponseException("empty TCP response")
                    val response = ByteArray(length)
                    input.readFully(response)
                    continuation.resumeWith(Result.success(response))
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private fun classifyDnsIo(error: Throwable): ProbeOutcome = when {
        error.isPermissionDenied() -> ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
        error is SocketTimeoutException -> ProbeOutcome.Failed(FailureReason.TIMEOUT)
        error is PortUnreachableException -> ProbeOutcome.Failed(FailureReason.REFUSED)
        error is MalformedDnsResponseException -> ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE)
        else -> ProbeOutcome.Failed(FailureReason.DNS)
    }

    private fun completed(
        request: ProbeRunRequest,
        startNanos: Long,
        phases: Map<ProbePhase, Duration>,
        outcome: ProbeOutcome,
        dns: DnsObservation? = null,
    ): ProbeResult.Completed = ProbeResult.Completed(
        context = request.context,
        recordedAt = Instant.now(),
        timing = ProbeTiming(
            elapsed = (System.nanoTime() - startNanos).nanoseconds,
            phases = phases.toMap(),
        ),
        outcome = outcome,
        observations = ProbeObservations(dns = dns),
    )

    private fun skipped(request: ProbeRunRequest, reason: SkipReason): ProbeResult.Skipped =
        ProbeResult.Skipped(
            context = request.context,
            recordedAt = Instant.now(),
            reason = reason,
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

    private fun record(
        phases: MutableMap<ProbePhase, Duration>,
        phase: ProbePhase,
        startNanos: Long,
    ) {
        phases[phase] = (System.nanoTime() - startNanos).nanoseconds
    }

    private fun InetAddress.matches(family: IpFamily?): Boolean =
        family == null || family == this.family()

    private fun InetAddress.family(): IpFamily =
        if (this is Inet4Address) IpFamily.IPV4 else IpFamily.IPV6

    private fun Throwable.isPermissionDenied(): Boolean =
        this is SecurityException ||
            (this is SocketException && message?.let { text ->
                text.contains("EACCES", ignoreCase = true) ||
                    text.contains("EPERM", ignoreCase = true) ||
                    text.contains("permission denied", ignoreCase = true)
            } == true)

    private fun normalizeDnsName(value: String): String? {
        val name = try {
            IDN.toASCII(value.trim().removeSuffix("."), IDN.ALLOW_UNASSIGNED)
        } catch (error: IllegalArgumentException) {
            return null
        }
        if (name.isEmpty() || name.length > 253) return null
        if (name.split('.').any { it.isEmpty() || it.length > 63 }) return null
        return name.lowercase()
    }

    private fun normalizeHostKey(value: String): String {
        val trimmed = value.trim()
        val withoutBrackets = if (trimmed.length >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return withoutBrackets.removeSuffix(".").lowercase()
    }

    private fun isIpLiteral(value: String): Boolean {
        val host = normalizeHostKey(value)
        val ipv4 = IPV4.matches(host) && host.split('.').all { it.toIntOrNull() in 0..255 }
        return host.count { it == ':' } >= 2 || ipv4
    }

    private fun queryId(query: ByteArray): Int =
        ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)

    private fun remainingMillis(deadlineNanos: Long): Int =
        ((deadlineNanos - System.nanoTime()).coerceAtLeast(MIN_ATTEMPT_NANOS) / 1_000_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private fun DatagramSocket.closeQuietly() {
        runCatching { close() }
    }

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

    private sealed interface DirectAttempt {
        data class Done(
            val outcome: ProbeOutcome,
            val observation: DnsObservation? = null,
            val retryable: Boolean = false,
        ) : DirectAttempt

        data class Denied(val decision: AdmissionDecision.Denied) : DirectAttempt
    }

    private companion object {
        const val A = 1
        const val AAAA = 28
        const val MAX_DNS_RCODE = 15
        const val MIN_ATTEMPT_NANOS = 1_000_000L
        const val SYSTEM_DNS = "system-dns"
        const val MAX_DNS_MESSAGE = 65_535
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
