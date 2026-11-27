package io.github.chuck852.linksonda

import android.app.Application
import io.github.chuck852.linksonda.settings.PersistentSettingsStore
import io.github.chuck852.linksonda.settings.SettingsStore
import io.github.chuck852.linksonda.settings.SharedPreferencesSettingsStorage

/**
 * Owns the process-wide settings store so every Activity reads and writes the same state.
 * #13 can replace the storage adapter with an app-scoped DataStore behind this contract.
 */
class LinkSondaApplication : Application() {
    val settingsStore: SettingsStore by lazy {
        PersistentSettingsStore(SharedPreferencesSettingsStorage(this))
    }
}
