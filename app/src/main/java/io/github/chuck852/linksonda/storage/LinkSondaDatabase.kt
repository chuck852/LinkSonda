package io.github.chuck852.linksonda.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ProbeConfigEntity::class,
        ProfileEntity::class,
        ProfileProbeEntity::class,
        ProbeGroupEntity::class,
        ProbeGroupMemberEntity::class,
        NetworkSnapshotEntity::class,
        ProbeResultEntity::class,
        IncidentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(StorageConverters::class)
abstract class LinkSondaDatabase : RoomDatabase() {
    abstract fun probeConfigDao(): ProbeConfigDao
    abstract fun profileDao(): ProfileDao
    abstract fun networkSnapshotDao(): NetworkSnapshotDao
    abstract fun probeResultDao(): ProbeResultDao
    abstract fun incidentDao(): IncidentDao
}
