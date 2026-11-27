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

    private companion object {
        const val DATABASE_NAME = "linksonda.db"
    }
}
