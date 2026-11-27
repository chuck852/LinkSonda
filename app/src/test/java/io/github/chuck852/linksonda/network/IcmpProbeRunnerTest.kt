package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.IcmpObservation
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkSnapshot
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeAttempt
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeMode
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.RttSummary
import io.github.chuck852.linksonda.domain.SkipReason
import java.io.FileNotFoundException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpProbeRunnerTest {
    @Test
    fun ipv4ReplyMatchesAndReportsRtt() {
        val process = FakePingProcessRunner(
            PingExecution.Completed(
                output = "64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=1.500 ms\n" +
                    "1 packets transmitted, 1 received, 0% packet loss",
                exitCode = 0,
            ),
        )
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
            admission = admission,
        )

        val completed = completed(result)
        assertEquals(ProbeOutcome.Matched, completed.outcome)
        assertEquals(
            IcmpObservation(
                sentPackets = 1,
                receivedReplies = 1,
                rtt = RttSummary(1.5.milliseconds, 1.5.milliseconds, 1.5.milliseconds),
            ),
            completed.observations.icmp,
        )
        assertEquals(listOf(ProbeAttempt("127.0.0.1", "127.0.0.1")), admission.attempts)
        assertEquals(
            listOf("/system/bin/ping", "-c", "1", "-W", "2", "127.0.0.1"),
            process.commands.single(),
        )
        assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
    }

    @Test
    fun ipv6LiteralUsesPing6() {
        val process = FakePingProcessRunner(
            PingExecution.Completed(
                output = "64 bytes from ::1: icmp_seq=1 ttl=64 time=0.200 ms",
                exitCode = 0,
            ),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("::1", family = IpFamily.IPV6),
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertEquals("/system/bin/ping6", process.commands.single().first())
        assertEquals("::1", process.commands.single().last())
        assertEquals(IpFamily.IPV6, completed(result).observations.connectedIpFamily)
    }

    @Test
    fun bracketedIpv6HostIsNormalizedBeforeLaunch() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from ::1: icmp_seq=1 ttl=64 time=0.100 ms", 0),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("[::1]", family = IpFamily.IPV6),
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertEquals("::1", process.commands.single().last())
    }

    @Test
    fun firstPacketTimeoutDoesNotAbortRemainingPackets() {
        val process = FakePingProcessRunner(
            PingExecution.TimedOut(""),
            PingExecution.Completed("64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=1.000 ms", 0),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1", packets = 2, minimumReplies = 1),
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertEquals(2, completed(result).observations.icmp?.sentPackets)
        assertEquals(1, completed(result).observations.icmp?.receivedReplies)
        assertEquals(2, process.commands.size)
    }

    @Test
    fun rttSummaryUsesAllReplies() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from 127.0.0.1: time=1.000 ms", 0),
            PingExecution.Completed("64 bytes from 127.0.0.1: time=3.000 ms", 0),
        )
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1", packets = 2, minimumReplies = 2),
            admission = admission,
        )

        assertEquals(
            RttSummary(1.milliseconds, 2.milliseconds, 3.milliseconds),
            completed(result).observations.icmp?.rtt,
        )
        assertEquals(2, completed(result).observations.icmp?.sentPackets)
        assertEquals(2, admission.attempts.size)
    }

    @Test
    fun toyboxDnsFailureIsDns() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("ping: missing.test: No address associated with hostname", 1),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("missing.test"),
        )

        failed(result, FailureReason.DNS)
        assertNull(completed(result).observations.icmp)
        assertNull(completed(result).observations.connectedIpFamily)
    }

    @Test
    fun ping6DnsFailureIsDnsWithoutObservationOrConnectedFamily() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("ping6: missing.test: No address associated with hostname", 1),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("missing.test", family = IpFamily.IPV6),
        )

        failed(result, FailureReason.DNS)
        assertNull(completed(result).observations.icmp)
        assertNull(completed(result).observations.connectedIpFamily)
    }

    @Test
    fun ping6PermissionFailureIsInconclusiveWithoutObservation() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("ping6: socket: Permission denied", 1),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("::1", family = IpFamily.IPV6),
        )

        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
            completed(result).outcome,
        )
        assertNull(completed(result).observations.icmp)
        assertNull(completed(result).observations.connectedIpFamily)
    }

    @Test
    fun outputErrorAfterMinimumRepliesKeepsSuccess() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from ::1: time=1.000 ms", 0),
            PingExecution.Completed("ping6: socket: Permission denied", 1),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("::1", packets = 2, minimumReplies = 1, family = IpFamily.IPV6),
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertEquals(1, completed(result).observations.icmp?.sentPackets)
        assertEquals(IpFamily.IPV6, completed(result).observations.connectedIpFamily)
    }

    @Test
    fun outputPermissionFailureIsInconclusiveWithoutObservation() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("ping: socket: Permission denied", 1),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
        )

        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
            completed(result).outcome,
        )
        assertNull(completed(result).observations.icmp)
    }

    @Test
    fun ipv4FamilyAddsMinusFourForHostname() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from 192.0.2.1: time=1.000 ms", 0),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("example.test", family = IpFamily.IPV4),
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertTrue("-4" in process.commands.single())
    }

    @Test
    fun admissionKeyIsNormalized() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from 192.0.2.1: time=1.000 ms", 0),
        )
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("Example.COM."),
            admission = admission,
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertEquals(listOf(ProbeAttempt("example.com", "example.com")), admission.attempts)
        assertEquals("example.com", process.commands.single().last())
    }

    @Test
    fun denialAfterMinimumRepliesKeepsTheSuccessfulResult() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from 127.0.0.1: time=1.000 ms", 0),
        )
        val admission = RecordingAdmission(
            listOf(AdmissionDecision.Allowed, AdmissionDecision.Denied(SkipReason.COOLDOWN)),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1", packets = 2, minimumReplies = 1),
            admission = admission,
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertEquals(1, process.commands.size)
        assertEquals(2, admission.attempts.size)
    }

    @Test
    fun noReplyTimesOutWithObservation() {
        val process = FakePingProcessRunner(PingExecution.TimedOut(""))
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
        )

        val completed = failed(result, FailureReason.TIMEOUT)
        assertEquals(IcmpObservation(sentPackets = 1, receivedReplies = 0), completed.observations.icmp)
    }

    @Test
    fun emptySuccessfulProcessWithoutReplyIsTimeout() {
        val process = FakePingProcessRunner(PingExecution.Completed("", 1))
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
        )

        failed(result, FailureReason.TIMEOUT)
        assertEquals(IcmpObservation(1, 0), completed(result).observations.icmp)
    }

    @Test
    fun unknownHostIsDnsFailure() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("ping: unknown host no-such-host.test", 1),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("no-such-host.test"),
        )

        failed(result, FailureReason.DNS)
    }

    @Test
    fun unreachableNetworkIsUnreachable() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("connect: Network is unreachable", 1),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("192.0.2.1"),
        )

        failed(result, FailureReason.UNREACHABLE)
    }

    @Test
    fun permissionFailureIsInconclusive() {
        val process = FakePingProcessRunner(PingExecution.LaunchFailed(SecurityException("denied")))
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
        )

        val completed = completed(result)
        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
            completed.outcome,
        )
        assertNull(completed.observations.icmp)
    }

    @Test
    fun osResourceExhaustionIsInconclusive() {
        val process = FakePingProcessRunner(
            PingExecution.LaunchFailed(java.io.IOException("Too many open files")),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
        )

        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION),
            completed(result).outcome,
        )
    }

    @Test
    fun missingPingBinaryIsUnsupported() {
        val process = FakePingProcessRunner(PingExecution.LaunchFailed(FileNotFoundException("ping")))
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
        )

        skipped(result, SkipReason.UNSUPPORTED)
        assertNull(completedOrNull(result))
    }

    @Test
    fun admissionDenialPreventsProcessLaunch() {
        val process = FakePingProcessRunner(PingExecution.TimedOut(""))
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1"),
            admission = RecordingAdmission(listOf(AdmissionDecision.Denied(SkipReason.COOLDOWN))),
        )

        skipped(result, SkipReason.COOLDOWN)
        assertTrue(process.commands.isEmpty())
    }

    @Test
    fun partialRepliesBelowMinimumFailWithTimeout() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=1 ms", 0),
            PingExecution.TimedOut(""),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1", packets = 2, minimumReplies = 2),
        )

        val completed = failed(result, FailureReason.TIMEOUT)
        assertEquals(2, completed.observations.icmp?.sentPackets)
        assertEquals(1, completed.observations.icmp?.receivedReplies)
    }

    @Test
    fun oneReplyCanMatchWhenMinimumIsOne() {
        val process = FakePingProcessRunner(
            PingExecution.Completed("64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=1.000 ms", 0),
            PingExecution.TimedOut(""),
        )
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1", packets = 2, minimumReplies = 1),
        )

        assertEquals(ProbeOutcome.Matched, completed(result).outcome)
        assertEquals(2, completed(result).observations.icmp?.sentPackets)
        assertEquals(1, completed(result).observations.icmp?.receivedReplies)
    }

    @Test
    fun optionLikeHostIsRejectedWithoutLaunching() {
        val process = FakePingProcessRunner(PingExecution.TimedOut(""))
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("-c"),
        )

        failed(result, FailureReason.ICMP)
        assertTrue(process.commands.isEmpty())
    }

    @Test
    fun familyMismatchIsDnsFailureWithoutLaunching() {
        val process = FakePingProcessRunner(PingExecution.TimedOut(""))
        val result = runProbe(
            runner = IcmpProbeRunner(process),
            request = icmpRequest("127.0.0.1", family = IpFamily.IPV6),
        )

        failed(result, FailureReason.DNS)
        assertTrue(process.commands.isEmpty())
    }

    @Test
    fun externalCancellationPropagates() {
        val process = FakePingProcessRunner(PingExecution.TimedOut(""))
        process.blockForever = true
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(100.milliseconds) {
                    IcmpProbeRunner(process).run(
                        probeRunRequest(icmpRequest("127.0.0.1"), 5.seconds),
                        RecordingAdmission(),
                    )
                }
            }
        }
    }

    private fun runProbe(
        runner: IcmpProbeRunner,
        request: ProbeRequest,
        timeout: Duration = 2.seconds,
        admission: ProbeAdmission = RecordingAdmission(),
    ): ProbeResult = runBlocking {
        runner.run(probeRunRequest(request, timeout), admission)
    }

    private fun icmpRequest(
        host: String,
        packets: Int = 1,
        minimumReplies: Int = 1,
        family: IpFamily? = null,
    ): ProbeRequest.IcmpEcho = ProbeRequest.IcmpEcho(
        host = host,
        packetCount = packets,
        minimumReplies = minimumReplies,
        family = family,
    )

    private fun probeRunRequest(request: ProbeRequest, timeout: Duration): ProbeRunRequest =
        ProbeRunRequest(
            config = ProbeConfig(ProbeId("test-icmp"), "Test ICMP probe", request, timeout),
            network = NetworkSnapshot(
                id = NetworkId("local-test"),
                capturedAt = Instant.now(),
                transports = setOf(NetworkTransport.ETHERNET),
                ipFamilies = setOf(IpFamily.IPV4, IpFamily.IPV6),
                validation = NetworkValidation.VALIDATED,
            ),
            mode = ProbeMode.MANUAL,
        )

    private fun completed(result: ProbeResult): ProbeResult.Completed {
        assertTrue("Expected Completed, got $result", result is ProbeResult.Completed)
        return result as ProbeResult.Completed
    }

    private fun completedOrNull(result: ProbeResult): ProbeResult.Completed? = result as? ProbeResult.Completed

    private fun failed(result: ProbeResult, reason: FailureReason): ProbeResult.Completed {
        val completed = completed(result)
        assertEquals(ProbeOutcome.Failed(reason), completed.outcome)
        return completed
    }

    private fun skipped(result: ProbeResult, reason: SkipReason): ProbeResult.Skipped {
        assertTrue("Expected Skipped, got $result", result is ProbeResult.Skipped)
        val skipped = result as ProbeResult.Skipped
        assertEquals(reason, skipped.reason)
        return skipped
    }

    private class FakePingProcessRunner(
        vararg responses: PingExecution,
    ) : PingProcessRunner {
        private val pending = ArrayDeque(responses.toList())
        val commands = mutableListOf<List<String>>()
        var blockForever = false

        override suspend fun execute(command: List<String>, timeout: Duration): PingExecution {
            commands += command
            if (blockForever) awaitCancellation()
            return pending.removeFirstOrNull() ?: error("No fake ping response for $command")
        }
    }

    private class RecordingAdmission(
        private val decisions: List<AdmissionDecision> = listOf(AdmissionDecision.Allowed),
    ) : ProbeAdmission {
        val attempts = mutableListOf<ProbeAttempt>()

        override suspend fun authorize(attempt: ProbeAttempt): AdmissionDecision {
            attempts += attempt
            return decisions.getOrElse(attempts.lastIndex) { AdmissionDecision.Allowed }
        }
    }
}
