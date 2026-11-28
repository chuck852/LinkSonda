package io.github.chuck852.linksonda.ui.profiles

import io.github.chuck852.linksonda.domain.AlertThresholds
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.MonitoringSchedule
import io.github.chuck852.linksonda.domain.NotificationSettings
import io.github.chuck852.linksonda.domain.Preset
import io.github.chuck852.linksonda.domain.ProfileId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditorStateTest {
    private val profile = MonitoringProfile(
        id = ProfileId("home"),
        name = "Home",
        preset = Preset("home:preset", "Custom", emptyList()),
        groups = emptyList(),
        schedule = MonitoringSchedule(300.seconds, 2),
        thresholds = AlertThresholds(3, 2),
        notifications = NotificationSettings(true, 15.minutes),
    )

    @Test
    fun fromProfileRoundTripsSettings() {
        val state = ProfileEditorState.from(profile)
        assertEquals(300, state.intervalSeconds)
        assertEquals("2", state.maxConcurrentProbes)
        assertEquals("15", state.repeatMinutes)
        assertEquals(profile, state.validate(profile).profile)
    }

    @Test
    fun validChangesKeepChecksAndGroups() {
        val state = ProfileEditorState.from(profile).copy(
            name = " Work ",
            intervalSeconds = 60,
            maxConcurrentProbes = "4",
            consecutiveFailures = "5",
            consecutiveSuccesses = "1",
            notificationsEnabled = false,
        )

        val updated = requireNotNull(state.validate(profile).profile)
        assertEquals("Work", updated.name)
        assertEquals(60.seconds, updated.schedule.interval)
        assertEquals(4, updated.schedule.maxConcurrentProbes)
        assertEquals(AlertThresholds(5, 1), updated.thresholds)
        assertEquals(NotificationSettings(false), updated.notifications)
        assertEquals(profile.preset, updated.preset)
    }

    @Test
    fun tenSecondIntervalIsBlockedUntilCatalogIsVerified() {
        val result = ProfileEditorState.from(profile).copy(intervalSeconds = 10).validate(profile)
        assertNull(result.profile)
        assertEquals(ProfileSettingsError.INVALID_INTERVAL, result.errors[ProfileSettingsField.INTERVAL])
    }

    @Test
    fun invalidFieldsAreReported() {
        val state = ProfileEditorState.from(profile).copy(
            name = " ",
            maxConcurrentProbes = "5",
            consecutiveFailures = "0",
            consecutiveSuccesses = "many",
            notificationsEnabled = true,
            repeatMinutes = "0",
        )
        val errors = state.validate(profile).errors

        assertEquals(ProfileSettingsError.REQUIRED, errors[ProfileSettingsField.NAME])
        assertEquals(ProfileSettingsError.INVALID_CONCURRENCY, errors[ProfileSettingsField.MAX_CONCURRENCY])
        assertEquals(ProfileSettingsError.INVALID_THRESHOLD, errors[ProfileSettingsField.FAILURES])
        assertEquals(ProfileSettingsError.INVALID_THRESHOLD, errors[ProfileSettingsField.SUCCESSES])
        assertEquals(ProfileSettingsError.INVALID_REPEAT, errors[ProfileSettingsField.REPEAT])
        assertFalse(state.validate(profile).isValid)
        assertTrue(ProfileEditorState.from(profile).validate(profile).isValid)
    }
}
