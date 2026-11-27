package io.github.chuck852.linksonda.storage

import androidx.room.withTransaction
import io.github.chuck852.linksonda.domain.AlertThresholds
import io.github.chuck852.linksonda.domain.Incident
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.MonitoringSchedule
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkSnapshot
import io.github.chuck852.linksonda.domain.NotificationSettings
import io.github.chuck852.linksonda.domain.Preset
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeGroup
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeKey
import io.github.chuck852.linksonda.domain.ProbeRevision
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProfileId
import io.github.chuck852.linksonda.domain.ProfileRepository
import io.github.chuck852.linksonda.domain.ResultRepository
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StaleProbeRevisionException(probeId: ProbeId, revision: ProbeRevision) :
    IllegalStateException("Probe ${probeId.value} revision ${revision.value} is older than stored history")

class ConflictingProbeRevisionException(probeId: ProbeId, revision: ProbeRevision) :
    IllegalStateException("Probe ${probeId.value} revision ${revision.value} has different execution parameters")

class RoomProfileRepository(
    private val database: LinkSondaDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : ProfileRepository {
    private val profileDao = database.profileDao()
    private val configDao = database.probeConfigDao()

    override fun observeProfiles(): Flow<List<MonitoringProfile>> =
        profileDao.observeProfiles().map {
            database.withTransaction {
                profileDao.getProfiles().map { profile -> loadProfile(profile) }
            }
        }

    override suspend fun saveProfile(profile: MonitoringProfile) {
        database.withTransaction {
            profile.preset.probes.forEach { saveConfig(it) }
            profileDao.deleteGroupMembers(profile.id.value)
            profileDao.deleteGroups(profile.id.value)
            profileDao.deleteProbes(profile.id.value)
            profileDao.upsert(profile.toEntity())
            profileDao.insertProbes(
                profile.preset.probes.mapIndexed { position, config ->
                    ProfileProbeEntity(
                        profileId = profile.id.value,
                        probeId = config.id.value,
                        revision = config.revision.value,
                        position = position,
                    )
                },
            )
            profileDao.insertGroups(
                profile.groups.mapIndexed { position, group ->
                    ProbeGroupEntity(profile.id.value, group.name, position)
                },
            )
            profile.groups.forEach { group ->
                profileDao.insertGroupMembers(
                    group.probeIds.map { probeId ->
                        ProbeGroupMemberEntity(profile.id.value, group.name, probeId.value)
                    },
                )
            }
        }
    }

    override suspend fun deleteProfile(profileId: ProfileId) {
        database.withTransaction {
            profileDao.deleteGroupMembers(profileId.value)
            profileDao.deleteGroups(profileId.value)
            profileDao.deleteProbes(profileId.value)
            profileDao.deleteProfile(profileId.value)
        }
    }

    private suspend fun saveConfig(config: ProbeConfig) {
        val latest = configDao.latest(config.id.value)
        if (latest == null) {
            configDao.insert(config.toEntity())
            return
        }
        if (config.revision.value < latest.revision) {
            throw StaleProbeRevisionException(config.id, config.revision)
        }
        if (config.revision.value > latest.revision) {
            configDao.insert(config.toEntity())
            return
        }
        if (latest.request != config.request || latest.timeoutMillis != config.timeout.inWholeMilliseconds) {
            throw ConflictingProbeRevisionException(config.id, config.revision)
        }
        if (latest.name != config.name) {
            configDao.updateName(config.id.value, config.revision.value, config.name)
        }
    }

    private suspend fun loadProfile(entity: ProfileEntity): MonitoringProfile {
        val links = profileDao.probes(entity.profileId)
        val probes = links.map { link ->
            val config = checkNotNull(configDao.find(link.probeId, link.revision)) {
                "Missing probe configuration ${link.probeId} @ ${link.revision}"
            }
            config.toDomain()
        }
        val groups = profileDao.groups(entity.profileId).map { group ->
            ProbeGroup(
                name = group.name,
                probeIds = profileDao.groupMembers(entity.profileId, group.name)
                    .map { ProbeId(it.probeId) }
                    .toSet(),
            )
        }
        return MonitoringProfile(
            id = ProfileId(entity.profileId),
            name = entity.name,
            preset = Preset(entity.presetId, entity.presetName, probes),
            groups = groups,
            schedule = MonitoringSchedule(
                interval = entity.intervalMillis.milliseconds,
                maxConcurrentProbes = entity.maxConcurrentProbes,
            ),
            thresholds = AlertThresholds(
                consecutiveFailures = entity.consecutiveFailures,
                consecutiveSuccesses = entity.consecutiveSuccesses,
            ),
            notifications = NotificationSettings(
                enabled = entity.notificationsEnabled,
                repeatInterval = entity.repeatIntervalMillis?.milliseconds,
            ),
        )
    }

    private fun ProbeConfig.toEntity() = ProbeConfigEntity(
        probeId = id.value,
        revision = revision.value,
        name = name,
        request = request,
        timeoutMillis = timeout.inWholeMilliseconds,
    )

    private fun ProbeConfigEntity.toDomain(): ProbeConfig = ProbeConfig.restore(
        key = ProbeKey(ProbeId(probeId), ProbeRevision(revision)),
        name = name,
        request = request,
        timeout = timeoutMillis.milliseconds,
    )

    private fun MonitoringProfile.toEntity() = ProfileEntity(
        profileId = id.value,
        name = name,
        presetId = preset.id,
        presetName = preset.name,
        intervalMillis = schedule.interval.inWholeMilliseconds,
        maxConcurrentProbes = schedule.maxConcurrentProbes,
        consecutiveFailures = thresholds.consecutiveFailures,
        consecutiveSuccesses = thresholds.consecutiveSuccesses,
        notificationsEnabled = notifications.enabled,
        repeatIntervalMillis = notifications.repeatInterval?.inWholeMilliseconds,
        savedAt = clock.millis(),
    )
}

class RoomResultRepository(
    private val database: LinkSondaDatabase,
) : ResultRepository {
    private val resultDao = database.probeResultDao()
    private val incidentDao = database.incidentDao()
    private val networkDao = database.networkSnapshotDao()

    override fun observeResults(context: ProbeContext): Flow<List<ProbeResult>> =
        resultDao.observe(
            probeId = context.probe.id.value,
            revision = context.probe.revision.value,
            networkId = context.networkId?.value,
        ).map { rows -> rows.map { it.result } }

    override fun observeIncidents(profileId: ProfileId, networkId: NetworkId): Flow<List<Incident>> =
        incidentDao.observe(profileId.value, networkId.value).map { rows -> rows.map { it.incident } }

    override suspend fun saveNetwork(snapshot: NetworkSnapshot) {
        networkDao.upsert(
            NetworkSnapshotEntity(
                networkId = snapshot.id.value,
                capturedAt = snapshot.capturedAt.toEpochMilli(),
                transports = snapshot.transports.joinToString(",") { it.name },
                ipFamilies = snapshot.ipFamilies.joinToString(",") { it.name },
                validation = snapshot.validation.name,
            ),
        )
    }

    override suspend fun saveResult(result: ProbeResult) {
        resultDao.insert(
            ProbeResultEntity(
                probeId = result.context.probe.id.value,
                revision = result.context.probe.revision.value,
                networkId = result.context.networkId?.value,
                recordedAt = result.recordedAt.toEpochMilli(),
                result = result,
            ),
        )
    }

    override suspend fun saveIncident(incident: Incident) {
        incidentDao.upsert(
            IncidentEntity(
                id = incident.id,
                profileId = incident.profileId.value,
                networkId = incident.networkId.value,
                openedAt = incident.openedAt.toEpochMilli(),
                recoveredAt = incident.recoveredAt?.toEpochMilli(),
                incident = incident,
            ),
        )
    }

}

