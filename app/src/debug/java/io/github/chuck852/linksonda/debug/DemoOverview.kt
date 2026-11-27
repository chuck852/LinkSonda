package io.github.chuck852.linksonda.debug

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsObservation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.DnsTransport
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.HttpObservation
import io.github.chuck852.linksonda.domain.IcmpObservation
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeKey
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRevision
import io.github.chuck852.linksonda.domain.ProbeState
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.RestrictionReason
import io.github.chuck852.linksonda.domain.RttSummary
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.domain.TlsObservation
import io.github.chuck852.linksonda.domain.TlsVersion
import io.github.chuck852.linksonda.ui.checks.ProbeListItem
import io.github.chuck852.linksonda.ui.overview.GroupUiSummary
import io.github.chuck852.linksonda.ui.overview.ManualCheckState
import io.github.chuck852.linksonda.ui.overview.NetworkUiSummary
import io.github.chuck852.linksonda.ui.overview.OverviewFreshness
import io.github.chuck852.linksonda.ui.overview.OverviewMonitoring
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import io.github.chuck852.linksonda.ui.overview.ProfileUiSummary
import java.net.URI
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Debug-only demonstration data built from the real domain models. Every scenario is marked with
 * [OverviewUiState.isDemo] and rendered with a visible banner. No network request is made and no
 * result is persisted.
 */
enum class DemoScenario { EMPTY, UNKNOWN, RUNNING, PARTIAL, DOWN, UP }

fun demoOverview(scenario: DemoScenario): OverviewUiState = when (scenario) {
    DemoScenario.EMPTY -> OverviewUiState(isDemo = true)
    DemoScenario.UNKNOWN -> scenarioWith(
        aggregate = AggregateStatus.UNKNOWN,
        freshness = OverviewFreshness.MISSING,
        monitoring = OverviewMonitoring.NOT_CONFIGURED,
        manualCheck = ManualCheckState.UNAVAILABLE,
        items = demoSpecs.map { ProbeListItem(it.config, unknownState(it.config), it.groupId, it.groupName) },
        secondsAgo = null,
        networkValidation = NetworkValidation.UNKNOWN,
    )

    DemoScenario.RUNNING -> scenarioWith(
        aggregate = AggregateStatus.AVAILABLE,
        freshness = OverviewFreshness.FRESH,
        monitoring = OverviewMonitoring.RUNNING,
        manualCheck = ManualCheckState.RUNNING,
        items = demoSpecs.map {
            ProbeListItem(
                it.config,
                upState(it.config, lifecycle = if (it.config.id.value == "https-global") ProbeLifecycle.RUNNING else ProbeLifecycle.IDLE),
                it.groupId,
                it.groupName,
            )
        },
        secondsAgo = 4,
    )

    DemoScenario.PARTIAL -> mixedScenario()
    DemoScenario.DOWN -> scenarioWith(
        aggregate = AggregateStatus.UNAVAILABLE,
        freshness = OverviewFreshness.FRESH,
        monitoring = OverviewMonitoring.RUNNING,
        manualCheck = ManualCheckState.IDLE,
        items = demoSpecs.map { ProbeListItem(it.config, downState(it.config), it.groupId, it.groupName) },
        secondsAgo = 8,
        networkValidation = NetworkValidation.UNVALIDATED,
    )

    DemoScenario.UP -> scenarioWith(
        aggregate = AggregateStatus.AVAILABLE,
        freshness = OverviewFreshness.FRESH,
        monitoring = OverviewMonitoring.RUNNING,
        manualCheck = ManualCheckState.IDLE,
        items = demoSpecs.map { ProbeListItem(it.config, upState(it.config), it.groupId, it.groupName) },
        secondsAgo = 6,
        network = NetworkUiSummary(
            contextKnown = true,
            transports = setOf(NetworkTransport.ETHERNET),
            validation = NetworkValidation.VALIDATED,
        ),
    )
}

private fun mixedScenario(): OverviewUiState {
    val items = demoSpecs.map { spec ->
        val state = when (spec.config.id.value) {
            "https-global" -> upState(spec.config)
            "tcp-global" -> upState(spec.config)
            "tls-global" -> staleState(spec.config)
            "https-backup" -> unknownState(spec.config)
            "https-retained" -> retainedState(spec.config)
            "http-regional" -> downState(spec.config)
            "icmp-regional" -> upState(spec.config)
            "system-dns" -> upState(spec.config)
            "dns-udp" -> upState(spec.config)
            "dns-tcp" -> skippedState(spec.config)
            "doh" -> inconclusiveState(spec.config)
            "dot" -> restrictedState(spec.config)
            else -> unknownState(spec.config)
        }
        ProbeListItem(spec.config, state, spec.groupId, spec.groupName)
    }
    return scenarioWith(
        aggregate = AggregateStatus.PARTIAL,
        freshness = OverviewFreshness.FRESH,
        monitoring = OverviewMonitoring.RUNNING,
        manualCheck = ManualCheckState.IDLE,
        items = items,
        secondsAgo = 12,
        network = NetworkUiSummary(
            contextKnown = true,
            transports = setOf(NetworkTransport.CELLULAR, NetworkTransport.VPN),
            validation = NetworkValidation.VALIDATED,
        ),
    )
}

private fun scenarioWith(
    aggregate: AggregateStatus,
    freshness: OverviewFreshness,
    monitoring: OverviewMonitoring,
    manualCheck: ManualCheckState,
    items: List<ProbeListItem>,
    secondsAgo: Long?,
    network: NetworkUiSummary = NetworkUiSummary(
        contextKnown = true,
        transports = setOf(NetworkTransport.WIFI),
        validation = NetworkValidation.VALIDATED,
    ),
    networkValidation: NetworkValidation = NetworkValidation.VALIDATED,
): OverviewUiState = OverviewUiState(
    aggregateStatus = aggregate,
    freshness = freshness,
    lastUpdatedSecondsAgo = secondsAgo,
    monitoring = monitoring,
    network = network.copy(validation = networkValidation),
    activeProfile = ProfileUiSummary(name = "Default profile", intervalSeconds = 60),
    groups = groupsFor(items),
    probes = items,
    manualCheck = manualCheck,
    isDemo = true,
)

internal fun groupsFor(items: List<ProbeListItem>): List<GroupUiSummary> =
    items.groupBy { it.groupId }.map { (id, groupItems) ->
        GroupUiSummary(
            id = id,
            name = groupItems.first().groupName,
            status = groupStatus(groupItems.map { it.status }),
            availableProbes = groupItems.count { it.status == ProbeStatus.UP },
            totalProbes = groupItems.size,
        )
    }

internal fun groupStatus(statuses: List<ProbeStatus>): AggregateStatus = when {
    statuses.all { it == ProbeStatus.UP } -> AggregateStatus.AVAILABLE
    statuses.all { it == ProbeStatus.UNKNOWN || it == ProbeStatus.PENDING } -> AggregateStatus.UNKNOWN
    statuses.none { it == ProbeStatus.UP } && statuses.any { it == ProbeStatus.DOWN } -> AggregateStatus.UNAVAILABLE
    else -> AggregateStatus.PARTIAL
}

private fun unknownState(config: ProbeConfig): ProbeState = ProbeState(
    context = context(config),
    status = ProbeStatus.UNKNOWN,
    lifecycle = ProbeLifecycle.IDLE,
    freshness = ProbeFreshness.MISSING,
)

private fun upState(config: ProbeConfig, lifecycle: ProbeLifecycle = ProbeLifecycle.IDLE): ProbeState {
    val completed = completed(
        config = config,
        recordedAt = DEMO_TIME,
        timing = matchedTiming(config.request),
        outcome = ProbeOutcome.Matched,
        observations = observationsFor(config.request),
    )
    return ProbeState(
        context = context(config),
        status = ProbeStatus.UP,
        lifecycle = lifecycle,
        freshness = ProbeFreshness.FRESH,
        lastCompleted = completed,
    )
}

private fun staleState(config: ProbeConfig): ProbeState {
    val completed = completed(
        config = config,
        recordedAt = DEMO_TIME.minusSeconds(900),
        timing = matchedTiming(config.request),
        outcome = ProbeOutcome.Matched,
        observations = observationsFor(config.request),
    )
    return ProbeState(
        context = context(config),
        status = ProbeStatus.UP,
        lifecycle = ProbeLifecycle.IDLE,
        freshness = ProbeFreshness.STALE,
        lastCompleted = completed,
    )
}

private fun downState(config: ProbeConfig): ProbeState {
    val completed = completed(
        config = config,
        recordedAt = DEMO_TIME,
        timing = ProbeTiming(
            elapsed = 5.seconds,
            phases = when (config.request) {
                is ProbeRequest.Http -> mapOf(
                    ProbePhase.DNS to 22.milliseconds,
                    ProbePhase.TCP_CONNECT to 30.milliseconds,
                )

                is ProbeRequest.TcpConnect -> emptyMap()
                is ProbeRequest.TlsHandshake -> mapOf(
                    ProbePhase.DNS to 18.milliseconds,
                    ProbePhase.TCP_CONNECT to 31.milliseconds,
                )

                is ProbeRequest.SystemDns,
                is ProbeRequest.DirectDns,
                is ProbeRequest.DnsOverHttps,
                is ProbeRequest.DnsOverTls,
                -> mapOf(ProbePhase.DNS to 5.seconds)

                is ProbeRequest.IcmpEcho -> emptyMap()
            },
        ),
        outcome = ProbeOutcome.Failed(io.github.chuck852.linksonda.domain.FailureReason.TIMEOUT),
        observations = ProbeObservations(),
    )
    return ProbeState(
        context = context(config),
        status = ProbeStatus.DOWN,
        lifecycle = ProbeLifecycle.IDLE,
        freshness = ProbeFreshness.FRESH,
        lastCompleted = completed,
    )
}

private fun retainedState(config: ProbeConfig): ProbeState = ProbeState(
    context = context(config),
    status = ProbeStatus.UP,
    lifecycle = ProbeLifecycle.IDLE,
    freshness = ProbeFreshness.STALE,
    lastCompleted = completed(
        config = config,
        recordedAt = DEMO_TIME.minusSeconds(300),
        timing = matchedTiming(config.request),
        outcome = ProbeOutcome.Matched,
        observations = observationsFor(config.request),
    ),
    lastSkipped = ProbeResult.Skipped(
        context = context(config),
        recordedAt = DEMO_TIME,
        reason = SkipReason.BUDGET,
        nextAllowedAt = DEMO_TIME.plusSeconds(60),
    ),
)

private fun skippedState(config: ProbeConfig): ProbeState = ProbeState(
    context = context(config),
    status = ProbeStatus.UNKNOWN,
    lifecycle = ProbeLifecycle.IDLE,
    freshness = ProbeFreshness.MISSING,
    lastSkipped = ProbeResult.Skipped(
        context = context(config),
        recordedAt = DEMO_TIME,
        reason = SkipReason.SLEEP,
        nextAllowedAt = DEMO_TIME.plusSeconds(60),
    ),
)

private fun inconclusiveState(config: ProbeConfig): ProbeState {
    val completed = completed(
        config = config,
        recordedAt = DEMO_TIME,
        timing = ProbeTiming(
            elapsed = 900.milliseconds,
            phases = mapOf(
                ProbePhase.DNS to 19.milliseconds,
                ProbePhase.TCP_CONNECT to 41.milliseconds,
                ProbePhase.TLS_HANDSHAKE to 96.milliseconds,
                ProbePhase.HTTP_FIRST_BYTE to 120.milliseconds,
            ),
        ),
        outcome = ProbeOutcome.Inconclusive(InconclusiveReason.DOZE),
        observations = ProbeObservations(
            connectedIpFamily = IpFamily.IPV4,
            http = HttpObservation(statusCode = 200),
        ),
    )
    return ProbeState(
        context = context(config),
        status = ProbeStatus.UNKNOWN,
        lifecycle = ProbeLifecycle.IDLE,
        freshness = ProbeFreshness.FRESH,
        lastCompleted = completed,
    )
}

private fun restrictedState(config: ProbeConfig): ProbeState {
    val completed = completed(
        config = config,
        recordedAt = DEMO_TIME,
        timing = ProbeTiming(
            elapsed = 1.2.seconds,
            phases = mapOf(
                ProbePhase.DNS to 20.milliseconds,
                ProbePhase.TCP_CONNECT to 32.milliseconds,
                ProbePhase.TLS_HANDSHAKE to 270.milliseconds,
            ),
        ),
        outcome = ProbeOutcome.Restricted(
            reason = RestrictionReason.RATE_LIMITED,
            retryAt = DEMO_TIME.plusSeconds(120),
        ),
        observations = ProbeObservations(
            connectedIpFamily = IpFamily.IPV4,
            tls = TlsObservation(TlsVersion.TLS_1_3),
        ),
    )
    return ProbeState(
        context = context(config),
        status = ProbeStatus.PENDING,
        lifecycle = ProbeLifecycle.IDLE,
        freshness = ProbeFreshness.FRESH,
        lastCompleted = completed,
    )
}

private fun completed(
    config: ProbeConfig,
    recordedAt: Instant,
    timing: ProbeTiming,
    outcome: ProbeOutcome,
    observations: ProbeObservations,
): ProbeResult.Completed = ProbeResult.Completed(
    context = context(config),
    recordedAt = recordedAt,
    timing = timing,
    outcome = outcome,
    observations = observations,
)

private fun context(config: ProbeConfig): ProbeContext =
    ProbeContext(config.key, DEMO_NETWORK)

private fun observationsFor(request: ProbeRequest): ProbeObservations = when (request) {
    is ProbeRequest.TcpConnect -> ProbeObservations(
        connectedIpFamily = request.endpoint.family,
    )

    is ProbeRequest.TlsHandshake -> ProbeObservations(
        connectedIpFamily = request.endpoint.family,
        tls = TlsObservation(request.minimumVersion ?: TlsVersion.TLS_1_3),
    )

    is ProbeRequest.Http -> ProbeObservations(
        connectedIpFamily = request.family,
        http = HttpObservation(
            statusCode = request.expected.status.first,
            bodyMarkerMatched = request.expected.bodyMarker?.let { true },
        ),
        tls = if (request.url.scheme.equals("https", ignoreCase = true)) {
            TlsObservation(TlsVersion.TLS_1_3)
        } else {
            null
        },
    )

    is ProbeRequest.SystemDns -> ProbeObservations(
        dns = DnsObservation(responseCode = 0, answerCount = 2, truncated = false),
    )

    is ProbeRequest.DirectDns -> ProbeObservations(
        dns = DnsObservation(responseCode = 0, answerCount = 1, truncated = false),
    )

    is ProbeRequest.DnsOverHttps -> ProbeObservations(
        connectedIpFamily = request.family,
        http = HttpObservation(statusCode = 200),
        dns = DnsObservation(responseCode = 0, answerCount = 1, truncated = false),
        tls = TlsObservation(TlsVersion.TLS_1_3),
    )

    is ProbeRequest.DnsOverTls -> ProbeObservations(
        dns = DnsObservation(responseCode = 0, answerCount = 1, truncated = false),
        tls = TlsObservation(TlsVersion.TLS_1_3),
    )

    is ProbeRequest.IcmpEcho -> ProbeObservations(
        connectedIpFamily = request.family,
        icmp = IcmpObservation(
            sentPackets = request.packetCount,
            receivedReplies = request.packetCount,
            rtt = RttSummary(minimum = 10.milliseconds, average = 15.milliseconds, maximum = 22.milliseconds),
        ),
    )
}

private fun matchedTiming(request: ProbeRequest): ProbeTiming = when (request) {
    is ProbeRequest.TcpConnect -> ProbeTiming(
        elapsed = 35.milliseconds,
        phases = mapOf(ProbePhase.TCP_CONNECT to 35.milliseconds),
    )

    is ProbeRequest.TlsHandshake -> ProbeTiming(
        elapsed = 138.milliseconds,
        phases = mapOf(
            ProbePhase.DNS to 18.milliseconds,
            ProbePhase.TCP_CONNECT to 30.milliseconds,
            ProbePhase.TLS_HANDSHAKE to 90.milliseconds,
        ),
    )

    is ProbeRequest.Http -> if (request.url.scheme.equals("https", ignoreCase = true)) {
        ProbeTiming(
            elapsed = 360.milliseconds,
            phases = mapOf(
                ProbePhase.DNS to 20.milliseconds,
                ProbePhase.TCP_CONNECT to 40.milliseconds,
                ProbePhase.TLS_HANDSHAKE to 120.milliseconds,
                ProbePhase.HTTP_FIRST_BYTE to 180.milliseconds,
            ),
        )
    } else {
        ProbeTiming(
            elapsed = 220.milliseconds,
            phases = mapOf(
                ProbePhase.DNS to 18.milliseconds,
                ProbePhase.TCP_CONNECT to 30.milliseconds,
                ProbePhase.HTTP_FIRST_BYTE to 172.milliseconds,
            ),
        )
    }

    is ProbeRequest.SystemDns -> ProbeTiming(
        elapsed = 16.milliseconds,
        phases = mapOf(ProbePhase.DNS to 16.milliseconds),
    )

    is ProbeRequest.DirectDns -> ProbeTiming(
        elapsed = 12.milliseconds,
        phases = mapOf(ProbePhase.DNS to 12.milliseconds),
    )

    is ProbeRequest.DnsOverHttps -> ProbeTiming(
        elapsed = 300.milliseconds,
        phases = mapOf(
            ProbePhase.DNS to 20.milliseconds,
            ProbePhase.TCP_CONNECT to 40.milliseconds,
            ProbePhase.TLS_HANDSHAKE to 100.milliseconds,
            ProbePhase.HTTP_FIRST_BYTE to 140.milliseconds,
        ),
    )

    is ProbeRequest.DnsOverTls -> ProbeTiming(
        elapsed = 320.milliseconds,
        phases = mapOf(
            ProbePhase.DNS to 20.milliseconds,
            ProbePhase.TCP_CONNECT to 30.milliseconds,
            ProbePhase.TLS_HANDSHAKE to 270.milliseconds,
        ),
    )

    is ProbeRequest.IcmpEcho -> ProbeTiming(elapsed = 250.milliseconds)
}

private data class DemoSpec(
    val config: ProbeConfig,
    val groupId: String,
    val groupName: String,
)

private val demoSpecs = listOf(
    DemoSpec(
        config = config(
            id = "https-global",
            name = "Global HTTPS",
            request = ProbeRequest.Http(
                url = URI("https://example.com/"),
                method = HttpMethod.GET,
                expected = HttpExpectation(200..299, "Example Domain"),
                family = IpFamily.IPV4,
            ),
            timeout = 5.seconds,
            revision = 2,
        ),
        groupId = "global",
        groupName = "Global services",
    ),
    DemoSpec(
        config = config(
            id = "tcp-global",
            name = "Global TCP",
            request = ProbeRequest.TcpConnect(
                SocketEndpoint("192.0.2.1", 443, IpFamily.IPV4),
            ),
            timeout = 4.seconds,
        ),
        groupId = "global",
        groupName = "Global services",
    ),
    DemoSpec(
        config = config(
            id = "tls-global",
            name = "Global TLS",
            request = ProbeRequest.TlsHandshake(
                endpoint = SocketEndpoint("example.net", 443, IpFamily.IPV4),
                serverName = "example.net",
                minimumVersion = TlsVersion.TLS_1_2,
            ),
            timeout = 4.seconds,
        ),
        groupId = "global",
        groupName = "Global services",
    ),
    DemoSpec(
        config = config(
            id = "https-backup",
            name = "Backup HTTPS",
            request = ProbeRequest.Http(
                url = URI("https://example.org/"),
                method = HttpMethod.GET,
                expected = HttpExpectation(200..299),
            ),
            timeout = 5.seconds,
        ),
        groupId = "global",
        groupName = "Global services",
    ),
    DemoSpec(
        config = config(
            id = "https-retained",
            name = "Retained HTTPS",
            request = ProbeRequest.Http(
                url = URI("https://example.net/"),
                method = HttpMethod.HEAD,
                expected = HttpExpectation(200..399),
            ),
            timeout = 5.seconds,
            revision = 3,
        ),
        groupId = "global",
        groupName = "Global services",
    ),
    DemoSpec(
        config = config(
            id = "http-regional",
            name = "Regional HTTP",
            request = ProbeRequest.Http(
                url = URI("http://example.com/"),
                method = HttpMethod.GET,
                expected = HttpExpectation(200..299),
            ),
            timeout = 5.seconds,
        ),
        groupId = "regional",
        groupName = "Regional services",
    ),
    DemoSpec(
        config = config(
            id = "icmp-regional",
            name = "Regional ICMP",
            request = ProbeRequest.IcmpEcho(
                host = "192.0.2.1",
                packetCount = 4,
                minimumReplies = 3,
                family = IpFamily.IPV4,
            ),
            timeout = 3.seconds,
        ),
        groupId = "regional",
        groupName = "Regional services",
    ),
    DemoSpec(
        config = config(
            id = "system-dns",
            name = "System DNS",
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("example.com", 1),
                expected = DnsExpectation(responseCode = 0, requiresAnswer = true),
            ),
            timeout = 3.seconds,
        ),
        groupId = "dns",
        groupName = "DNS",
    ),
    DemoSpec(
        config = config(
            id = "dns-udp",
            name = "DNS over UDP",
            request = ProbeRequest.DirectDns(
                resolver = SocketEndpoint("192.0.2.53", 53, IpFamily.IPV4),
                transport = DnsTransport.UDP,
                question = DnsQuestion("example.com", 1),
                expected = DnsExpectation(responseCode = 0, requiresAnswer = true),
            ),
            timeout = 3.seconds,
        ),
        groupId = "dns",
        groupName = "DNS",
    ),
    DemoSpec(
        config = config(
            id = "dns-tcp",
            name = "DNS over TCP",
            request = ProbeRequest.DirectDns(
                resolver = SocketEndpoint("192.0.2.54", 53, IpFamily.IPV4),
                transport = DnsTransport.TCP,
                question = DnsQuestion("example.com", 1),
                expected = DnsExpectation(responseCode = 0, requiresAnswer = true),
            ),
            timeout = 3.seconds,
        ),
        groupId = "dns",
        groupName = "DNS",
    ),
    DemoSpec(
        config = config(
            id = "doh",
            name = "DNS over HTTPS",
            request = ProbeRequest.DnsOverHttps(
                resolver = URI("https://dns.example/dns-query"),
                question = DnsQuestion("example.com", 1),
                expected = DnsExpectation(responseCode = 0, requiresAnswer = true),
                family = IpFamily.IPV4,
            ),
            timeout = 5.seconds,
        ),
        groupId = "dns",
        groupName = "DNS",
    ),
    DemoSpec(
        config = config(
            id = "dot",
            name = "DNS over TLS",
            request = ProbeRequest.DnsOverTls(
                resolver = SocketEndpoint("dns.example", 853, IpFamily.IPV4),
                serverName = "dns.example",
                question = DnsQuestion("example.com", 1),
                expected = DnsExpectation(responseCode = 0, requiresAnswer = true),
            ),
            timeout = 5.seconds,
        ),
        groupId = "dns",
        groupName = "DNS",
    ),
)

private fun config(
    id: String,
    name: String,
    request: ProbeRequest,
    timeout: Duration,
    revision: Long = 1,
): ProbeConfig {
    val probeId = ProbeId(id)
    return if (revision == 1L) {
        ProbeConfig(probeId, name, request, timeout)
    } else {
        ProbeConfig.restore(
            key = ProbeKey(probeId, ProbeRevision(revision)),
            name = name,
            request = request,
            timeout = timeout,
        )
    }
}

private val DEMO_NETWORK = NetworkId("demo-network")
private val DEMO_TIME: Instant = Instant.parse("2026-11-27T15:40:00Z")
