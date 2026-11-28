package io.github.chuck852.linksonda.domain

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ProfileId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class Preset(val id: String, val name: String, val probes: List<ProbeConfig>) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(probes.map { it.id }.distinct().size == probes.size)
    }
}

data class ProbeGroup(val name: String, val probeIds: Set<ProbeId>) {
    init { require(name.isNotBlank() && probeIds.isNotEmpty()) }
}

data class MonitoringSchedule(val interval: Duration, val maxConcurrentProbes: Int) {
    init {
        require(interval.isFinite() && interval.isPositive())
        require(interval == interval.inWholeMilliseconds.milliseconds) {
            "Schedule interval must be a whole number of milliseconds"
        }
        require(maxConcurrentProbes > 0)
    }
}

data class AlertThresholds(val consecutiveFailures: Int, val consecutiveSuccesses: Int) {
    init { require(consecutiveFailures > 0 && consecutiveSuccesses > 0) }
}

data class NotificationSettings(val enabled: Boolean, val repeatInterval: Duration? = null) {
    init {
        require(repeatInterval == null || repeatInterval.isFinite() && repeatInterval.isPositive())
        require(repeatInterval == null || repeatInterval == repeatInterval.inWholeMilliseconds.milliseconds) {
            "Notification repeat interval must be a whole number of milliseconds"
        }
    }
}

data class MonitoringProfile(
    val id: ProfileId,
    val name: String,
    val preset: Preset,
    val groups: List<ProbeGroup>,
    val schedule: MonitoringSchedule,
    val thresholds: AlertThresholds,
    val notifications: NotificationSettings,
) {
    init {
        require(name.isNotBlank())
        val configuredIds = preset.probes.map { it.id }.toSet()
        require(groups.all { configuredIds.containsAll(it.probeIds) })
        require(groups.map { it.name }.distinct().size == groups.size) { "Group names must be unique" }
    }
}

enum class HypothesisKind { DNS_FAILURE, PROTOCOL_FILTERING, CAPTIVE_PORTAL, REGIONAL_RESTRICTION, SERVICE_FAILURE }

@Serializable
data class DiagnosticEvidence(
    val context: ProbeContext,
    @Contextual val recordedAt: Instant,
)

@Serializable
data class DiagnosticHypothesis(
    val kind: HypothesisKind,
    val evidence: Set<DiagnosticEvidence>,
) {
    init { require(evidence.isNotEmpty()) }
}

@Serializable
data class Incident(
    val id: String,
    val profileId: ProfileId,
    val networkId: NetworkId,
    @Contextual val openedAt: Instant,
    @Contextual val recoveredAt: Instant? = null,
    val hypotheses: List<DiagnosticHypothesis> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(recoveredAt == null || !recoveredAt.isBefore(openedAt))
        require(hypotheses.all { hypothesis -> hypothesis.evidence.all { it.context.networkId == networkId } })
    }
}
