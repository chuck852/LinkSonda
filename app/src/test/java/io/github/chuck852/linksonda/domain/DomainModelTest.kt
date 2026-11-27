package io.github.chuck852.linksonda.domain

import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainModelTest {
    private val probeId = ProbeId("example-check")
    private val networkId = NetworkId("wifi")
    private val probeKey = ProbeKey(probeId, ProbeRevision(1))
    private val context = ProbeContext(probeKey, networkId)
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
        val offline = ProbeContext(probeKey, networkId = null)
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
        assertThrows(IllegalArgumentException::class.java) { AdmissionDecision.Denied(SkipReason.NO_NETWORK) }
    }

    @Test
    fun observedProtocolFactsRemainAvailableAcrossOutcomes() {
        val observations = ProbeObservations(
            connectedIpFamily = IpFamily.IPV6,
            http = HttpObservation(503, bodyMarkerMatched = false),
            dns = DnsObservation(responseCode = 0, answerCount = 2, truncated = false),
            tls = TlsObservation(TlsVersion.TLS_1_3),
        )
        val completed = ProbeResult.Completed(context, recordedAt, ProbeTiming(250.milliseconds), ProbeOutcome.Matched)
        val restricted = completed.copy(outcome = ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED))
        val failed = completed.copy(outcome = ProbeOutcome.Failed(FailureReason.UNEXPECTED_HTTP_STATUS))
        listOf(completed, failed, restricted).forEach {
            val result = it.copy(observations = observations)
            assertEquals(503, result.observations.http?.statusCode)
            assertEquals(0, result.observations.dns?.responseCode)
            assertEquals(2, result.observations.dns?.answerCount)
            assertEquals(TlsVersion.TLS_1_3, result.observations.tls?.negotiatedVersion)
        }
        assertNull(DnsObservation(answerCount = 0).responseCode)
        assertThrows(IllegalArgumentException::class.java) { HttpObservation(700) }
        assertThrows(IllegalArgumentException::class.java) { DnsObservation(answerCount = -1) }
    }

    @Test
    fun icmpLossAndRttRepresentObservedReplies() {
        val rtt = RttSummary(10.milliseconds, 15.milliseconds, 20.milliseconds)
        val observation = IcmpObservation(4, 3, rtt)
        assertEquals(1, observation.lostPackets)
        assertEquals(0.25, observation.lossFraction, 0.0)
        assertEquals(rtt, observation.rtt)
        assertEquals(1.0, IcmpObservation(4, 0).lossFraction, 0.0)
        assertNull(IcmpObservation(4, 0).rtt)
        assertThrows(IllegalArgumentException::class.java) { IcmpObservation(4, 0, rtt) }
        assertThrows(IllegalArgumentException::class.java) { IcmpObservation(4, 5) }
        assertThrows(IllegalArgumentException::class.java) { RttSummary(20.milliseconds, 10.milliseconds, 30.milliseconds) }
    }

    @Test
    fun completedAttemptsCanBeInconclusiveWithPartialObservations() {
        val observations = ProbeObservations(http = HttpObservation(204))
        InconclusiveReason.entries.forEach { reason ->
            val outcome = ProbeOutcome.Inconclusive(reason)
            val result = ProbeResult.Completed(context, recordedAt, ProbeTiming(40.milliseconds), outcome, observations)
            assertEquals(outcome, result.outcome)
            assertEquals(observations, result.observations)
        }
    }

    @Test
    fun permissionDeniedBeforeAttemptIsDistinctFromPolicyDenial() {
        val skip = ProbeResult.Skipped(context, recordedAt, SkipReason.PERMISSION_DENIED)
        assertEquals(SkipReason.PERMISSION_DENIED, skip.reason)
        assertNotEquals(SkipReason.NOT_ALLOWED, skip.reason)
        assertThrows(IllegalArgumentException::class.java) { AdmissionDecision.Denied(skip.reason) }
    }

    @Test
    fun incidentRecoveryMustBeAtOrAfterOpening() {
        val incident = Incident("incident", ProfileId("profile"), networkId, recordedAt)
        assertNull(incident.recoveredAt)
        assertEquals(recordedAt, incident.copy(recoveredAt = recordedAt).recoveredAt)
        assertEquals(recordedAt.plusSeconds(1), incident.copy(recoveredAt = recordedAt.plusSeconds(1)).recoveredAt)
        assertThrows(IllegalArgumentException::class.java) { incident.copy(recoveredAt = recordedAt.minusNanos(1)) }
    }

    @Test
    fun revisionChangesWithEndpointProtocolCriteriaOrTimeoutAndSurvivesRestore() {
        val http = ProbeRequest.Http(URI("https://example.test/health"), HttpMethod.GET, HttpExpectation(200..299))
        val initial = ProbeConfig(probeId, "Example", http, 5.seconds)
        val endpointChanged = initial.update(request = http.copy(url = URI("https://example.test/status")))
        val tls = endpointChanged.update(request = ProbeRequest.TlsHandshake(endpoint))
        val backToHttp = tls.update(request = http)
        val criteriaChanged = backToHttp.update(request = http.copy(expected = HttpExpectation(204..204)))
        val timeoutChanged = criteriaChanged.update(timeout = 6.seconds)
        val renamed = timeoutChanged.update(name = "Renamed")

        assertEquals(ProbeRevision(1), initial.revision)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), listOf(endpointChanged, tls, backToHttp, criteriaChanged, timeoutChanged)
            .map { it.revision.value })
        assertEquals(initial.id, renamed.id)
        assertEquals(timeoutChanged.key, renamed.key)
        assertEquals(renamed, ProbeConfig.restore(renamed.key, renamed.name, renamed.request, renamed.timeout))
    }

    @Test
    fun oldRevisionCannotPopulateCurrentProbeStateOnTheSameNetwork() {
        val initial = ProbeConfig(probeId, "Example", ProbeRequest.TcpConnect(endpoint), 5.seconds)
        val network = NetworkSnapshot(networkId, recordedAt, setOf(NetworkTransport.WIFI), setOf(IpFamily.IPV4),
            NetworkValidation.UNKNOWN)
        val run = ProbeRunRequest(initial, network, ProbeMode.MANUAL)
        val result = ProbeResult.Completed(run.context, recordedAt, ProbeTiming(40.milliseconds), ProbeOutcome.Matched)
        val current = run.copy(config = initial.update(request = ProbeRequest.TcpConnect(endpoint.copy(port = 8443))))
        val oldSkip = ProbeResult.Skipped(run.context, recordedAt, SkipReason.COOLDOWN)

        assertEquals(initial.key, result.context.probe)
        assertNotEquals(current.context, result.context)
        assertThrows(IllegalArgumentException::class.java) {
            ProbeState(current.context, ProbeStatus.UP, ProbeLifecycle.IDLE, ProbeFreshness.FRESH, result)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbeState(current.context, ProbeStatus.UNKNOWN, ProbeLifecycle.IDLE, ProbeFreshness.MISSING, lastSkipped = oldSkip)
        }
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
