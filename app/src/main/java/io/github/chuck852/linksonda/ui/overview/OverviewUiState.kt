package io.github.chuck852.linksonda.ui.overview

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeStatus

enum class OverviewMonitoring { NOT_CONFIGURED, RUNNING, PAUSED }

enum class OverviewFreshness { MISSING, FRESH, STALE }

enum class ManualCheckState { UNAVAILABLE, IDLE, RUNNING }

enum class ProbeKind { TCP, HTTPS, DNS, TLS, ICMP }

enum class ProbeFailure { TIMEOUT, DNS, TLS, HTTP_STATUS, REFUSED, UNREACHABLE }

sealed interface ProbeDetail {
    data class Latency(val milliseconds: Long) : ProbeDetail {
        init { require(milliseconds >= 0) }
    }

    data class Failure(val reason: ProbeFailure) : ProbeDetail

    data object None : ProbeDetail
}

data class NetworkUiSummary(
    val transports: Set<NetworkTransport> = emptySet(),
    val validation: NetworkValidation = NetworkValidation.UNKNOWN,
)

data class ProfileUiSummary(
    val name: String,
    val intervalSeconds: Long,
) {
    init {
        require(name.isNotBlank())
        require(intervalSeconds > 0)
    }
}

data class GroupUiSummary(
    val id: String,
    val name: String,
    val status: AggregateStatus,
    val availableProbes: Int,
    val totalProbes: Int,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(totalProbes >= 0)
        require(availableProbes in 0..totalProbes)
    }
}

data class ProbeUiSummary(
    val id: String,
    val groupId: String,
    val name: String,
    val kind: ProbeKind,
    val target: String,
    val status: ProbeStatus,
    val detail: ProbeDetail = ProbeDetail.None,
) {
    init {
        require(id.isNotBlank() && groupId.isNotBlank())
        require(name.isNotBlank() && target.isNotBlank())
    }
}

/**
 * Presentation state for the overview. Production starts from [Empty] until the monitoring engine
 * and local repositories provide confirmed results. Demo values exist only in the debug source set.
 */
data class OverviewUiState(
    val aggregateStatus: AggregateStatus = AggregateStatus.UNKNOWN,
    val freshness: OverviewFreshness = OverviewFreshness.MISSING,
    val lastUpdatedSecondsAgo: Long? = null,
    val monitoring: OverviewMonitoring = OverviewMonitoring.NOT_CONFIGURED,
    val network: NetworkUiSummary = NetworkUiSummary(),
    val activeProfile: ProfileUiSummary? = null,
    val groups: List<GroupUiSummary> = emptyList(),
    val probes: List<ProbeUiSummary> = emptyList(),
    val manualCheck: ManualCheckState = ManualCheckState.UNAVAILABLE,
    val isDemo: Boolean = false,
) {
    init {
        require(lastUpdatedSecondsAgo == null || lastUpdatedSecondsAgo >= 0)
        require(groups.map { it.id }.distinct().size == groups.size)
        require(probes.map { it.id }.distinct().size == probes.size)
    }

    fun probesFor(groupId: String): List<ProbeUiSummary> =
        probes.filter { it.groupId == groupId }

    companion object {
        val Empty = OverviewUiState()
    }
}
