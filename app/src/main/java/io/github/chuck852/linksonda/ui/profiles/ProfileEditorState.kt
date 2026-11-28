package io.github.chuck852.linksonda.ui.profiles

import io.github.chuck852.linksonda.domain.AlertThresholds
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.MonitoringSchedule
import io.github.chuck852.linksonda.domain.NotificationSettings
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class ProfileSettingsField {
    NAME,
    INTERVAL,
    MAX_CONCURRENCY,
    FAILURES,
    SUCCESSES,
    REPEAT,
}

enum class ProfileSettingsError {
    REQUIRED,
    INVALID_INTERVAL,
    INVALID_CONCURRENCY,
    INVALID_THRESHOLD,
    INVALID_REPEAT,
}

data class ProfileEditorValidation(
    val errors: Map<ProfileSettingsField, ProfileSettingsError>,
    val profile: MonitoringProfile?,
) {
    val isValid: Boolean get() = profile != null
}

/**
 * Editable profile settings. Checks and groups are edited through their own actions so a settings
 * save never drops a check added on another screen.
 */
data class ProfileEditorState(
    val profileId: String,
    val name: String,
    val intervalSeconds: Long = 60,
    val maxConcurrentProbes: String = "4",
    val consecutiveFailures: String = "3",
    val consecutiveSuccesses: String = "2",
    val notificationsEnabled: Boolean = false,
    val repeatMinutes: String = "30",
) {
    init {
        require(profileId.isNotBlank())
    }

    fun validate(base: MonitoringProfile): ProfileEditorValidation {
        val errors = linkedMapOf<ProfileSettingsField, ProfileSettingsError>()
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            errors[ProfileSettingsField.NAME] = ProfileSettingsError.REQUIRED
        }
        if (intervalSeconds !in PROFILE_ALLOWED_INTERVALS) {
            errors[ProfileSettingsField.INTERVAL] = ProfileSettingsError.INVALID_INTERVAL
        }
        val maxConcurrent = maxConcurrentProbes.trim().toIntOrNull()
        if (maxConcurrent == null || maxConcurrent !in 1..PROFILE_MAX_CONCURRENCY) {
            errors[ProfileSettingsField.MAX_CONCURRENCY] = ProfileSettingsError.INVALID_CONCURRENCY
        }
        val failures = consecutiveFailures.trim().toIntOrNull()
        if (failures == null || failures !in 1..PROFILE_MAX_THRESHOLD) {
            errors[ProfileSettingsField.FAILURES] = ProfileSettingsError.INVALID_THRESHOLD
        }
        val successes = consecutiveSuccesses.trim().toIntOrNull()
        if (successes == null || successes !in 1..PROFILE_MAX_THRESHOLD) {
            errors[ProfileSettingsField.SUCCESSES] = ProfileSettingsError.INVALID_THRESHOLD
        }
        val repeat = repeatMinutes.trim().toIntOrNull()
        if (notificationsEnabled && (repeat == null || repeat !in 1..PROFILE_MAX_REPEAT_MINUTES)) {
            errors[ProfileSettingsField.REPEAT] = ProfileSettingsError.INVALID_REPEAT
        }

        val profile = if (errors.isEmpty()) {
            base.copy(
                name = trimmedName,
                schedule = MonitoringSchedule(
                    interval = intervalSeconds.seconds,
                    maxConcurrentProbes = requireNotNull(maxConcurrent),
                ),
                thresholds = AlertThresholds(
                    consecutiveFailures = requireNotNull(failures),
                    consecutiveSuccesses = requireNotNull(successes),
                ),
                notifications = NotificationSettings(
                    enabled = notificationsEnabled,
                    repeatInterval = if (notificationsEnabled) {
                        requireNotNull(repeat).minutes
                    } else {
                        null
                    },
                ),
            )
        } else {
            null
        }
        return ProfileEditorValidation(errors = errors, profile = profile)
    }

    companion object {
        fun from(profile: MonitoringProfile): ProfileEditorState = ProfileEditorState(
            profileId = profile.id.value,
            name = profile.name,
            intervalSeconds = profile.schedule.interval.inWholeSeconds,
            maxConcurrentProbes = profile.schedule.maxConcurrentProbes.toString(),
            consecutiveFailures = profile.thresholds.consecutiveFailures.toString(),
            consecutiveSuccesses = profile.thresholds.consecutiveSuccesses.toString(),
            notificationsEnabled = profile.notifications.enabled,
            repeatMinutes = (profile.notifications.repeatInterval?.inWholeMinutes ?: 30).toString(),
        )
    }
}

/** 10 s is visible but unavailable until approved endpoints exist; see docs/PROBE_POLICY.md. */
val PROFILE_INTERVAL_OPTIONS = listOf(10L, 60L, 300L)
val PROFILE_ALLOWED_INTERVALS = setOf(60L, 300L)
const val PROFILE_MAX_CONCURRENCY = 4
const val PROFILE_MAX_THRESHOLD = 10
const val PROFILE_MAX_REPEAT_MINUTES = 1440

val ProfileEditorStateSaver = androidx.compose.runtime.saveable.mapSaver<ProfileEditorState>(
    save = { state ->
        mapOf(
            "profileId" to state.profileId,
            "name" to state.name,
            "intervalSeconds" to state.intervalSeconds,
            "maxConcurrentProbes" to state.maxConcurrentProbes,
            "consecutiveFailures" to state.consecutiveFailures,
            "consecutiveSuccesses" to state.consecutiveSuccesses,
            "notificationsEnabled" to state.notificationsEnabled,
            "repeatMinutes" to state.repeatMinutes,
        )
    },
    restore = { values ->
        ProfileEditorState(
            profileId = values["profileId"] as? String ?: "profile",
            name = values["name"] as? String ?: "",
            intervalSeconds = values["intervalSeconds"] as? Long ?: 60L,
            maxConcurrentProbes = values["maxConcurrentProbes"] as? String ?: "4",
            consecutiveFailures = values["consecutiveFailures"] as? String ?: "3",
            consecutiveSuccesses = values["consecutiveSuccesses"] as? String ?: "2",
            notificationsEnabled = values["notificationsEnabled"] as? Boolean ?: false,
            repeatMinutes = values["repeatMinutes"] as? String ?: "30",
        )
    },
)
