package io.github.chuck852.linksonda.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.chuck852.linksonda.domain.Incident
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult

@Entity(
    tableName = "probe_configs",
    primaryKeys = ["probe_id", "revision"],
)
data class ProbeConfigEntity(
    @ColumnInfo(name = "probe_id") val probeId: String,
    val revision: Long,
    val name: String,
    val request: ProbeRequest,
    @ColumnInfo(name = "timeout_millis") val timeoutMillis: Long,
)

@Entity(
    tableName = "profiles",
    indices = [Index(value = ["saved_at"])],
)
data class ProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "profile_id") val profileId: String,
    val name: String,
    @ColumnInfo(name = "preset_id") val presetId: String,
    @ColumnInfo(name = "preset_name") val presetName: String,
    @ColumnInfo(name = "interval_millis") val intervalMillis: Long,
    @ColumnInfo(name = "max_concurrent_probes") val maxConcurrentProbes: Int,
    @ColumnInfo(name = "consecutive_failures") val consecutiveFailures: Int,
    @ColumnInfo(name = "consecutive_successes") val consecutiveSuccesses: Int,
    @ColumnInfo(name = "notifications_enabled") val notificationsEnabled: Boolean,
    @ColumnInfo(name = "repeat_interval_millis") val repeatIntervalMillis: Long?,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

@Entity(
    tableName = "profile_probes",
    primaryKeys = ["profile_id", "probe_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProbeConfigEntity::class,
            parentColumns = ["probe_id", "revision"],
            childColumns = ["probe_id", "revision"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profile_id"]), Index(value = ["probe_id", "revision"])],
)
data class ProfileProbeEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "probe_id") val probeId: String,
    val revision: Long,
    val position: Int,
)

@Entity(
    tableName = "probe_groups",
    primaryKeys = ["profile_id", "name"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profile_id"])],
)
data class ProbeGroupEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "probe_group_members",
    primaryKeys = ["profile_id", "group_name", "probe_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProbeGroupEntity::class,
            parentColumns = ["profile_id", "name"],
            childColumns = ["profile_id", "group_name"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileProbeEntity::class,
            parentColumns = ["profile_id", "probe_id"],
            childColumns = ["profile_id", "probe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profile_id", "group_name"]), Index(value = ["profile_id", "probe_id"])],
)
data class ProbeGroupMemberEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "probe_id") val probeId: String,
)

@Entity(
    tableName = "network_snapshots",
    indices = [Index(value = ["captured_at"])],
)
data class NetworkSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "network_id") val networkId: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    val transports: String,
    @ColumnInfo(name = "ip_families") val ipFamilies: String,
    val validation: String,
)

@Entity(
    tableName = "probe_results",
    indices = [
        Index(value = ["probe_id", "revision", "network_id", "recorded_at"]),
        Index(value = ["recorded_at"]),
    ],
)
data class ProbeResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "probe_id") val probeId: String,
    val revision: Long,
    @ColumnInfo(name = "network_id") val networkId: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    val result: ProbeResult,
)

@Entity(
    tableName = "incidents",
    indices = [
        Index(value = ["profile_id", "network_id", "opened_at"]),
        Index(value = ["recovered_at"]),
    ],
)
data class IncidentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "network_id") val networkId: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "recovered_at") val recoveredAt: Long?,
    val incident: Incident,
)
