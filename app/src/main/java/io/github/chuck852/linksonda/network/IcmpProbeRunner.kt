package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.IcmpObservation
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeAttempt
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.ProbeRunner
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.RttSummary
import io.github.chuck852.linksonda.domain.SkipReason
import java.io.FileNotFoundException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ICMP Echo checks through the Android system ping binary.
 *
 * The runner never invokes a shell. Each packet is one `-c 1` process and one admission grant.
 * The payload size is the platform default; packet count and timeout come from the approved request.
 */
internal class IcmpProbeRunner internal constructor(
    private val ping: PingProcessRunner = SystemPingProcessRunner(),
) : ProbeRunner {
    override suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult {
        val probe = request.config.request
        if (probe !is ProbeRequest.IcmpEcho) {
            return skipped(request, SkipReason.UNSUPPORTED)
        }

        val startNanos = System.nanoTime()
        val state = RunState()
        val host = normalizeHost(probe.host)
        if (host == null || !isSafeHost(host)) {
            return completed(
                request,
                startNanos,
                state,
                ProbeOutcome.Failed(FailureReason.ICMP),
            )
        }
        if (familyMismatch(probe.family, host)) {
            return completed(
                request,
                startNanos,
                state,
                ProbeOutcome.Failed(FailureReason.DNS),
            )
        }
        state.targetFamily = literalFamily(host) ?: probe.family

        val result = withTimeoutOrNull(request.config.timeout) {
            execute(request, probe, host, admission, startNanos, state)
        }
        currentCoroutineContext().ensureActive()
        return result ?: completed(
            request,
            startNanos,
            state,
            if (state.received >= probe.minimumReplies) {
                ProbeOutcome.Matched
            } else {
                ProbeOutcome.Failed(FailureReason.TIMEOUT)
            },
        )
    }

    private suspend fun execute(
        request: ProbeRunRequest,
        probe: ProbeRequest.IcmpEcho,
        host: String,
        admission: ProbeAdmission,
        startNanos: Long,
        state: RunState,
    ): ProbeResult {
        val binary = if (probe.family == IpFamily.IPV6 || literalFamily(host) == IpFamily.IPV6) {
            PING6
        } else {
            PING
        }

        for (index in 0 until probe.packetCount) {
            val decision = admission.authorize(ProbeAttempt(host, host))
            if (decision is AdmissionDecision.Denied) {
                return if (state.received >= probe.minimumReplies) {
                    completed(request, startNanos, state, ProbeOutcome.Matched)
                } else {
                    skipped(request, decision)
                }
            }

            val now = System.nanoTime()
            val remaining = request.config.timeout.inWholeNanoseconds - (now - startNanos)
            val packetsLeft = probe.packetCount - index
            val budget = (remaining.coerceAtLeast(MIN_PACKET_NANOS) / packetsLeft)
                .coerceAtLeast(MIN_PACKET_NANOS)
            val command = pingCommand(binary, host, budget, probe.family)
            val execution = try {
                ping.execute(command, budget.nanoseconds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return launchFailure(request, startNanos, state, error)
            }

            when (execution) {
                is PingExecution.LaunchFailed -> {
                    return launchFailure(request, startNanos, state, execution.error)
                }
                is PingExecution.TimedOut -> {
                    state.add(PingOutputParser.summary(execution.output))
                    state.timedOut = true
                }
                is PingExecution.Completed -> {
                    val summary = PingOutputParser.summary(execution.output)
                    if (summary.transmitted == null || summary.received == null) {
                        val outputOutcome = outputOutcome(execution.output)
                        if (outputOutcome != null) {
                            if (state.received >= probe.minimumReplies) {
                                return completed(request, startNanos, state, ProbeOutcome.Matched)
                            }
                            state.lastFailure = outputOutcome
                            return completed(request, startNanos, state, outputOutcome)
                        }
                        state.unknownPackets += 1
                        if (execution.exitCode == 0) {
                            state.lastFailure = ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION)
                        } else {
                            state.timedOut = true
                        }
                    } else {
                        state.add(summary)
                        if (summary.received == 0) {
                            val outputOutcome = outputOutcome(execution.output)
                            if (outputOutcome != null) {
                                if (state.received >= probe.minimumReplies) {
                                    return completed(request, startNanos, state, ProbeOutcome.Matched)
                                }
                                state.lastFailure = outputOutcome
                                return completed(request, startNanos, state, outputOutcome)
                            }
                            state.timedOut = execution.exitCode != 0
                            if (execution.exitCode == 0) {
                                state.lastFailure = ProbeOutcome.Failed(FailureReason.ICMP)
                            }
                        }
                    }
                }
            }
        }

        return completed(request, startNanos, state, resultOutcome(probe, state))
    }

    private fun launchFailure(
        request: ProbeRunRequest,
        startNanos: Long,
        state: RunState,
        error: Throwable,
    ): ProbeResult {
        val message = error.message.orEmpty().lowercase()
        val unsupported = error is FileNotFoundException ||
            message.contains("no such file") ||
            message.contains("not found")
        if (unsupported && state.sent == 0) {
            return skipped(request, SkipReason.UNSUPPORTED)
        }
        val outcome = when {
            unsupported -> ProbeOutcome.Failed(FailureReason.ICMP)
            error is SecurityException ||
                message.contains("permission denied") ||
                message.contains("eacces") ||
                message.contains("eperm") ->
                ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
            message.contains("too many open files") ||
                message.contains("cannot allocate memory") ||
                message.contains("resource temporarily unavailable") ||
                message.contains("selinux") ->
                ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION)
            else -> ProbeOutcome.Failed(FailureReason.ICMP)
        }
        return completed(request, startNanos, state, outcome)
    }

    private fun outputOutcome(output: String): ProbeOutcome? {
        val text = output.lineSequence()
            .map { it.trimStart() }
            .filter {
                it.startsWith("ping:", ignoreCase = true) ||
                    it.startsWith("ping6:", ignoreCase = true) ||
                    it.startsWith("connect:", ignoreCase = true) ||
                    it.startsWith("sendto:", ignoreCase = true)
            }
            .joinToString("\n")
            .lowercase()
        return when {
            text.contains("unknown host") ||
                text.contains("cannot resolve") ||
                text.contains("name or service not known") ||
                text.contains("hostname nor servname provided") ||
                text.contains("no address associated with hostname") ||
                text.contains("temporary failure in name resolution") ||
                text.contains("non-recoverable failure in name resolution") ||
                text.contains("address family for hostname not supported") ->
                ProbeOutcome.Failed(FailureReason.DNS)
            text.contains("network is unreachable") ||
                text.contains("no route to host") ->
                ProbeOutcome.Failed(FailureReason.UNREACHABLE)
            text.contains("operation not permitted") ||
                text.contains("permission denied") ||
                text.contains("eacces") ||
                text.contains("eperm") ->
                ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
            text.contains("too many open files") ||
                text.contains("cannot allocate memory") ||
                text.contains("memory allocation failure") ||
                text.contains("eai_memory") ||
                text.contains("resource temporarily unavailable") ||
                text.contains("selinux") ->
                ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION)
            else -> null
        }
    }

    private fun resultOutcome(probe: ProbeRequest.IcmpEcho, state: RunState): ProbeOutcome = when {
        state.received >= probe.minimumReplies -> ProbeOutcome.Matched
        state.lastFailure != null -> state.lastFailure!!
        state.timedOut -> ProbeOutcome.Failed(FailureReason.TIMEOUT)
        state.sent == 0 -> ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION)
        else -> ProbeOutcome.Failed(FailureReason.ICMP)
    }

    private fun completed(
        request: ProbeRunRequest,
        startNanos: Long,
        state: RunState,
        outcome: ProbeOutcome,
    ): ProbeResult.Completed = ProbeResult.Completed(
        context = request.context,
        recordedAt = Instant.now(),
        timing = ProbeTiming(elapsed = (System.nanoTime() - startNanos).nanoseconds),
        outcome = outcome,
        observations = ProbeObservations(
            connectedIpFamily = state.connectedIpFamily,
            icmp = observation(state),
        ),
    )

    private fun observation(state: RunState): IcmpObservation? {
        if (state.sent == 0 || state.unknownPackets > 0) return null
        val rtt = if (state.rtts.isEmpty()) {
            null
        } else {
            val total = state.rtts.sumOf { it.inWholeNanoseconds }
            RttSummary(
                minimum = state.rtts.min(),
                average = (total / state.rtts.size).nanoseconds,
                maximum = state.rtts.max(),
            )
        }
        return IcmpObservation(state.sent, state.received, rtt)
    }

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

    private fun pingCommand(
        binary: String,
        host: String,
        budget: Long,
        family: IpFamily?,
    ): List<String> {
        val seconds = ((budget + 999_999_999L) / 1_000_000_000L).coerceAtLeast(1L)
        val command = mutableListOf(binary, "-c", "1", "-W", seconds.toString())
        if (family == IpFamily.IPV4) command += "-4"
        command += host
        return command
    }

    private fun normalizeHost(value: String): String? {
        val trimmed = value.trim()
        val withoutBrackets = if (trimmed.length >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return withoutBrackets.removeSuffix(".").lowercase().takeIf { it.isNotEmpty() }
    }

    private fun isSafeHost(host: String): Boolean =
        !host.startsWith("-") &&
            host.none { it.isWhitespace() || it.isISOControl() } &&
            '/' !in host &&
            '\\' !in host

    private fun familyMismatch(family: IpFamily?, host: String): Boolean {
        val literal = literalFamily(host) ?: return false
        return family != null && literal != family
    }

    private fun literalFamily(host: String): IpFamily? {
        val ipv4 = IPV4.matches(host) && host.split('.').all { it.toIntOrNull() in 0..255 }
        return when {
            ipv4 -> IpFamily.IPV4
            host.count { it == ':' } >= 2 -> IpFamily.IPV6
            else -> null
        }
    }

    private class RunState {
        var sent = 0
        var received = 0
        var targetFamily: IpFamily? = null
        var connectedIpFamily: IpFamily? = null
        val rtts = mutableListOf<Duration>()
        var timedOut = false
        var unknownPackets = 0
        var lastFailure: ProbeOutcome? = null

        fun add(summary: PingSummary) {
            val transmitted = summary.transmitted
            val received = summary.received
            if (transmitted == null || received == null) {
                unknownPackets += 1
                return
            }
            sent += transmitted
            this.received += received
            if (received > 0 && summary.replyTimes.isNotEmpty()) {
                rtts += summary.replyTimes.first()
                connectedIpFamily = targetFamily
            }
        }
    }

    private companion object {
        const val PING = "/system/bin/ping"
        const val PING6 = "/system/bin/ping6"
        const val MIN_PACKET_NANOS = 1_000_000L
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
