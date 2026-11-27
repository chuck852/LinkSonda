package io.github.chuck852.linksonda.debug

import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Debug-only implementation of the profile repository contract.
 *
 * It keeps saved profiles in memory for the demo activity and is never part of the release APK.
 * Production uses the real repository once local storage is implemented.
 */
class DemoProfileRepository(initial: List<MonitoringProfile> = emptyList()) : ProfileRepository {
    private val profiles = MutableStateFlow(initial)

    override fun observeProfiles(): Flow<List<MonitoringProfile>> = profiles.asStateFlow()

    override suspend fun saveProfile(profile: MonitoringProfile) {
        profiles.update { current ->
            current.filterNot { it.id == profile.id } + profile
        }
    }
}
