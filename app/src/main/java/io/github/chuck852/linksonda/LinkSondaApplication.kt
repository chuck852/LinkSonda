package io.github.chuck852.linksonda

import android.app.Application
import androidx.room.Room
import io.github.chuck852.linksonda.settings.DataStoreSettingsStorage
import io.github.chuck852.linksonda.settings.PersistentSettingsStore
import io.github.chuck852.linksonda.settings.SettingsStore
import io.github.chuck852.linksonda.storage.HistoryMaintenance
import io.github.chuck852.linksonda.storage.LinkSondaDatabase
import io.github.chuck852.linksonda.storage.RoomProfileRepository
import io.github.chuck852.linksonda.storage.RoomResultRepository
import io.github.chuck852.linksonda.updater.GITHUB_RELEASES_URL
import io.github.chuck852.linksonda.updater.Updater

/** Owns the process-wide stores so every Activity reads and writes the same state. */
class LinkSondaApplication : Application() {
    val settingsStore: SettingsStore by lazy {
        PersistentSettingsStore(DataStoreSettingsStorage(this))
    }

    val database: LinkSondaDatabase by lazy {
        Room.databaseBuilder(this, LinkSondaDatabase::class.java, DATABASE_NAME).build()
    }

    val profileRepository by lazy { RoomProfileRepository(database) }
    val resultRepository by lazy { RoomResultRepository(database) }
    val historyMaintenance by lazy { HistoryMaintenance(database) }
    internal val updater by lazy {
        Updater(
            context = this,
            settingsStore = settingsStore,
            apiUrl = BuildConfig.UPDATER_TEST_URL.ifBlank { GITHUB_RELEASES_URL },
            testSource = BuildConfig.UPDATER_TEST_SOURCE,
        )
    }

    private companion object {
        const val DATABASE_NAME = "linksonda.db"
    }
}
