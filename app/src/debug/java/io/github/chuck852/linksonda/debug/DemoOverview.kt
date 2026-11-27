package io.github.chuck852.linksonda.debug

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.ui.overview.GroupUiSummary
import io.github.chuck852.linksonda.ui.overview.ManualCheckState
import io.github.chuck852.linksonda.ui.overview.NetworkUiSummary
import io.github.chuck852.linksonda.ui.overview.OverviewFreshness
import io.github.chuck852.linksonda.ui.overview.OverviewMonitoring
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import io.github.chuck852.linksonda.ui.overview.ProbeDetail
import io.github.chuck852.linksonda.ui.overview.ProbeFailure
import io.github.chuck852.linksonda.ui.overview.ProbeKind
import io.github.chuck852.linksonda.ui.overview.ProbeUiSummary
import io.github.chuck852.linksonda.ui.overview.ProfileUiSummary

/**
 * Debug-only demonstration data. Every scenario is marked with [OverviewUiState.isDemo] and is
 * rendered with a visible banner. No network request is made and no result is persisted.
 */
enum class DemoScenario { EMPTY, UNKNOWN, RUNNING, PARTIAL, DOWN, UP }

fun demoOverview(scenario: DemoScenario): OverviewUiState = when (scenario) {
    DemoScenario.EMPTY -> OverviewUiState(isDemo = true)
    DemoScenario.UNKNOWN -> unknownScenario()
    DemoScenario.RUNNING -> runningScenario()
    DemoScenario.PARTIAL -> partialScenario()
    DemoScenario.DOWN -> downScenario()
    DemoScenario.UP -> upScenario()
}

private fun unknownScenario(): OverviewUiState {
    val probes = listOf(
        probe("global-http", "global", "Global HTTP", ProbeKind.HTTPS, "https://example.com/", ProbeStatus.UNKNOWN),
        probe("global-tls", "global", "Global TLS", ProbeKind.TLS, "example.net:443", ProbeStatus.UNKNOWN),
        probe("regional-http", "regional", "Regional HTTP", ProbeKind.HTTPS, "https://example.org/", ProbeStatus.UNKNOWN),
        probe("regional-tcp", "regional", "Regional TCP", ProbeKind.TCP, "192.0.2.1:443", ProbeStatus.UNKNOWN),
    )
    return base(
        aggregate = AggregateStatus.UNKNOWN,
        freshness = OverviewFreshness.MISSING,
        monitoring = OverviewMonitoring.NOT_CONFIGURED,
        network = NetworkUiSummary(
            transports = setOf(NetworkTransport.WIFI),
            validation = NetworkValidation.UNKNOWN,
        ),
        groups = listOf(
            group("global", "Global services", AggregateStatus.UNKNOWN, probes),
            group("regional", "Regional services", AggregateStatus.UNKNOWN, probes),
        ),
        probes = probes,
        manualCheck = ManualCheckState.UNAVAILABLE,
    )
}

private fun runningScenario(): OverviewUiState {
    val probes = listOf(
        probe("global-http", "global", "Global HTTP", ProbeKind.HTTPS, "https://example.com/", ProbeStatus.UP, ProbeDetail.Latency(42)),
        probe("global-tls", "global", "Global TLS", ProbeKind.TLS, "example.net:443", ProbeStatus.UP, ProbeDetail.Latency(88)),
        probe("regional-http", "regional", "Regional HTTP", ProbeKind.HTTPS, "https://example.org/", ProbeStatus.UP, ProbeDetail.Latency(51)),
        probe("regional-tcp", "regional", "Regional TCP", ProbeKind.TCP, "192.0.2.1:443", ProbeStatus.UP, ProbeDetail.Latency(120)),
    )
    return base(
        aggregate = AggregateStatus.AVAILABLE,
        freshness = OverviewFreshness.FRESH,
        secondsAgo = 4,
        monitoring = OverviewMonitoring.RUNNING,
        network = NetworkUiSummary(
            transports = setOf(NetworkTransport.WIFI),
            validation = NetworkValidation.VALIDATED,
        ),
        groups = listOf(
            group("global", "Global services", AggregateStatus.AVAILABLE, probes),
            group("regional", "Regional services", AggregateStatus.AVAILABLE, probes),
        ),
        probes = probes,
        manualCheck = ManualCheckState.RUNNING,
    )
}

private fun partialScenario(): OverviewUiState {
    val probes = listOf(
        probe("global-http", "global", "Global HTTP", ProbeKind.HTTPS, "https://example.com/", ProbeStatus.UP, ProbeDetail.Latency(46)),
        probe("global-tls", "global", "Global TLS", ProbeKind.TLS, "example.net:443", ProbeStatus.UP, ProbeDetail.Latency(91)),
        probe("regional-http", "regional", "Regional HTTP", ProbeKind.HTTPS, "https://example.org/", ProbeStatus.UP, ProbeDetail.Latency(58)),
        probe("regional-tcp", "regional", "Regional TCP", ProbeKind.TCP, "192.0.2.1:443", ProbeStatus.DOWN, ProbeDetail.Failure(ProbeFailure.TIMEOUT)),
        probe("system-dns", "dns", "System DNS", ProbeKind.DNS, "192.0.2.53", ProbeStatus.UP, ProbeDetail.Latency(18)),
        probe("backup-dns", "dns", "Backup DNS", ProbeKind.DNS, "192.0.2.54", ProbeStatus.UP, ProbeDetail.Latency(24)),
    )
    return base(
        aggregate = AggregateStatus.PARTIAL,
        freshness = OverviewFreshness.FRESH,
        secondsAgo = 12,
        monitoring = OverviewMonitoring.RUNNING,
        network = NetworkUiSummary(
            transports = setOf(NetworkTransport.CELLULAR, NetworkTransport.VPN),
            validation = NetworkValidation.VALIDATED,
        ),
        groups = listOf(
            group("global", "Global services", AggregateStatus.AVAILABLE, probes),
            group("regional", "Regional services", AggregateStatus.PARTIAL, probes),
            group("dns", "DNS", AggregateStatus.AVAILABLE, probes),
        ),
        probes = probes,
        manualCheck = ManualCheckState.IDLE,
    )
}

private fun downScenario(): OverviewUiState {
    val probes = listOf(
        probe("global-http", "global", "Global HTTP", ProbeKind.HTTPS, "https://example.com/", ProbeStatus.DOWN, ProbeDetail.Failure(ProbeFailure.TIMEOUT)),
        probe("global-tls", "global", "Global TLS", ProbeKind.TLS, "example.net:443", ProbeStatus.DOWN, ProbeDetail.Failure(ProbeFailure.UNREACHABLE)),
        probe("regional-http", "regional", "Regional HTTP", ProbeKind.HTTPS, "https://example.org/", ProbeStatus.DOWN, ProbeDetail.Failure(ProbeFailure.DNS)),
        probe("regional-tcp", "regional", "Regional TCP", ProbeKind.TCP, "192.0.2.1:443", ProbeStatus.DOWN, ProbeDetail.Failure(ProbeFailure.REFUSED)),
    )
    return base(
        aggregate = AggregateStatus.UNAVAILABLE,
        freshness = OverviewFreshness.FRESH,
        secondsAgo = 8,
        monitoring = OverviewMonitoring.RUNNING,
        network = NetworkUiSummary(
            transports = setOf(NetworkTransport.WIFI),
            validation = NetworkValidation.UNVALIDATED,
        ),
        groups = listOf(
            group("global", "Global services", AggregateStatus.UNAVAILABLE, probes),
            group("regional", "Regional services", AggregateStatus.UNAVAILABLE, probes),
        ),
        probes = probes,
        manualCheck = ManualCheckState.IDLE,
    )
}

private fun upScenario(): OverviewUiState {
    val probes = listOf(
        probe("global-http", "global", "Global HTTP", ProbeKind.HTTPS, "https://example.com/", ProbeStatus.UP, ProbeDetail.Latency(38)),
        probe("global-tls", "global", "Global TLS", ProbeKind.TLS, "example.net:443", ProbeStatus.UP, ProbeDetail.Latency(74)),
        probe("regional-http", "regional", "Regional HTTP", ProbeKind.HTTPS, "https://example.org/", ProbeStatus.UP, ProbeDetail.Latency(62)),
        probe("regional-tcp", "regional", "Regional TCP", ProbeKind.TCP, "192.0.2.1:443", ProbeStatus.UP, ProbeDetail.Latency(105)),
        probe("system-dns", "dns", "System DNS", ProbeKind.DNS, "192.0.2.53", ProbeStatus.UP, ProbeDetail.Latency(16)),
        probe("backup-dns", "dns", "Backup DNS", ProbeKind.DNS, "192.0.2.54", ProbeStatus.UP, ProbeDetail.Latency(21)),
    )
    return base(
        aggregate = AggregateStatus.AVAILABLE,
        freshness = OverviewFreshness.FRESH,
        secondsAgo = 6,
        monitoring = OverviewMonitoring.RUNNING,
        network = NetworkUiSummary(
            transports = setOf(NetworkTransport.ETHERNET),
            validation = NetworkValidation.VALIDATED,
        ),
        groups = listOf(
            group("global", "Global services", AggregateStatus.AVAILABLE, probes),
            group("regional", "Regional services", AggregateStatus.AVAILABLE, probes),
            group("dns", "DNS", AggregateStatus.AVAILABLE, probes),
        ),
        probes = probes,
        manualCheck = ManualCheckState.IDLE,
    )
}

private fun base(
    aggregate: AggregateStatus,
    freshness: OverviewFreshness,
    monitoring: OverviewMonitoring,
    network: NetworkUiSummary,
    groups: List<GroupUiSummary>,
    probes: List<ProbeUiSummary>,
    manualCheck: ManualCheckState,
    secondsAgo: Long? = null,
): OverviewUiState = OverviewUiState(
    aggregateStatus = aggregate,
    freshness = freshness,
    lastUpdatedSecondsAgo = secondsAgo,
    monitoring = monitoring,
    network = network,
    activeProfile = ProfileUiSummary(name = "Default profile", intervalSeconds = 60),
    groups = groups,
    probes = probes,
    manualCheck = manualCheck,
    isDemo = true,
)

private fun probe(
    id: String,
    groupId: String,
    name: String,
    kind: ProbeKind,
    target: String,
    status: ProbeStatus,
    detail: ProbeDetail = ProbeDetail.None,
): ProbeUiSummary = ProbeUiSummary(
    id = id,
    groupId = groupId,
    name = name,
    kind = kind,
    target = target,
    status = status,
    detail = detail,
)

private fun group(
    id: String,
    name: String,
    status: AggregateStatus,
    allProbes: List<ProbeUiSummary>,
): GroupUiSummary {
    val probes = allProbes.filter { it.groupId == id }
    return GroupUiSummary(
        id = id,
        name = name,
        status = status,
        availableProbes = probes.count { it.status == ProbeStatus.UP },
        totalProbes = probes.size,
    )
}
