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

/**
 * DataStore is authoritative for settings.
 *
 * attachBaseContext needs the language before the first frame, so a small SharedPreferences snapshot
 * mirrors the last successfully written values. The snapshot is read synchronously and never touches
 * DataStore. It is replaced after each successful DataStore write, so it cannot become the source of
 * a newer value. On upgrade the old SharedPreferences file serves as both migration input and snapshot.
 */
class DataStoreSettingsStorage(
    private val dataStore: DataStore<Preferences>,
    private val startupSnapshot: SettingsStorage,
) : SettingsStorage {
    constructor(context: Context) : this(
        createDataStore(context.applicationContext),
        SharedPreferencesSettingsStorage(context),
    )

    private var cached: Map<String, String?> = startupSnapshot.read()

    override fun read(): Map<String, String?> = cached

    override suspend fun write(values: Map<String, String>): Boolean {
        try {
            dataStore.edit { preferences ->
                preferences.clear()
                values.forEach { (key, value) ->
                    preferences[stringPreferencesKey(key)] = value
                }
            }
        } catch (_: IOException) {
            return false
        }
        // A cache failure must not turn a committed DataStore write into a UI save failure.
        startupSnapshot.write(values)
        cached = values
        return true
    }

    /** Reads the authoritative store; used by tests and one-time diagnostics, never by startup. */
    internal suspend fun readDataStore(): Map<String, String?> =
        dataStore.data.first().asMap().entries.associate { (key, value) -> key.name to value as? String }

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
