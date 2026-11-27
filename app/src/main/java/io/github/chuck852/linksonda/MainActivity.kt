package io.github.chuck852.linksonda

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.lifecycleScope
import io.github.chuck852.linksonda.settings.AppLanguage
import io.github.chuck852.linksonda.settings.SettingsStore
import io.github.chuck852.linksonda.settings.applySystemAppLanguage
import io.github.chuck852.linksonda.settings.withAppLanguage
import io.github.chuck852.linksonda.ui.LinkSondaApp
import io.github.chuck852.linksonda.ui.screens.UpdaterActions
import io.github.chuck852.linksonda.updater.UpdaterScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore

    override fun attachBaseContext(newBase: Context) {
        settingsStore = (newBase.applicationContext as LinkSondaApplication).settingsStore
        super.attachBaseContext(newBase.withAppLanguage(settingsStore.current().language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startupLanguage = settingsStore.current().language
        if (startupLanguage != AppLanguage.SYSTEM) {
            // SYSTEM is left to the platform picker; an explicit choice is restored here.
            applySystemAppLanguage(startupLanguage)
        }
        // The startup snapshot can be stale after process death; DataStore wins once it is read.
        lifecycleScope.launch {
            try {
                settingsStore.initialize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not load authoritative settings", error)
                return@launch
            }
            val loadedLanguage = settingsStore.current().language
            UpdaterScheduler.sync(this@MainActivity, settingsStore.current().updateChecksEnabled)
            if (loadedLanguage != startupLanguage) {
                applySystemAppLanguage(loadedLanguage)
                recreate()
            }
        }
        enableEdgeToEdge()
        val application = application as LinkSondaApplication
        setContent {
            val settings by settingsStore.settings.collectAsState()
            val updaterState by application.updater.state.collectAsState()
            val scope = rememberCoroutineScope()
            LinkSondaApp(
                settings = settings,
                updaterState = updaterState,
                updaterActions = UpdaterActions(
                    checkNow = application.updater::checkNow,
                    download = application.updater::download,
                    cancelDownload = application.updater::cancelDownload,
                    install = application.updater::install,
                    openInstallPermission = ::openInstallPermission,
                ),
                onSettingsChange = { transform ->
                    scope.launch {
                        val update = try {
                            settingsStore.update(transform)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (update == null) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.settings_save_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else if (update.previous.language != update.current.language) {
                            applySystemAppLanguage(update.current.language)
                            recreate()
                        } else if (update.previous.updateChecksEnabled != update.current.updateChecksEnabled) {
                            UpdaterScheduler.sync(
                                this@MainActivity,
                                update.current.updateChecksEnabled,
                            )
                        }
                    }
                },
            )
        }
    }

    private fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private companion object {
        const val TAG = "LinkSondaSettings"
    }
}
