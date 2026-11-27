package io.github.chuck852.linksonda

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import io.github.chuck852.linksonda.settings.AppLanguage
import io.github.chuck852.linksonda.settings.PersistentSettingsStore
import io.github.chuck852.linksonda.settings.SettingsStore
import io.github.chuck852.linksonda.settings.SharedPreferencesSettingsStorage
import io.github.chuck852.linksonda.settings.applySystemAppLanguage
import io.github.chuck852.linksonda.settings.withAppLanguage
import io.github.chuck852.linksonda.ui.LinkSondaApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore

    override fun attachBaseContext(newBase: Context) {
        settingsStore = PersistentSettingsStore(SharedPreferencesSettingsStorage(newBase))
        super.attachBaseContext(newBase.withAppLanguage(settingsStore.current().language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storedLanguage = settingsStore.current().language
        if (storedLanguage != AppLanguage.SYSTEM) {
            // SYSTEM is left to the platform picker; an explicit choice is restored here.
            applySystemAppLanguage(storedLanguage)
        }
        enableEdgeToEdge()
        setContent {
            val settings by settingsStore.settings.collectAsState()
            val scope = rememberCoroutineScope()
            LinkSondaApp(
                settings = settings,
                onSettingsChange = { updated ->
                    scope.launch {
                        val languageChanged = updated.language != settings.language
                        val saved = try {
                            settingsStore.save(updated)
                        } catch (_: Exception) {
                            false
                        }
                        if (!saved) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.settings_save_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else if (languageChanged) {
                            applySystemAppLanguage(updated.language)
                            recreate()
                        }
                    }
                },
            )
        }
    }
}
