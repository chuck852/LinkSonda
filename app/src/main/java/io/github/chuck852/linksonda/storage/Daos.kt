package io.github.chuck852.linksonda.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProbeConfigDao {
    @Query("SELECT * FROM probe_configs WHERE probe_id = :probeId ORDER BY revision DESC LIMIT 1")
    suspend fun latest(probeId: String): ProbeConfigEntity?

    @Query("SELECT * FROM probe_configs WHERE probe_id = :probeId AND revision = :revision")
    suspend fun find(probeId: String, revision: Long): ProbeConfigEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(config: ProbeConfigEntity)

    @Query("UPDATE probe_configs SET name = :name WHERE probe_id = :probeId AND revision = :revision")
    suspend fun updateName(probeId: String, revision: Long, name: String)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE, profile_id")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE, profile_id")
    suspend fun getProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE profile_id = :profileId")
    suspend fun find(profileId: String): ProfileEntity?

    @Query("SELECT * FROM profile_probes WHERE profile_id = :profileId ORDER BY position")
    suspend fun probes(profileId: String): List<ProfileProbeEntity>

    @Query("SELECT * FROM probe_groups WHERE profile_id = :profileId ORDER BY position")
    suspend fun groups(profileId: String): List<ProbeGroupEntity>

    @Query("SELECT * FROM probe_group_members WHERE profile_id = :profileId AND group_name = :groupName")
    suspend fun groupMembers(profileId: String, groupName: String): List<ProbeGroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProbes(probes: List<ProfileProbeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<ProbeGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroupMembers(members: List<ProbeGroupMemberEntity>)

    @Query("DELETE FROM probe_group_members WHERE profile_id = :profileId")
    suspend fun deleteGroupMembers(profileId: String)

    @Query("DELETE FROM probe_groups WHERE profile_id = :profileId")
    suspend fun deleteGroups(profileId: String)

    @Query("DELETE FROM profile_probes WHERE profile_id = :profileId")
    suspend fun deleteProbes(profileId: String)

    @Query("DELETE FROM profiles WHERE profile_id = :profileId")
    suspend fun deleteProfile(profileId: String)
}

@Dao
interface NetworkSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: NetworkSnapshotEntity)

    @Query("SELECT COUNT(*) FROM network_snapshots")
    suspend fun count(): Int

    @Query(
        "DELETE FROM network_snapshots WHERE captured_at < :cutoff " +
            "AND network_id NOT IN (SELECT network_id FROM probe_results WHERE network_id IS NOT NULL) " +
            "AND network_id NOT IN (SELECT network_id FROM incidents)",
    )
    suspend fun deleteUnreferencedBefore(cutoff: Long): Int

    @Query(
        "DELETE FROM network_snapshots " +
            "WHERE network_id NOT IN (SELECT network_id FROM probe_results WHERE network_id IS NOT NULL) " +
            "AND network_id NOT IN (SELECT network_id FROM incidents)",
    )
    suspend fun deleteAllUnreferenced(): Int
}

@Dao
interface ProbeResultDao {
    @Query(
        "SELECT * FROM probe_results WHERE probe_id = :probeId AND revision = :revision " +
            "AND network_id IS :networkId ORDER BY recorded_at DESC",
    )
    fun observe(
        probeId: String,
        revision: Long,
        networkId: String?,
    ): Flow<List<ProbeResultEntity>>

    @Insert
    suspend fun insert(result: ProbeResultEntity): Long

    @Query("DELETE FROM probe_results WHERE recorded_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM probe_results")
    suspend fun deleteAll(): Int
}

@Dao
interface IncidentDao {
    @Query(
        "SELECT * FROM incidents WHERE profile_id = :profileId AND network_id = :networkId " +
            "ORDER BY opened_at DESC",
    )
    fun observe(profileId: String, networkId: String): Flow<List<IncidentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(incident: IncidentEntity)

    @Query("DELETE FROM incidents WHERE recovered_at IS NOT NULL AND recovered_at < :cutoff")
    suspend fun deleteRecoveredOlderThan(cutoff: Long): Int

    @Query("DELETE FROM incidents WHERE recovered_at IS NOT NULL")
    suspend fun deleteRecovered(): Int
}
