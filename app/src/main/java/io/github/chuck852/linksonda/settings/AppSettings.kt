package io.github.chuck852.linksonda.settings

import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode
import java.time.Instant

/** Language used for app resources. [SYSTEM] follows the Android locale. */
enum class AppLanguage(val languageTag: String?) {
    SYSTEM(null),
    RUSSIAN("ru"),
    ENGLISH("en"),
}

enum class UpdateChannel { STABLE, PREVIEW }

enum class UpdateCheckStatus {
    NEVER,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    NOT_MODIFIED,
    RATE_LIMITED,
    NETWORK_ERROR,
    INVALID_RELEASE,
    NO_RELEASE,
}

/**
 * User-visible settings that are independent of the monitoring engine and history storage.
 *
 * Runtime permission state is not stored here: [notificationsEnabled] is the user's preference, while
 * Android remains the source of truth for whether notifications may actually be posted.
 */
data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themeMode: ThemeMode = ThemeMode.ADAPTIVE,
    val themeBrightness: ThemeBrightness = ThemeBrightness.SYSTEM,
    val monitoringEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val notificationsPermissionRequested: Boolean = false,
    val historyRetentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
    val selectedProfileId: String? = null,
    val onboardingCompleted: Boolean = false,
    /** Reserved for #57; no updater exists yet. */
    val updateChecksEnabled: Boolean = false,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val lastUpdateCheckAt: Instant? = null,
    val updateEtag: String? = null,
    val lastOfferedVersionCode: Long? = null,
    val lastOfferedVersionName: String? = null,
    val lastOfferedReleaseNotes: String? = null,
    val lastOfferedChannel: UpdateChannel? = null,
    val lastOfferedUpdateJson: String? = null,
    val lastUpdateStatus: UpdateCheckStatus = UpdateCheckStatus.NEVER,
    val lastUpdateRetryAt: Instant? = null,
    val lastNotifiedVersionCode: Long? = null,
) {
    init {
        require(historyRetentionDays in HISTORY_RETENTION_DAYS)
        require(selectedProfileId == null || selectedProfileId.isNotBlank())
        require(updateEtag == null || updateEtag.isNotBlank())
        require(lastOfferedVersionCode == null || lastOfferedVersionCode > 0)
        require(lastOfferedVersionName == null || lastOfferedVersionName.isNotBlank())
        require(lastOfferedReleaseNotes == null || lastOfferedReleaseNotes.isNotBlank())
        require((lastOfferedVersionCode == null) == (lastOfferedVersionName == null))
        require((lastOfferedVersionCode == null) == (lastOfferedChannel == null))
        require(lastOfferedUpdateJson == null || lastOfferedUpdateJson.isNotBlank())
        require(lastNotifiedVersionCode == null || lastNotifiedVersionCode > 0)
    }

    companion object {
        const val DEFAULT_HISTORY_RETENTION_DAYS = 30
        val HISTORY_RETENTION_DAYS = listOf(7, 30, 90, 365)

        val Default = AppSettings()
    }
}

/** A profile that can be selected during first-run setup without inventing placeholder data. */
data class FirstRunProfile(
    val id: String,
    val name: String,
    val description: String,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && description.isNotBlank())
    }
}
