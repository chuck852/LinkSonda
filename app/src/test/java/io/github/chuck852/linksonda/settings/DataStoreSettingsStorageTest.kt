package io.github.chuck852.linksonda.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreSettingsStorageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "datastore").deleteRecursively()
        context.getSharedPreferences(SharedPreferencesSettingsStorage.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun migratesLegacySharedPreferencesAndPersistsWrites() {
        context.getSharedPreferences(SharedPreferencesSettingsStorage.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SettingsCodec.KEY_LANGUAGE, "RUSSIAN")
            .putString(SettingsCodec.KEY_HISTORY_RETENTION_DAYS, "90")
            .putString(SettingsCodec.KEY_ONBOARDING_COMPLETED, "true")
            .commit()

        val storage = DataStoreSettingsStorage(context)
        val migrated = SettingsCodec.decode(storage.read())
        assertEquals(AppLanguage.RUSSIAN, migrated.language)
        assertEquals(90, migrated.historyRetentionDays)
        assertTrue(migrated.onboardingCompleted)

        val migratedDataStore = SettingsCodec.decode(runBlocking { storage.readDataStore() })
        assertEquals(migrated, migratedDataStore)

        val updated = migrated.copy(themeMode = io.github.chuck852.linksonda.ui.theme.ThemeMode.STATIC)
        assertTrue(runBlocking { storage.write(SettingsCodec.encode(updated)) })
        assertEquals(updated, SettingsCodec.decode(storage.read()))
        assertEquals(updated, SettingsCodec.decode(runBlocking { storage.readDataStore() }))
    }
}
