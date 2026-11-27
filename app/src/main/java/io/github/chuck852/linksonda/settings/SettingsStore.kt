package io.github.chuck852.linksonda.settings

import android.content.Context
import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistence boundary for [AppSettings].
 *
 * [PersistentSettingsStore] keeps the UI contract and preference keys independent of the adapter.
 * The production adapter is DataStore; the synchronous startup snapshot exists only for the locale.
 */
interface SettingsStore {
    val settings: StateFlow<AppSettings>
    fun current(): AppSettings
    /**
     * Replaces the synchronous startup snapshot with the authoritative stored state.
     * Must finish before any write, otherwise a stale snapshot could overwrite DataStore.
     */
    suspend fun initialize()
    /** Applies [transform] to the current state under the write lock; null means the write failed. */
    suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdate?
}

/** Actual states around a successful update, used to react only to real changes. */
data class SettingsUpdate(
    val previous: AppSettings,
    val current: AppSettings,
)

/** Minimal platform boundary so the settings logic is testable without Android. */
interface SettingsStorage {
    /** Fast startup snapshot, used before [readAuthoritative] completes. */
    fun read(): Map<String, String?>
    /** Authoritative stored values. The default keeps simple test and legacy adapters working. */
    suspend fun readAuthoritative(): Map<String, String?> = read()
    /** Replaces the stored values; keys absent from [values] are removed. */
    suspend fun write(values: Map<String, String>): Boolean
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

    override suspend fun write(values: Map<String, String>): Boolean {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) -> editor.putString(key, value) }
        // Callers use Dispatchers.IO; commit keeps a language change durable before recreate().
        return editor.commit()
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
    private val initializeMutex = Mutex()
    private var initialized = false

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun current(): AppSettings = state.value

    override suspend fun initialize() {
        initializeMutex.withLock {
            if (initialized) return@withLock
            val authoritative = withContext(Dispatchers.IO) { storage.readAuthoritative() }
            state.value = SettingsCodec.decode(authoritative)
            initialized = true
        }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdate? =
        writeMutex.withLock {
            // Writes wait for the authoritative load so a stale startup snapshot cannot win.
            initialize()
            currentCoroutineContext().ensureActive()
            val previous = state.value
            val updated = transform(previous)
            if (updated == previous) {
                return@withLock SettingsUpdate(previous, updated)
            }
            // Once the write starts, finish the state transition even if the caller is cancelled.
            // Otherwise DataStore could contain the new value while the UI still shows the old one.
            withContext(NonCancellable + Dispatchers.IO) {
                val stored = storage.write(SettingsCodec.encode(updated))
                if (stored) {
                    state.value = updated
                    SettingsUpdate(previous, updated)
                } else {
                    null
                }
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
    const val KEY_NOTIFICATIONS_PERMISSION_REQUESTED = "notifications_permission_requested"
    const val KEY_HISTORY_RETENTION_DAYS = "history_retention_days"
    const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    const val KEY_UPDATE_CHECKS_ENABLED = "update_checks_enabled"
    const val KEY_UPDATE_CHANNEL = "update_channel"
    const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
    const val KEY_UPDATE_ETAG = "update_etag"
    const val KEY_LAST_OFFERED_VERSION_CODE = "last_offered_version_code"

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
            notificationsPermissionRequested = values[KEY_NOTIFICATIONS_PERMISSION_REQUESTED]
                .toBooleanSetting(defaults.notificationsPermissionRequested),
            historyRetentionDays = retention,
            selectedProfileId = values[KEY_SELECTED_PROFILE_ID]
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            onboardingCompleted = values[KEY_ONBOARDING_COMPLETED]
                .toBooleanSetting(defaults.onboardingCompleted),
            updateChecksEnabled = values[KEY_UPDATE_CHECKS_ENABLED]
                .toBooleanSetting(defaults.updateChecksEnabled),
            updateChannel = enumValue(values[KEY_UPDATE_CHANNEL], defaults.updateChannel),
            lastUpdateCheckAt = values[KEY_LAST_UPDATE_CHECK_AT]
                ?.trim()
                ?.toLongOrNull()
                ?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() },
            updateEtag = values[KEY_UPDATE_ETAG]
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            lastOfferedVersionCode = values[KEY_LAST_OFFERED_VERSION_CODE]
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 },
        )
    }

    fun encode(settings: AppSettings): Map<String, String> = mapOf(
        KEY_LANGUAGE to settings.language.name,
        KEY_THEME_MODE to settings.themeMode.name,
        KEY_THEME_BRIGHTNESS to settings.themeBrightness.name,
        KEY_MONITORING_ENABLED to settings.monitoringEnabled.toString(),
        KEY_NOTIFICATIONS_ENABLED to settings.notificationsEnabled.toString(),
        KEY_NOTIFICATIONS_PERMISSION_REQUESTED to settings.notificationsPermissionRequested.toString(),
        KEY_HISTORY_RETENTION_DAYS to settings.historyRetentionDays.toString(),
        KEY_SELECTED_PROFILE_ID to settings.selectedProfileId.orEmpty(),
        KEY_ONBOARDING_COMPLETED to settings.onboardingCompleted.toString(),
        KEY_UPDATE_CHECKS_ENABLED to settings.updateChecksEnabled.toString(),
        KEY_UPDATE_CHANNEL to settings.updateChannel.name,
        KEY_LAST_UPDATE_CHECK_AT to settings.lastUpdateCheckAt?.toEpochMilli()?.toString().orEmpty(),
        KEY_UPDATE_ETAG to settings.updateEtag.orEmpty(),
        KEY_LAST_OFFERED_VERSION_CODE to settings.lastOfferedVersionCode?.toString().orEmpty(),
    )

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun String?.toBooleanSetting(fallback: Boolean): Boolean = when (this) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}
