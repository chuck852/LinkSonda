package io.github.chuck852.linksonda.settings

import android.content.Context
import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistence boundary for [AppSettings].
 *
 * Public #13 replaces the storage adapter with DataStore without changing this contract or the UI.
 * No Room or second settings repository is introduced here.
 */
interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun current(): AppSettings
    suspend fun save(settings: AppSettings)
}

/** Minimal platform boundary so the settings logic is testable without Android. */
interface SettingsStorage {
    fun read(): Map<String, String?>
    fun write(values: Map<String, String>)
}

class SharedPreferencesSettingsStorage(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : SettingsStorage {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun read(): Map<String, String?> = preferences.all.mapValues { (_, value) ->
        value as? String
    }

    override fun write(values: Map<String, String>) {
        val editor = preferences.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        // Callers run this on Dispatchers.IO; commit keeps a language change durable before recreate().
        editor.commit()
    }

    companion object {
        const val PREFERENCES_NAME = "linksonda_settings"
    }
}

class PersistentSettingsStore(
    private val storage: SettingsStorage,
) : SettingsStore {
    private val state = MutableStateFlow(SettingsCodec.decode(storage.read()))
    private val writeMutex = Mutex()

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun current(): AppSettings = state.value

    override suspend fun save(settings: AppSettings) {
        val encoded = SettingsCodec.encode(settings)
        writeMutex.withLock {
            withContext(Dispatchers.IO) { storage.write(encoded) }
            state.value = settings
        }
    }
}

/** Stable preference keys and tolerant decoding for values written by older or newer builds. */
object SettingsCodec {
    const val KEY_LANGUAGE = "language"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_THEME_BRIGHTNESS = "theme_brightness"
    const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val KEY_HISTORY_RETENTION_DAYS = "history_retention_days"
    const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    fun decode(values: Map<String, String?>): AppSettings {
        val defaults = AppSettings.Default
        val retention = values[KEY_HISTORY_RETENTION_DAYS]
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in AppSettings.HISTORY_RETENTION_DAYS }
            ?: defaults.historyRetentionDays
        return AppSettings(
            language = enumValue(values[KEY_LANGUAGE], defaults.language),
            themeMode = enumValue(values[KEY_THEME_MODE], defaults.themeMode),
            themeBrightness = enumValue(values[KEY_THEME_BRIGHTNESS], defaults.themeBrightness),
            monitoringEnabled = values[KEY_MONITORING_ENABLED].toBooleanSetting(defaults.monitoringEnabled),
            notificationsEnabled = values[KEY_NOTIFICATIONS_ENABLED].toBooleanSetting(defaults.notificationsEnabled),
            historyRetentionDays = retention,
            selectedProfileId = values[KEY_SELECTED_PROFILE_ID]
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            onboardingCompleted = values[KEY_ONBOARDING_COMPLETED]
                .toBooleanSetting(defaults.onboardingCompleted),
        )
    }

    fun encode(settings: AppSettings): Map<String, String> = mapOf(
        KEY_LANGUAGE to settings.language.name,
        KEY_THEME_MODE to settings.themeMode.name,
        KEY_THEME_BRIGHTNESS to settings.themeBrightness.name,
        KEY_MONITORING_ENABLED to settings.monitoringEnabled.toString(),
        KEY_NOTIFICATIONS_ENABLED to settings.notificationsEnabled.toString(),
        KEY_HISTORY_RETENTION_DAYS to settings.historyRetentionDays.toString(),
        KEY_SELECTED_PROFILE_ID to settings.selectedProfileId.orEmpty(),
        KEY_ONBOARDING_COMPLETED to settings.onboardingCompleted.toString(),
    )

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun String?.toBooleanSetting(fallback: Boolean): Boolean = when (this) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}
