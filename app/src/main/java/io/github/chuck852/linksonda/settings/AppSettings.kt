package io.github.chuck852.linksonda.settings

import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode

/** Language used for app resources. [SYSTEM] follows the Android locale. */
enum class AppLanguage(val languageTag: String?) {
    SYSTEM(null),
    RUSSIAN("ru"),
    ENGLISH("en"),
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
    val historyRetentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
    val selectedProfileId: String? = null,
    val onboardingCompleted: Boolean = false,
) {
    init {
        require(historyRetentionDays in HISTORY_RETENTION_DAYS)
        require(selectedProfileId == null || selectedProfileId.isNotBlank())
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
