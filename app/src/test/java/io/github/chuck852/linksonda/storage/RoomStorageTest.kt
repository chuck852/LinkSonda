package io.github.chuck852.linksonda.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeRevision
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProfileId
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomStorageTest {
    private lateinit var context: Context
    private lateinit var database: LinkSondaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LinkSondaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun profileCrudRoundTripsRelationsAndRevisions() = runBlocking {
        val repository = RoomProfileRepository(database, clock = fixedClock)
        val profile = StorageFixtures.profile()

        repository.saveProfile(profile)
        assertEquals(listOf(profile), repository.observeProfiles().first())

        val renamed = profile.copy(name = "Home updated")
        repository.saveProfile(renamed)
        assertEquals(listOf(renamed), repository.observeProfiles().first())

        val changedTcp = StorageFixtures.tcp.update(
            name = "TCP",
            request = ProbeRequest.TcpConnect(SocketEndpoint("example.net", 8443)),
        )
        repository.saveProfile(
            profile.copy(
                preset = profile.preset.copy(
                    probes = listOf(changedTcp, StorageFixtures.tls, StorageFixtures.dns),
                ),
            ),
        )
        val stored = repository.observeProfiles().first().single()
        assertEquals(ProbeRevision(2), stored.preset.probes.first { it.id == ProbeId("tcp-1") }.revision)
        assertEquals("example.net", (stored.preset.probes.first().request as ProbeRequest.TcpConnect).endpoint.host)

        repository.deleteProfile(ProfileId("profile-1"))
        assertEquals(emptyList<Any>(), repository.observeProfiles().first())
    }

    @Test
    fun staleAndConflictingRevisionsAreRejected() {
        runBlocking {
            val repository = RoomProfileRepository(database, clock = fixedClock)
            val profile = StorageFixtures.profile()
            repository.saveProfile(profile)

            val changedTcp = StorageFixtures.tcp.update(
                request = ProbeRequest.TcpConnect(SocketEndpoint("example.net", 8443)),
            )
            repository.saveProfile(
                profile.copy(
                    preset = profile.preset.copy(
                        probes = listOf(changedTcp, StorageFixtures.tls, StorageFixtures.dns),
                    ),
                ),
            )

            val stale = profile.copy(
                preset = profile.preset.copy(
                    probes = listOf(StorageFixtures.tcp, StorageFixtures.tls, StorageFixtures.dns),
                ),
            )
            assertThrows(StaleProbeRevisionException::class.java) {
                runBlocking { repository.saveProfile(stale) }
            }

            val conflicting = profile.copy(
                preset = profile.preset.copy(
                    probes = listOf(
                        StorageFixtures.tcp.update(
                            request = ProbeRequest.TcpConnect(SocketEndpoint("other.example", 443)),
                        ),
                        StorageFixtures.tls,
                        StorageFixtures.dns,
                    ),
                ),
            )
            assertThrows(ConflictingProbeRevisionException::class.java) {
                runBlocking { repository.saveProfile(conflicting) }
            }
        }
    }

    @Test
    fun resultsAndIncidentsRoundTrip() = runBlocking {
        val repository = RoomResultRepository(database)
        val network = StorageFixtures.network()
        val result = StorageFixtures.completed()
        val incident = StorageFixtures.incident(recoveredAt = Instant.parse("2026-11-01T11:00:00Z"))

        repository.saveNetwork(network)
        repository.saveResult(result)
        repository.saveIncident(incident)

        assertEquals(listOf(result), repository.observeResults(result.context).first())
        assertEquals(
            listOf(incident),
            repository.observeIncidents(StorageFixtures.profileId, StorageFixtures.networkId).first(),
        )

        val skipped = ProbeResult.Skipped(
            context = ProbeContext(StorageFixtures.tcp.key, null),
            recordedAt = Instant.parse("2026-11-01T10:00:02Z"),
            reason = SkipReason.NO_NETWORK,
        )
        repository.saveResult(skipped)
        assertEquals(listOf(skipped), repository.observeResults(skipped.context).first())
    }

    @Test
    fun networkSnapshotIsStoredOncePerId() = runBlocking {
        val repository = RoomResultRepository(database)
        repository.saveNetwork(StorageFixtures.network())
        repository.saveNetwork(StorageFixtures.network(capturedAt = Instant.parse("2026-11-01T10:05:00Z")))

        assertEquals(1, database.networkSnapshotDao().deleteAllUnreferenced())
    }

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun databaseReopensOnDiskWithTheSameRecords() {
        runBlocking {
            val file = context.getDatabasePath("reopen-test.db")
            context.deleteDatabase("reopen-test.db")
            val first = Room.databaseBuilder(context, LinkSondaDatabase::class.java, "reopen-test.db").build()
            first.profileDao().upsert(ProfileEntity("p", "P", "id", "P", 1, 1, 1, 1, false, null, 1))
            first.close()

            val second = Room.databaseBuilder(context, LinkSondaDatabase::class.java, "reopen-test.db").build()
            assertNotNull(second.profileDao().find("p"))
            second.close()
            context.deleteDatabase(file.name)
        }
    }
}
