package io.github.chuck852.linksonda.storage

import androidx.room.withTransaction
import io.github.chuck852.linksonda.settings.AppSettings
import java.time.Clock
import java.time.temporal.ChronoUnit

data class PruneSummary(
    val results: Int,
    val incidents: Int,
    val networks: Int,
)

/** Deletes old observations without touching incidents that are still open. */
class HistoryMaintenance(
    private val database: LinkSondaDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun prune(retentionDays: Int = AppSettings.DEFAULT_HISTORY_RETENTION_DAYS): PruneSummary {
        require(retentionDays > 0)
        val cutoff = clock.instant().minus(retentionDays.toLong(), ChronoUnit.DAYS).toEpochMilli()
        return database.withTransaction {
            PruneSummary(
                results = database.probeResultDao().deleteOlderThan(cutoff),
                incidents = database.incidentDao().deleteRecoveredOlderThan(cutoff),
                networks = database.networkSnapshotDao().deleteUnreferencedBefore(cutoff),
            )
        }
    }

    /** Clears finished history while preserving open incidents and the evidence attached to them. */
    suspend fun clearHistory(): PruneSummary = database.withTransaction {
        PruneSummary(
            results = database.probeResultDao().deleteAll(),
            incidents = database.incidentDao().deleteRecovered(),
            networks = database.networkSnapshotDao().deleteAllUnreferenced(),
        )
    }
}
