package io.github.chuck852.linksonda.domain

import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainModelTest {
    private val probeId = ProbeId("example-check")
    private val networkId = NetworkId("wifi")
    private val context = ProbeContext(probeId, networkId)
    private val recordedAt = Instant.parse("2026-01-01T12:00:00Z")
    private val endpoint = SocketEndpoint("example.test", 443)
    private val question = DnsQuestion("example.test", 1)
    private val dnsExpectation = DnsExpectation(responseCode = 0, requiresAnswer = true)

    @Test
    fun configRetainsProtocolAndSuccessCriteria() {
        val requests = listOf(
            ProbeRequest.TcpConnect(endpoint),
            ProbeRequest.TlsHandshake(endpoint, minimumVersion = TlsVersion.TLS_1_3),
            ProbeRequest.Http(URI("https://example.test/health"), HttpMethod.GET, HttpExpectation(200..204, "ready")),
            ProbeRequest.SystemDns(question, dnsExpectation),
            ProbeRequest.DirectDns(SocketEndpoint("example.test", 53), DnsTransport.UDP, question, dnsExpectation),
            ProbeRequest.DirectDns(SocketEndpoint("example.test", 53), DnsTransport.TCP, question, dnsExpectation),
            ProbeRequest.DnsOverHttps(URI("https://example.test/dns-query"), question, dnsExpectation),
            ProbeRequest.DnsOverTls(SocketEndpoint("example.test", 853), "example.test", question, dnsExpectation),
            ProbeRequest.IcmpEcho("example.test", packetCount = 3, minimumReplies = 2),
        )
        requests.forEach { request ->
            assertEquals(request, ProbeConfig(probeId, "Example", request, 5.seconds).request)
        }
    }

    @Test
    fun invalidProtocolSettingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SocketEndpoint("example.test", 0) }
        assertThrows(IllegalArgumentException::class.java) { DnsExpectation(3, requiresAnswer = true) }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeRequest.DnsOverHttps(URI("http://example.test/dns-query"), question, dnsExpectation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeRequest.Http(URI("https://example.test"), HttpMethod.HEAD, HttpExpectation(200..299, "ready"))
        }
        assertThrows(IllegalArgumentException::class.java) { ProbeRequest.IcmpEcho("example.test", 2, 3) }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeConfig(probeId, "Example", ProbeRequest.TcpConnect(endpoint), Duration.INFINITE)
        }
    }

    @Test
    fun completedTimingUsesDurationsAndLeavesUnobservedPhasesAbsent() {
        val timing = ProbeTiming(250.milliseconds, mapOf(ProbePhase.TCP_CONNECT to 80.milliseconds))
        val result = ProbeResult.Completed(context, recordedAt, timing, ProbeOutcome.Matched)
        assertEquals(recordedAt, result.recordedAt)
        assertEquals(250.milliseconds, result.timing.elapsed)
        assertNull(result.timing.phases[ProbePhase.DNS])
        assertThrows(IllegalArgumentException::class.java) { ProbeTiming((-1).seconds) }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeTiming(1.seconds, mapOf(ProbePhase.TCP_CONNECT to 2.seconds))
        }
    }

    @Test
    fun runningAndPolicySkipsPreserveTheLastCompletedResult() {
        val completed = ProbeResult.Completed(context, recordedAt, ProbeTiming(20.milliseconds), ProbeOutcome.Matched)
        val skip = ProbeResult.Skipped(context, recordedAt.plusSeconds(5), SkipReason.BUDGET)
        val state = ProbeState(context, ProbeStatus.UP, ProbeLifecycle.RUNNING, ProbeFreshness.STALE, completed, skip)
        assertEquals(ProbeStatus.UP, state.status)
        assertEquals(completed, state.lastCompleted)
        assertEquals(skip, state.lastSkipped)
    }

    @Test
    fun resultsFromAnotherNetworkCannotPopulateStateOrIncidentEvidence() {
        val otherContext = context.copy(networkId = NetworkId("mobile"))
        val completed = ProbeResult.Completed(otherContext, recordedAt, ProbeTiming(Duration.ZERO), ProbeOutcome.Matched)
        assertThrows(IllegalArgumentException::class.java) {
            ProbeState(context, ProbeStatus.UP, ProbeLifecycle.IDLE, ProbeFreshness.FRESH, completed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeState(context, ProbeStatus.DOWN, ProbeLifecycle.IDLE, ProbeFreshness.MISSING)
        }
        val hypothesis = DiagnosticHypothesis(
            HypothesisKind.DNS_FAILURE, setOf(DiagnosticEvidence(otherContext, recordedAt)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            Incident("incident", ProfileId("profile"), networkId, recordedAt, hypotheses = listOf(hypothesis))
        }
    }

    @Test
    fun slotWithoutAnActiveNetworkHasNoCompletedMeasurement() {
        val offline = ProbeContext(probeId, networkId = null)
        val skip = ProbeResult.Skipped(offline, recordedAt, SkipReason.NO_NETWORK)
        val state = ProbeState(offline, ProbeStatus.UNKNOWN, ProbeLifecycle.IDLE, ProbeFreshness.MISSING, lastSkipped = skip)
        assertEquals(SkipReason.NO_NETWORK, state.lastSkipped?.reason)
        assertNull(state.lastCompleted)
        assertThrows(IllegalArgumentException::class.java) {
            ProbeResult.Completed(offline, recordedAt, ProbeTiming(Duration.ZERO), ProbeOutcome.Matched)
        }
    }

    @Test
    fun restrictionsAndLocalAdmissionDenialsCarrySeparateReasons() {
        val retryAt = recordedAt.plusSeconds(60)
        val restriction = ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, retryAt)
        val denial = AdmissionDecision.Denied(SkipReason.COOLDOWN, retryAt)
        assertEquals(retryAt, restriction.retryAt)
        assertEquals(SkipReason.COOLDOWN, denial.reason)
        assertEquals(503, ProbeFailure.UnexpectedHttpStatus(503).code)
        assertThrows(IllegalArgumentException::class.java) { AdmissionDecision.Denied(SkipReason.NO_NETWORK) }
    }

    @Test
    fun profileGroupsReferToConfiguredUniqueProbeIds() {
        val config = ProbeConfig(probeId, "Example", ProbeRequest.TcpConnect(endpoint), 5.seconds)
        val profile = MonitoringProfile(
            ProfileId("profile"), "Example", Preset("preset", "Example", listOf(config)),
            listOf(ProbeGroup("Checks", setOf(probeId))), MonitoringSchedule(60.seconds, 4),
            AlertThresholds(3, 2), NotificationSettings(enabled = false),
        )
        assertEquals(setOf(probeId), profile.groups.single().probeIds)
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(groups = listOf(ProbeGroup("Other", setOf(ProbeId("absent")))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile.preset.copy(probes = listOf(config, config))
        }
    }
}
