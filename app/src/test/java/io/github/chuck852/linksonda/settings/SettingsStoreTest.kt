package io.github.chuck852.linksonda.settings

import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            updateChecksEnabled = true,
            updateChannel = UpdateChannel.PREVIEW,
            lastUpdateCheckAt = java.time.Instant.parse("2026-11-27T12:00:00Z"),
            updateEtag = "etag-1",
            lastOfferedVersionCode = 42,
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
                SettingsCodec.KEY_UPDATE_CHANNEL to "NIGHTLY",
                SettingsCodec.KEY_LAST_UPDATE_CHECK_AT to "not-a-time",
                SettingsCodec.KEY_LAST_OFFERED_VERSION_CODE to "-1",
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
        assertEquals(updated, store.update { updated }?.current)

        assertEquals(updated, store.settings.value)
        assertEquals(updated, SettingsCodec.decode(storage.values))
        assertEquals(updated, PersistentSettingsStore(storage).current())
    }

    @Test
    fun updateAppliesTransformToCurrentStateAndPersistsResult() = runBlocking {
        val storage = FakeStorage(emptyMap())
        val store = PersistentSettingsStore(storage)

        val update = requireNotNull(
            store.update { current ->
                current.copy(themeMode = ThemeMode.STATIC, historyRetentionDays = 90)
            },
        )

        assertEquals(AppSettings.Default, update.previous)
        assertEquals(ThemeMode.STATIC, update.current.themeMode)
        assertEquals(90, update.current.historyRetentionDays)
        assertEquals(update.current, store.current())
        assertEquals(update.current, SettingsCodec.decode(storage.values))
    }

    @Test
    fun savingAnEmptyProfileIdPersistsNoProfile() = runBlocking {
        val storage = FakeStorage(emptyMap())
        val store = PersistentSettingsStore(storage)

        assertEquals(
            true,
            store.update {
                AppSettings.Default.copy(
                    onboardingCompleted = true,
                    selectedProfileId = null,
                )
            } != null,
        )

        assertNull(SettingsCodec.decode(storage.values).selectedProfileId)
        assertEquals(true, SettingsCodec.decode(storage.values).onboardingCompleted)
    }

    @Test
    fun failedWriteDoesNotAdvanceInMemoryState() = runBlocking {
        val storage = FakeStorage(emptyMap(), writeResult = false)
        val store = PersistentSettingsStore(storage)

        assertNull(store.update { it.copy(onboardingCompleted = true) })
        assertEquals(AppSettings.Default, store.current())
        assertEquals(emptyMap<String, String?>(), storage.values)
    }

    @Test
    fun concurrentUpdatesToDifferentSettingsBothPersist() = runBlocking {
        val storage = GatedStorage()
        val store = PersistentSettingsStore(storage)

        val first = launch(Dispatchers.Default) {
            checkNotNull(store.update { it.copy(themeMode = ThemeMode.STATIC) })
        }
        storage.awaitFirstWrite()

        // Starts while the first commit is blocked: it must transform the state it will actually replace.
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            checkNotNull(store.update { it.copy(themeBrightness = ThemeBrightness.DARK) })
        }

        storage.releaseFirstWrite()
        first.join()
        second.join()

        assertEquals(ThemeMode.STATIC, store.current().themeMode)
        assertEquals(ThemeBrightness.DARK, store.current().themeBrightness)
        assertEquals(store.current(), SettingsCodec.decode(storage.values))
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

    private class GatedStorage : SettingsStorage {
        private val firstWriteStarted = CountDownLatch(1)
        private val firstWriteRelease = CountDownLatch(1)
        private var writeCount = 0

        var values: Map<String, String?> = emptyMap()
            private set

        override fun read(): Map<String, String?> = values

        override fun write(values: Map<String, String>): Boolean {
            if (writeCount++ == 0) {
                firstWriteStarted.countDown()
                check(firstWriteRelease.await(5, TimeUnit.SECONDS)) { "first write was not released" }
            }
            this.values = values
            return true
        }

        fun awaitFirstWrite() {
            check(firstWriteStarted.await(5, TimeUnit.SECONDS)) { "first write did not start" }
        }

        fun releaseFirstWrite() = firstWriteRelease.countDown()
    }
}
