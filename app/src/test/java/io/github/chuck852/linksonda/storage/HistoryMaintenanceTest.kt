package io.github.chuck852.linksonda.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.chuck852.linksonda.domain.Incident
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkSnapshot
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HistoryMaintenanceTest {
    private lateinit var database: LinkSondaDatabase
    private lateinit var repository: RoomResultRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LinkSondaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomResultRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pruneRemovesOldResultsAndRecoveredIncidentsButKeepsOpenIncidents() = runBlocking {
        val oldNetwork = NetworkId("old")
        val newNetwork = NetworkId("new")
        val openNetwork = NetworkId("open")
        val oldResult = StorageFixtures.completed(
            recordedAt = Instant.parse("2026-10-31T23:59:59Z"),
        ).copy(context = ProbeContext(StorageFixtures.tcp.key, oldNetwork))
        val newResult = StorageFixtures.completed(
            recordedAt = Instant.parse("2026-11-02T00:00:00Z"),
        ).copy(context = ProbeContext(StorageFixtures.tcp.key, newNetwork))
        val oldIncident = StorageFixtures.incident(
            id = "old-recovered",
            openedAt = Instant.parse("2026-09-01T00:00:00Z"),
            recoveredAt = Instant.parse("2026-10-01T00:00:00Z"),
            network = oldNetwork,
        )
        val openIncident = StorageFixtures.incident(
            id = "still-open",
            openedAt = Instant.parse("2026-09-01T00:00:00Z"),
            network = openNetwork,
        )

        listOf(oldNetwork, newNetwork, openNetwork).forEach { network ->
            repository.saveNetwork(
                NetworkSnapshot(
                    id = network,
                    capturedAt = Instant.parse("2026-09-01T00:00:00Z"),
                    transports = setOf(NetworkTransport.WIFI),
                    ipFamilies = setOf(io.github.chuck852.linksonda.domain.IpFamily.IPV4),
                    validation = io.github.chuck852.linksonda.domain.NetworkValidation.VALIDATED,
                ),
            )
        }
        repository.saveResult(oldResult)
        repository.saveResult(newResult)
        repository.saveIncident(oldIncident)
        repository.saveIncident(openIncident)

        val summary = HistoryMaintenance(database, Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC))
            .prune(retentionDays = 30)

        assertEquals(1, summary.results)
        assertEquals(1, summary.incidents)
        assertEquals(listOf(newResult), repository.observeResults(newResult.context).first())
        assertEquals(emptyList<ProbeResult>(), repository.observeResults(oldResult.context).first())
        assertEquals(listOf(openIncident), repository.observeIncidents(StorageFixtures.profileId, openNetwork).first())
        assertEquals(emptyList<Incident>(), repository.observeIncidents(StorageFixtures.profileId, oldNetwork).first())
        assertEquals(2, database.networkSnapshotDao().count())
    }

    @Test
    fun clearHistoryPreservesOpenIncidentsAndTheirNetwork() = runBlocking {
        val openNetwork = NetworkId("open")
        val network = StorageFixtures.network()
        val openIncident = StorageFixtures.incident(id = "still-open", network = openNetwork)
        repository.saveNetwork(network)
        repository.saveNetwork(
            NetworkSnapshot(
                id = openNetwork,
                capturedAt = Instant.parse("2026-11-01T10:00:00Z"),
                transports = setOf(NetworkTransport.WIFI),
                ipFamilies = setOf(io.github.chuck852.linksonda.domain.IpFamily.IPV4),
                validation = io.github.chuck852.linksonda.domain.NetworkValidation.VALIDATED,
            ),
        )
        repository.saveResult(StorageFixtures.completed())
        repository.saveIncident(openIncident)

        val summary = HistoryMaintenance(database).clearHistory()

        assertEquals(1, summary.results)
        assertEquals(0, summary.incidents)
        assertEquals(emptyList<ProbeResult>(), repository.observeResults(StorageFixtures.completed().context).first())
        assertEquals(listOf(openIncident), repository.observeIncidents(StorageFixtures.profileId, openNetwork).first())
        assertEquals(1, database.networkSnapshotDao().count())
    }
}
