package io.github.chuck852.linksonda.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * DataStore preferences with a one-time synchronous snapshot for the locale in attachBaseContext.
 *
 * Settings are small and the snapshot is read once during application startup. Writes still use the
 * DataStore transaction API on the caller's IO dispatcher.
 */
class DataStoreSettingsStorage(
    private val dataStore: DataStore<Preferences>,
) : SettingsStorage {
    constructor(context: Context) : this(createDataStore(context.applicationContext))

    private var cached: Map<String, String?> = load()

    override fun read(): Map<String, String?> = cached

    override fun write(values: Map<String, String>): Boolean = runBlocking {
        try {
            dataStore.edit { preferences ->
                preferences.clear()
                values.forEach { (key, value) ->
                    preferences[stringPreferencesKey(key)] = value
                }
            }
            cached = values
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun load(): Map<String, String?> = runBlocking {
        try {
            dataStore.data.first().asMap().entries.associate { (key, value) -> key.name to value as? String }
        } catch (_: IOException) {
            emptyMap()
        }
    }

    companion object {
        const val DATASTORE_NAME = "linksonda_settings"

        internal fun createDataStore(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = listOf(
                    SharedPreferencesMigration(
                        context = context,
                        sharedPreferencesName = SharedPreferencesSettingsStorage.PREFERENCES_NAME,
                    ),
                ),
                produceFile = {
                    File(context.filesDir, "datastore/$DATASTORE_NAME.preferences_pb")
                },
            )
    }
}
