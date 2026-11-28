package io.github.chuck852.linksonda.domain

import kotlin.time.Duration.Companion.seconds

/** Product defaults for a new profile; the endpoint catalog is intentionally empty. */
object ProfileDefaults {
    val interval = 60.seconds
    const val MAX_CONCURRENT_PROBES = 4
    const val CONSECUTIVE_FAILURES = 3
    const val CONSECUTIVE_SUCCESSES = 2
    val notifications = NotificationSettings(enabled = false)
}

fun newMonitoringProfile(id: ProfileId, name: String): MonitoringProfile = MonitoringProfile(
    id = id,
    name = name,
    preset = Preset("${id.value}:preset", "Custom", emptyList()),
    groups = emptyList(),
    schedule = MonitoringSchedule(ProfileDefaults.interval, ProfileDefaults.MAX_CONCURRENT_PROBES),
    thresholds = AlertThresholds(
        consecutiveFailures = ProfileDefaults.CONSECUTIVE_FAILURES,
        consecutiveSuccesses = ProfileDefaults.CONSECUTIVE_SUCCESSES,
    ),
    notifications = ProfileDefaults.notifications,
)

/**
 * Snapshot copy: every probe gets a new id and revision, so editing the copy never changes the
 * source. Group names stay scoped to the new profile.
 */
fun copyMonitoringProfile(
    source: MonitoringProfile,
    newId: ProfileId,
    newName: String,
): MonitoringProfile {
    val probeIds = source.preset.probes.associate { it.id to ProbeId("${newId.value}:${it.id.value}") }
    val probes = source.preset.probes.map { probe ->
        ProbeConfig(
            id = probeIds.getValue(probe.id),
            name = probe.name,
            request = probe.request,
            timeout = probe.timeout,
        )
    }
    val groups = source.groups.map { group ->
        ProbeGroup(
            name = group.name,
            probeIds = group.probeIds.mapTo(linkedSetOf()) { probeIds.getValue(it) },
        )
    }
    return MonitoringProfile(
        id = newId,
        name = newName,
        preset = Preset("${newId.value}:preset", source.preset.name, probes),
        groups = groups,
        schedule = source.schedule,
        thresholds = source.thresholds,
        notifications = source.notifications,
    )
}

/** Add a check or replace it by id and put it in exactly one group. */
fun MonitoringProfile.addOrReplaceProbe(config: ProbeConfig, groupName: String): MonitoringProfile {
    val name = groupName.trim()
    require(name.isNotBlank())
    val probes = if (preset.probes.any { it.id == config.id }) {
        preset.probes.map { if (it.id == config.id) config else it }
    } else {
        preset.probes + config
    }
    val groupsWithoutProbe = groups.mapNotNull { group ->
        val remaining = group.probeIds - config.id
        if (remaining.isEmpty()) null else ProbeGroup(group.name, remaining)
    }
    val targetIndex = groupsWithoutProbe.indexOfFirst { it.name == name }
    val updatedGroups = if (targetIndex >= 0) {
        groupsWithoutProbe.mapIndexed { index, group ->
            if (index == targetIndex) {
                ProbeGroup(group.name, group.probeIds + config.id)
            } else {
                group
            }
        }
    } else {
        groupsWithoutProbe + ProbeGroup(name, setOf(config.id))
    }
    return copy(preset = preset.copy(probes = probes), groups = updatedGroups)
}

/** Remove a check; a group disappears with its last member. */
fun MonitoringProfile.removeProbe(probeId: ProbeId): MonitoringProfile {
    val probes = preset.probes.filterNot { it.id == probeId }
    val groups = groups.mapNotNull { group ->
        val remaining = group.probeIds - probeId
        if (remaining.isEmpty()) null else ProbeGroup(group.name, remaining)
    }
    return copy(preset = preset.copy(probes = probes), groups = groups)
}

fun MonitoringProfile.renameGroup(oldName: String, newName: String): MonitoringProfile {
    val name = newName.trim()
    require(name.isNotBlank())
    require(groups.any { it.name == oldName }) { "Group does not exist" }
    require(groups.none { it.name == name && it.name != oldName }) { "Group already exists" }
    return copy(groups = groups.map { if (it.name == oldName) ProbeGroup(name, it.probeIds) else it })
}
