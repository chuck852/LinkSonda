package io.github.chuck852.linksonda.debug

import io.github.chuck852.linksonda.domain.AlertThresholds
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.MonitoringSchedule
import io.github.chuck852.linksonda.domain.NotificationSettings
import io.github.chuck852.linksonda.domain.Preset
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeGroup
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeState
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.ProfileId
import io.github.chuck852.linksonda.ui.checks.ProbeDraft
import io.github.chuck852.linksonda.ui.checks.ProbeListItem
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import kotlin.time.Duration.Companion.seconds

/**
 * Debug-only state transition used by the editor demo.
 *
 * A changed request or timeout produces a new configuration revision, so the old result is dropped
 * and the check starts from UNKNOWN. A rename keeps the same revision and its result.
 */
internal fun OverviewUiState.withSavedProbe(draft: ProbeDraft): OverviewUiState {
    val existing = probes.firstOrNull { it.id == draft.config.id }
    val item = ProbeListItem(
        config = draft.config,
        state = if (existing != null && existing.key == draft.config.key) {
            existing.state
        } else {
            ProbeState(
                context = ProbeContext(draft.config.key, null),
                status = ProbeStatus.UNKNOWN,
                lifecycle = ProbeLifecycle.IDLE,
                freshness = ProbeFreshness.MISSING,
            )
        },
        groupId = draft.groupId,
        groupName = draft.groupName,
    )
    val updatedProbes = if (existing == null) {
        probes + item
    } else {
        probes.map { if (it.id == draft.config.id) item else it }
    }
    return copy(probes = updatedProbes, groups = groupsFor(updatedProbes))
}

/** Debug-only profile assembled from the visible demo checks and saved through [DemoProfileRepository]. */
internal fun OverviewUiState.toMonitoringProfile(): MonitoringProfile = MonitoringProfile(
    id = ProfileId("demo-profile"),
    name = activeProfile?.name ?: "Demo profile",
    preset = Preset(
        id = "demo-preset",
        name = "Demo checks",
        probes = probes.map { it.config },
    ),
    groups = probes.groupBy { it.groupId }.map { (_, groupProbes) ->
        ProbeGroup(
            name = groupProbes.first().groupName,
            probeIds = groupProbes.map { it.id }.toSet(),
        )
    },
    schedule = MonitoringSchedule(
        interval = (activeProfile?.intervalSeconds ?: 60L).seconds,
        maxConcurrentProbes = 4,
    ),
    thresholds = AlertThresholds(consecutiveFailures = 3, consecutiveSuccesses = 2),
    notifications = NotificationSettings(enabled = true),
)
