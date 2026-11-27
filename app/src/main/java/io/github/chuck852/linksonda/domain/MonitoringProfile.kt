package io.github.chuck852.linksonda.domain

import java.time.Instant
import kotlin.time.Duration

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
        require(maxConcurrentProbes > 0)
    }
}

data class AlertThresholds(val consecutiveFailures: Int, val consecutiveSuccesses: Int) {
    init { require(consecutiveFailures > 0 && consecutiveSuccesses > 0) }
}

data class NotificationSettings(val enabled: Boolean, val repeatInterval: Duration? = null) {
    init { require(repeatInterval == null || repeatInterval.isFinite() && repeatInterval.isPositive()) }
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
    }
}

enum class HypothesisKind { DNS_FAILURE, PROTOCOL_FILTERING, CAPTIVE_PORTAL, REGIONAL_RESTRICTION, SERVICE_FAILURE }

data class DiagnosticEvidence(val context: ProbeContext, val recordedAt: Instant)

data class DiagnosticHypothesis(
    val kind: HypothesisKind,
    val evidence: Set<DiagnosticEvidence>,
) {
    init { require(evidence.isNotEmpty()) }
}

data class Incident(
    val id: String,
    val profileId: ProfileId,
    val networkId: NetworkId,
    val openedAt: Instant,
    val recoveredAt: Instant? = null,
    val hypotheses: List<DiagnosticHypothesis> = emptyList(),
) {
    init {
        require(id.isNotBlank())
        require(hypotheses.all { hypothesis -> hypothesis.evidence.all { it.context.networkId == networkId } })
    }
}
