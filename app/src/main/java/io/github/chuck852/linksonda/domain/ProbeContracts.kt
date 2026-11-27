package io.github.chuck852.linksonda.domain

import java.time.Instant
import kotlinx.coroutines.flow.Flow

enum class ProbeMode { MANUAL, BACKGROUND }

data class ProbeRunRequest(
    val config: ProbeConfig,
    val network: NetworkSnapshot,
    val mode: ProbeMode,
) {
    val context: ProbeContext get() = ProbeContext(config.key, network.id)
}

data class ProbeAttempt(val endpointKey: String, val infrastructureKey: String) {
    init { require(endpointKey.isNotBlank() && infrastructureKey.isNotBlank()) }
}

sealed interface AdmissionDecision {
    data object Allowed : AdmissionDecision
    data class Denied(val reason: SkipReason, val nextAllowedAt: Instant? = null) : AdmissionDecision {
        init { require(reason in setOf(SkipReason.BUDGET, SkipReason.COOLDOWN, SkipReason.NOT_ALLOWED)) }
    }
}

/** A run-scoped view of the shared admission policy. Each grant accounts for one outbound attempt. */
fun interface ProbeAdmission {
    suspend fun authorize(attempt: ProbeAttempt): AdmissionDecision
}

interface ProbeRunner {
    /**
     * Authorize every observable outbound attempt, including retries, redirects and fallbacks.
     * Keep the original request context if the network or stored configuration changes during the run.
     * Propagate coroutine cancellation and release any acquired resources.
     */
    suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult
}

interface ResultRepository {
    /** History for one probe configuration revision on one network. */
    fun observeResults(context: ProbeContext): Flow<List<ProbeResult>>
    fun observeIncidents(profileId: ProfileId, networkId: NetworkId): Flow<List<Incident>>
    /**
     * Stores the result and its network snapshot atomically. A snapshot is required whenever the
     * result has a network; retention must never leave a result without its network context.
     */
    suspend fun saveResult(result: ProbeResult, network: NetworkSnapshot?)
    /** Stores the incident and its network snapshot atomically. */
    suspend fun saveIncident(incident: Incident, network: NetworkSnapshot)
}

interface ProfileRepository {
    fun observeProfiles(): Flow<List<MonitoringProfile>>
    /**
     * Persist revisions with their definitions and reject stale or conflicting writes for a probe ID.
     * Independently editable copies use a new probe ID.
     */
    suspend fun saveProfile(profile: MonitoringProfile)
    suspend fun deleteProfile(profileId: ProfileId)
}
