package io.github.chuck852.linksonda.storage

import io.github.chuck852.linksonda.domain.AlertThresholds
import io.github.chuck852.linksonda.domain.DiagnosticEvidence
import io.github.chuck852.linksonda.domain.DiagnosticHypothesis
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.HypothesisKind
import io.github.chuck852.linksonda.domain.Incident
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.MonitoringSchedule
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkSnapshot
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.NotificationSettings
import io.github.chuck852.linksonda.domain.Preset
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeGroup
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.ProfileId
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.domain.TlsVersion
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal object StorageFixtures {
    val profileId = ProfileId("profile-1")
    val networkId = NetworkId("wifi-1")
    val tcp = ProbeConfig(
        id = ProbeId("tcp-1"),
        name = "TCP",
        request = ProbeRequest.TcpConnect(SocketEndpoint("example.com", 443)),
        timeout = 5.seconds,
    )
    val tls = ProbeConfig(
        id = ProbeId("tls-1"),
        name = "TLS",
        request = ProbeRequest.TlsHandshake(
            endpoint = SocketEndpoint("example.com", 443),
            serverName = "example.com",
            minimumVersion = TlsVersion.TLS_1_2,
        ),
        timeout = 5.seconds,
    )
    val dns = ProbeConfig(
        id = ProbeId("dns-1"),
        name = "DNS",
        request = ProbeRequest.SystemDns(
            question = DnsQuestion("example.com", 1),
            expected = io.github.chuck852.linksonda.domain.DnsExpectation(0, true),
        ),
        timeout = 3.seconds,
    )

    fun profile(
        name: String = "Home",
        probes: List<ProbeConfig> = listOf(tcp, tls, dns),
    ): MonitoringProfile = MonitoringProfile(
        id = profileId,
        name = name,
        preset = Preset("preset-1", "Everyday", probes),
        groups = listOf(ProbeGroup("Web", setOf(tcp.id, tls.id))),
        schedule = MonitoringSchedule(5.minutes, 2),
        thresholds = AlertThresholds(consecutiveFailures = 3, consecutiveSuccesses = 2),
        notifications = NotificationSettings(enabled = true, repeatInterval = 30.minutes),
    )

    fun network(capturedAt: Instant = Instant.parse("2026-11-01T10:00:00Z")): NetworkSnapshot =
        NetworkSnapshot(
            id = networkId,
            capturedAt = capturedAt,
            transports = setOf(NetworkTransport.WIFI),
            ipFamilies = setOf(IpFamily.IPV4),
            validation = NetworkValidation.VALIDATED,
        )

    fun completed(
        config: ProbeConfig = tcp,
        recordedAt: Instant = Instant.parse("2026-11-01T10:00:01Z"),
    ): ProbeResult.Completed = ProbeResult.Completed(
        context = ProbeContext(config.key, networkId),
        recordedAt = recordedAt,
        timing = ProbeTiming(
            elapsed = 120.milliseconds,
            phases = mapOf(ProbePhase.DNS to 10.milliseconds, ProbePhase.TCP_CONNECT to 100.milliseconds),
        ),
        outcome = ProbeOutcome.Matched,
        observations = ProbeObservations(connectedIpFamily = IpFamily.IPV4),
    )

    fun incident(
        id: String = "incident-1",
        recoveredAt: Instant? = null,
        openedAt: Instant = Instant.parse("2026-11-01T10:00:00Z"),
        network: NetworkId = networkId,
    ): Incident = Incident(
        id = id,
        profileId = profileId,
        networkId = network,
        openedAt = openedAt,
        recoveredAt = recoveredAt,
        hypotheses = listOf(
            DiagnosticHypothesis(
                kind = HypothesisKind.DNS_FAILURE,
                evidence = setOf(DiagnosticEvidence(ProbeContext(tcp.key, network), openedAt)),
            ),
        ),
    )
}
