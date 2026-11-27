package io.github.chuck852.linksonda.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import io.github.chuck852.linksonda.ui.LinkSondaApp
import io.github.chuck852.linksonda.ui.navigation.AppNavigationState
import io.github.chuck852.linksonda.ui.navigation.AppRoute
import io.github.chuck852.linksonda.ui.navigation.AppTab
import io.github.chuck852.linksonda.ui.navigation.CheckGroupFilter

/**
 * Debug-only harness for previews and emulator checks of navigation and the overview states.
 *
 * `adb shell am start -S -W -n io.github.chuck852.linksonda/.debug.OverviewDemoActivity`
 * `--es scenario PARTIAL --es tab OVERVIEW`
 * `--es filter regional`
 *
 * Every screen shows a demo banner; no probe runs and no data is persisted.
 */
class OverviewDemoActivity : ComponentActivity() {
    private val config = mutableStateOf(DemoConfig())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntent(intent)
        enableEdgeToEdge()
        setContent {
            val current = config.value
            key(current) {
                LinkSondaApp(
                    overviewState = demoOverview(current.scenario),
                    initialNavigation = current.navigation,
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
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)?.toScenario() ?: DemoScenario.EMPTY
        val navigation = intent.getStringExtra(EXTRA_FILTER)?.takeIf(String::isNotBlank)?.let { groupId ->
            AppNavigationState(
                listOf(
                    AppRoute.Tab(AppTab.OVERVIEW),
                    AppRoute.FilteredChecks(CheckGroupFilter(groupId)),
                ),
            )
        } ?: intent.getStringExtra(EXTRA_TAB)?.toTab()?.let {
            AppNavigationState(listOf(AppRoute.Tab(it)))
        } ?: AppNavigationState()
        config.value = DemoConfig(scenario = scenario, navigation = navigation)
    }

    private fun String.toScenario(): DemoScenario? =
        DemoScenario.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    private fun String.toTab(): AppTab? =
        AppTab.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    private data class DemoConfig(
        val scenario: DemoScenario = DemoScenario.EMPTY,
        val navigation: AppNavigationState = AppNavigationState(),
    )

    private companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_TAB = "tab"
        const val EXTRA_FILTER = "filter"
    }
}
