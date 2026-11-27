package io.github.chuck852.linksonda.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkSnapshot
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeMode
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.settings.AppSettings
import io.github.chuck852.linksonda.ui.LinkSondaApp
import io.github.chuck852.linksonda.ui.checks.ProbeEditorActions
import io.github.chuck852.linksonda.ui.checks.ProbeTestOutcome
import io.github.chuck852.linksonda.ui.navigation.AppNavigationState
import io.github.chuck852.linksonda.ui.navigation.AppRoute
import io.github.chuck852.linksonda.ui.navigation.AppTab
import io.github.chuck852.linksonda.ui.navigation.CheckGroupFilter
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import java.time.Instant
import kotlin.time.Duration

/**
 * Debug-only harness for previews and emulator checks of navigation, checks and the editor.
 *
 * `adb shell am start -S -W -n io.github.chuck852.linksonda/.debug.OverviewDemoActivity`
 * `--es scenario PARTIAL --es tab OVERVIEW`
 * `--es filter regional`
 * `--es probe https-global`
 * `--es edit https-global`
 * `--es editor TLS`
 * `--ez onboarding true`
 *
 * Every screen shows a demo banner; the demo runner performs no network request. Saved checks stay
 * in this activity's memory and are never written to production storage.
 */
class OverviewDemoActivity : ComponentActivity() {
    private val config = mutableStateOf(DemoConfig())
    private val appSettings = mutableStateOf(AppSettings.Default.copy(onboardingCompleted = true))
    private val overview = mutableStateOf(OverviewUiState.Empty)
    private val repository = DemoProfileRepository()
    private val runner = DemoProbeRunner()
    private val editorActions = ProbeEditorActions(
        onSave = { draft ->
            val updated = overview.value.withSavedProbe(draft)
            repository.saveProfile(updated.toMonitoringProfile())
            overview.value = updated
            draft.config
        },
        onTest = { probe ->
            val network = NetworkSnapshot(
                id = NetworkId("demo-network"),
                capturedAt = Instant.now(),
                transports = setOf(NetworkTransport.WIFI),
                ipFamilies = setOf(IpFamily.IPV4, IpFamily.IPV6),
                validation = NetworkValidation.VALIDATED,
            )
            val result = runner.run(
                request = ProbeRunRequest(probe, network, ProbeMode.MANUAL),
                admission = ProbeAdmission { AdmissionDecision.Allowed },
            )
            val completed = result as? ProbeResult.Completed
            ProbeTestOutcome(
                outcome = completed?.outcome ?: ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION),
                elapsed = completed?.timing?.elapsed ?: Duration.ZERO,
                isDemo = true,
            )
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntent(intent)
        enableEdgeToEdge()
        setContent {
            val current = config.value
            key(current) {
                LinkSondaApp(
                    overviewState = overview.value,
                    initialNavigation = current.navigation,
                    editorActions = editorActions,
                    settings = appSettings.value.copy(
                        onboardingCompleted = !current.showOnboarding,
                    ),
                    onSettingsChange = { updated ->
                        appSettings.value = updated
                        if (updated.onboardingCompleted && current.showOnboarding) {
                            config.value = current.copy(showOnboarding = false)
                        }
                    },
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
        val navigation = when {
            !intent.getStringExtra(EXTRA_EDIT).isNullOrBlank() -> {
                val probeId = requireNotNull(intent.getStringExtra(EXTRA_EDIT))
                AppNavigationState(
                    listOf(
                        AppRoute.Tab(AppTab.CHECKS),
                        AppRoute.ProbeEditor(probeId),
                    ),
                )
            }

            !intent.getStringExtra(EXTRA_EDITOR).isNullOrBlank() -> {
                val protocol = requireNotNull(intent.getStringExtra(EXTRA_EDITOR)).uppercase()
                AppNavigationState(
                    listOf(
                        AppRoute.Tab(AppTab.CHECKS),
                        AppRoute.ProbeEditor(initialProtocol = protocol),
                    ),
                )
            }

            !intent.getStringExtra(EXTRA_PROBE).isNullOrBlank() -> {
                val probeId = requireNotNull(intent.getStringExtra(EXTRA_PROBE))
                AppNavigationState(
                    listOf(
                        AppRoute.Tab(AppTab.CHECKS),
                        AppRoute.ProbeDetails(probeId),
                    ),
                )
            }

            !intent.getStringExtra(EXTRA_FILTER).isNullOrBlank() -> {
                val groupId = requireNotNull(intent.getStringExtra(EXTRA_FILTER))
                AppNavigationState(
                    listOf(
                        AppRoute.Tab(AppTab.OVERVIEW),
                        AppRoute.FilteredChecks(CheckGroupFilter(groupId)),
                    ),
                )
            }

            else -> intent.getStringExtra(EXTRA_TAB)?.toTab()?.let {
                AppNavigationState(listOf(AppRoute.Tab(it)))
            } ?: AppNavigationState()
        }
        config.value = DemoConfig(
            scenario = scenario,
            navigation = navigation,
            showOnboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false),
        )
        overview.value = demoOverview(scenario)
    }

    private fun String.toScenario(): DemoScenario? =
        DemoScenario.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    private fun String.toTab(): AppTab? =
        AppTab.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

    private data class DemoConfig(
        val scenario: DemoScenario = DemoScenario.EMPTY,
        val navigation: AppNavigationState = AppNavigationState(),
        val showOnboarding: Boolean = false,
    )

    private companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_TAB = "tab"
        const val EXTRA_FILTER = "filter"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_EDIT = "edit"
        const val EXTRA_EDITOR = "editor"
        const val EXTRA_ONBOARDING = "onboarding"
    }
}
