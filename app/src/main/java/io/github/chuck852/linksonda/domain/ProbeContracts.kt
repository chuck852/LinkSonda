package io.github.chuck852.linksonda.domain

import java.time.Instant
import kotlinx.coroutines.flow.Flow

enum class ProbeMode { MANUAL, BACKGROUND }

data class ProbeRunRequest(
    val config: ProbeConfig,
    val network: NetworkSnapshot,
    val mode: ProbeMode,
)

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
     * Propagate coroutine cancellation and release any acquired resources.
     */
    suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult
}

interface ResultRepository {
    fun observeResults(context: ProbeContext): Flow<List<ProbeResult>>
    fun observeIncidents(profileId: ProfileId, networkId: NetworkId): Flow<List<Incident>>
    suspend fun saveResult(result: ProbeResult)
    suspend fun saveIncident(incident: Incident)
}

interface ProfileRepository {
    fun observeProfiles(): Flow<List<MonitoringProfile>>
    suspend fun saveProfile(profile: MonitoringProfile)
}
