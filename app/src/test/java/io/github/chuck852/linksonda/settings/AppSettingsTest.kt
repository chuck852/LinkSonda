package io.github.chuck852.linksonda.settings

import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun defaultsMatchTheDocumentedProductDefaults() {
        val settings = AppSettings.Default

        assertEquals(AppLanguage.SYSTEM, settings.language)
        assertEquals(ThemeMode.ADAPTIVE, settings.themeMode)
        assertEquals(ThemeBrightness.SYSTEM, settings.themeBrightness)
        assertEquals(false, settings.monitoringEnabled)
        assertEquals(false, settings.notificationsEnabled)
        assertEquals(false, settings.notificationsPermissionRequested)
        assertEquals(30, settings.historyRetentionDays)
        assertNull(settings.selectedProfileId)
        assertEquals(false, settings.onboardingCompleted)
    }

    @Test
    fun retentionIsLimitedToSupportedChoices() {
        val accepted = AppSettings.HISTORY_RETENTION_DAYS

        assertEquals(listOf(7, 30, 90, 365), accepted)
        accepted.forEach { days ->
            assertEquals(days, AppSettings(historyRetentionDays = days).historyRetentionDays)
        }
        val rejected = listOf(0, 1, 31, 3650)
        rejected.forEach { days ->
            val failure = runCatching { AppSettings(historyRetentionDays = days) }.exceptionOrNull()
            assertTrue("days=$days", failure is IllegalArgumentException)
        }
    }

    @Test
    fun languageTagsAreExplicit() {
        assertNull(AppLanguage.SYSTEM.languageTag)
        assertEquals("ru", AppLanguage.RUSSIAN.languageTag)
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
    }

    @Test
    fun firstRunProfileRejectsBlankFields() {
        val failure = runCatching {
            FirstRunProfile(id = " ", name = "Profile", description = "Body")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
