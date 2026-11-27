package io.github.chuck852.linksonda.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.chuck852.linksonda.debug.DemoScenario
import io.github.chuck852.linksonda.debug.demoOverview
import io.github.chuck852.linksonda.ui.theme.ConfirmedAggregateStatus
import io.github.chuck852.linksonda.ui.theme.LinkSondaTheme

@Preview(name = "Empty", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun EmptyPreview() = OverviewPreview(DemoScenario.EMPTY)

@Preview(name = "Unknown", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun UnknownPreview() = OverviewPreview(DemoScenario.UNKNOWN)

@Preview(name = "Running", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun RunningPreview() = OverviewPreview(DemoScenario.RUNNING)

@Preview(name = "Partial", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun PartialPreview() = OverviewPreview(DemoScenario.PARTIAL)

@Preview(name = "Down", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun DownPreview() = OverviewPreview(DemoScenario.DOWN)

@Preview(name = "Up", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun UpPreview() = OverviewPreview(DemoScenario.UP)

@Preview(
    name = "Partial · 320 dp · 200% font",
    widthDp = 320,
    heightDp = 800,
    fontScale = 2f,
    showBackground = true,
)
@Composable
private fun LargeFontPreview() = OverviewPreview(DemoScenario.PARTIAL)

@Composable
private fun OverviewPreview(scenario: DemoScenario) {
    val state = demoOverview(scenario)
    LinkSondaTheme(confirmedAggregateStatus = ConfirmedAggregateStatus(state.aggregateStatus)) {
        OverviewScreen(
            state = state,
            onGroupClick = {},
            onRunManualCheck = {},
        )
    }
}
