package io.github.chuck852.linksonda.settings

import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun codecRoundTripsEveryField() {
        val settings = AppSettings(
            language = AppLanguage.RUSSIAN,
            themeMode = ThemeMode.ACCENTS,
            themeBrightness = ThemeBrightness.DARK,
            monitoringEnabled = true,
            notificationsEnabled = true,
            notificationsPermissionRequested = true,
            historyRetentionDays = 90,
            selectedProfileId = "profile-1",
            onboardingCompleted = true,
        )

        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test
    fun codecFallsBackForUnknownOrCorruptValues() {
        val decoded = SettingsCodec.decode(
            mapOf(
                SettingsCodec.KEY_LANGUAGE to "KLINGON",
                SettingsCodec.KEY_THEME_MODE to "NEON",
                SettingsCodec.KEY_THEME_BRIGHTNESS to "",
                SettingsCodec.KEY_MONITORING_ENABLED to "yes",
                SettingsCodec.KEY_NOTIFICATIONS_ENABLED to "1",
                SettingsCodec.KEY_NOTIFICATIONS_PERMISSION_REQUESTED to "sometimes",
                SettingsCodec.KEY_HISTORY_RETENTION_DAYS to "31",
                SettingsCodec.KEY_ONBOARDING_COMPLETED to "done",
                SettingsCodec.KEY_SELECTED_PROFILE_ID to "  ",
            ),
        )

        assertEquals(AppSettings.Default, decoded)
        assertNull(decoded.selectedProfileId)
    }

    @Test
    fun storeStartsFromPersistedValuesAndSavesChanges() = runBlocking {
        val storage = FakeStorage(
            mapOf(
                SettingsCodec.KEY_LANGUAGE to "ENGLISH",
                SettingsCodec.KEY_THEME_MODE to "STATIC",
                SettingsCodec.KEY_ONBOARDING_COMPLETED to "true",
            ),
        )
        val store = PersistentSettingsStore(storage)

        assertEquals(AppLanguage.ENGLISH, store.current().language)
        assertEquals(ThemeMode.STATIC, store.current().themeMode)
        assertEquals(true, store.current().onboardingCompleted)

        val updated = store.current().copy(
            language = AppLanguage.RUSSIAN,
            notificationsEnabled = true,
            historyRetentionDays = 365,
            selectedProfileId = "profile-2",
        )
        assertEquals(true, store.save(updated))

        assertEquals(updated, store.settings.value)
        assertEquals(updated, SettingsCodec.decode(storage.values))
        assertEquals(updated, PersistentSettingsStore(storage).current())
    }

    @Test
    fun savingAnEmptyProfileIdPersistsNoProfile() = runBlocking {
        val storage = FakeStorage(emptyMap())
        val store = PersistentSettingsStore(storage)

        assertEquals(
            true,
            store.save(
                AppSettings.Default.copy(
                    onboardingCompleted = true,
                    selectedProfileId = null,
                ),
            ),
        )

        assertNull(SettingsCodec.decode(storage.values).selectedProfileId)
        assertEquals(true, SettingsCodec.decode(storage.values).onboardingCompleted)
    }

    @Test
    fun failedWriteDoesNotAdvanceInMemoryState() = runBlocking {
        val storage = FakeStorage(emptyMap(), writeResult = false)
        val store = PersistentSettingsStore(storage)
        val changed = AppSettings.Default.copy(onboardingCompleted = true)

        assertEquals(false, store.save(changed))
        assertEquals(AppSettings.Default, store.current())
        assertEquals(emptyMap<String, String?>(), storage.values)
    }

    private class FakeStorage(
        initial: Map<String, String?>,
        private val writeResult: Boolean = true,
    ) : SettingsStorage {
        var values: Map<String, String?> = initial
            private set

        override fun read(): Map<String, String?> = values

        override fun write(values: Map<String, String>): Boolean {
            if (writeResult) this.values = values
            return writeResult
        }
    }
}
