package io.github.chuck852.linksonda.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.ui.theme.ConfirmedAggregateStatus
import io.github.chuck852.linksonda.ui.theme.LinkSondaTheme
import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode
import io.github.chuck852.linksonda.ui.theme.ThemeShowcase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only harness for Compose preview and emulator screenshots. It is not packaged in release
 * builds. Launch it with adb and optional extras:
 *
 * `adb shell am start -n io.github.chuck852.linksonda/.debug.ThemeShowcaseActivity`
 * `--es status UNAVAILABLE --es mode ADAPTIVE --es brightness DARK`
 * `--es initialStatus AVAILABLE --el delayMs 2000`
 */
class ThemeShowcaseActivity : ComponentActivity() {
    private val status = mutableStateOf(AggregateStatus.UNKNOWN)
    private val mode = mutableStateOf(ThemeMode.ADAPTIVE)
    private val brightness = mutableStateOf(ThemeBrightness.SYSTEM)
    private var pendingStatusChange: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntent(intent)
        enableEdgeToEdge()
        setContent {
            val dark = when (brightness.value) {
                ThemeBrightness.SYSTEM -> isSystemInDarkTheme()
                ThemeBrightness.LIGHT -> false
                ThemeBrightness.DARK -> true
            }
            LinkSondaTheme(
                confirmedAggregateStatus = ConfirmedAggregateStatus(status.value),
                themeMode = mode.value,
                themeBrightness = brightness.value,
            ) {
                ThemeShowcase(
                    aggregateStatus = status.value,
                    themeMode = mode.value,
                    dark = dark,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_MODE)?.toThemeMode()?.let { mode.value = it }
        intent.getStringExtra(EXTRA_BRIGHTNESS)?.toThemeBrightness()?.let { brightness.value = it }
        intent.getStringExtra(EXTRA_INITIAL_STATUS)?.toAggregateStatus()?.let { status.value = it }

        pendingStatusChange?.cancel()
        val target = intent.getStringExtra(EXTRA_STATUS)?.toAggregateStatus() ?: return
        val delayMillis = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
        if (delayMillis <= 0L) {
            status.value = target
        } else {
            pendingStatusChange = lifecycleScope.launch {
                delay(delayMillis)
                status.value = target
            }
        }
    }

    private fun String.toAggregateStatus(): AggregateStatus? =
        AggregateStatus.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    private fun String.toThemeMode(): ThemeMode? =
        ThemeMode.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    private fun String.toThemeBrightness(): ThemeBrightness? =
        ThemeBrightness.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    private companion object {
        const val EXTRA_STATUS = "status"
        const val EXTRA_INITIAL_STATUS = "initialStatus"
        const val EXTRA_MODE = "mode"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_DELAY_MS = "delayMs"
    }
}
