package io.github.chuck852.linksonda.ui.overview

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.ui.checks.ProbeListItem

enum class OverviewMonitoring { NOT_CONFIGURED, RUNNING, PAUSED }

enum class OverviewFreshness { MISSING, FRESH, STALE }

enum class ManualCheckState { UNAVAILABLE, IDLE, RUNNING }

data class NetworkUiSummary(
    /** False until a real network snapshot has been measured. */
    val contextKnown: Boolean = false,
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

/**
 * Presentation state for the overview and checks. Production starts from [Empty] until the
 * monitoring engine and local repositories provide confirmed results. Demo values exist only in the
 * debug source set.
 */
data class OverviewUiState(
    val aggregateStatus: AggregateStatus = AggregateStatus.UNKNOWN,
    val freshness: OverviewFreshness = OverviewFreshness.MISSING,
    val lastUpdatedSecondsAgo: Long? = null,
    val monitoring: OverviewMonitoring = OverviewMonitoring.NOT_CONFIGURED,
    val network: NetworkUiSummary = NetworkUiSummary(),
    val activeProfile: ProfileUiSummary? = null,
    val groups: List<GroupUiSummary> = emptyList(),
    val probes: List<ProbeListItem> = emptyList(),
    val manualCheck: ManualCheckState = ManualCheckState.UNAVAILABLE,
    val isDemo: Boolean = false,
) {
    init {
        require(lastUpdatedSecondsAgo == null || lastUpdatedSecondsAgo >= 0)
        require(groups.map { it.id }.distinct().size == groups.size)
        require(probes.map { it.id }.distinct().size == probes.size)
    }

    fun probesFor(groupId: String): List<ProbeListItem> =
        probes.filter { it.groupId == groupId }

    fun probe(id: ProbeId): ProbeListItem? = probes.firstOrNull { it.id == id }

    companion object {
        val Empty = OverviewUiState()
    }
}
