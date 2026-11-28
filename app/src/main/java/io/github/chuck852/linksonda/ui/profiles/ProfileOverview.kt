package io.github.chuck852.linksonda.ui.profiles

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeGroup
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeState
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.ui.checks.ProbeListItem
import io.github.chuck852.linksonda.ui.overview.GroupUiSummary
import io.github.chuck852.linksonda.ui.overview.ManualCheckState
import io.github.chuck852.linksonda.ui.overview.OverviewMonitoring
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import io.github.chuck852.linksonda.ui.overview.ProfileUiSummary

const val UNGROUPED_GROUP_ID = "__ungrouped"

/**
 * Read-only projection of a stored profile. Results stay UNKNOWN/MISSING: this build has no
 * connected engine and must not show demo values in production.
 */
fun MonitoringProfile.toOverviewUiState(ungroupedGroupName: String): OverviewUiState {
    val configs = preset.probes.associateBy { it.id }
    val assigned = mutableSetOf<io.github.chuck852.linksonda.domain.ProbeId>()
    val groups = mutableListOf<GroupUiSummary>()
    val probes = mutableListOf<ProbeListItem>()

    for (group in this.groups) {
        addGroup(group, configs, assigned, groups, probes)
    }
    val ungrouped = preset.probes.filterNot { it.id in assigned }
    if (ungrouped.isNotEmpty()) {
        val group = GroupUiSummary(
            id = UNGROUPED_GROUP_ID,
            name = ungroupedGroupName,
            status = AggregateStatus.UNKNOWN,
            availableProbes = 0,
            totalProbes = ungrouped.size,
        )
        groups += group
        probes += ungrouped.map { config ->
            ProbeListItem(config, config.unknownState(), group.id, group.name)
        }
    }
    return OverviewUiState(
        monitoring = OverviewMonitoring.NOT_CONFIGURED,
        activeProfile = ProfileUiSummary(
            id = id.value,
            name = name,
            intervalSeconds = schedule.interval.inWholeSeconds.coerceAtLeast(1),
        ),
        groups = groups,
        probes = probes,
        manualCheck = ManualCheckState.UNAVAILABLE,
        isDemo = false,
    )
}

private fun addGroup(
    group: ProbeGroup,
    configs: Map<io.github.chuck852.linksonda.domain.ProbeId, ProbeConfig>,
    assigned: MutableSet<io.github.chuck852.linksonda.domain.ProbeId>,
    groups: MutableList<GroupUiSummary>,
    probes: MutableList<ProbeListItem>,
) {
    val members = group.probeIds.mapNotNull(configs::get).filter { it.id !in assigned }
    if (members.isEmpty()) return
    val summary = GroupUiSummary(
        id = group.name,
        name = group.name,
        status = AggregateStatus.UNKNOWN,
        availableProbes = 0,
        totalProbes = members.size,
    )
    groups += summary
    probes += members.map { config ->
        assigned += config.id
        ProbeListItem(config, config.unknownState(), summary.id, summary.name)
    }
}

private fun ProbeConfig.unknownState(): ProbeState = ProbeState(
    context = ProbeContext(key, null),
    status = ProbeStatus.UNKNOWN,
    lifecycle = ProbeLifecycle.IDLE,
    freshness = ProbeFreshness.MISSING,
)
