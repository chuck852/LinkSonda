package io.github.chuck852.linksonda.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.chuck852.linksonda.debug.DemoScenario
import io.github.chuck852.linksonda.debug.demoOverview
import io.github.chuck852.linksonda.ui.checks.ChecksFilterState
import io.github.chuck852.linksonda.ui.checks.ProbeActions
import io.github.chuck852.linksonda.ui.theme.ConfirmedAggregateStatus
import io.github.chuck852.linksonda.ui.theme.LinkSondaTheme

@Preview(name = "Checks · mixed states", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun ChecksMixedPreview() {
    val state = demoOverview(DemoScenario.PARTIAL)
    LinkSondaTheme(confirmedAggregateStatus = ConfirmedAggregateStatus(state.aggregateStatus)) {
        ChecksScreen(
            state = state,
            filters = ChecksFilterState(),
            onFiltersChange = {},
            onProbeClick = {},
        )
    }
}

@Preview(name = "Details · matched phases", widthDp = 360, heightDp = 1400, showBackground = true)
@Composable
private fun DetailsMatchedPreview() = DetailsPreview("https-global")

@Preview(name = "Details · timeout", widthDp = 360, heightDp = 1000, showBackground = true)
@Composable
private fun DetailsTimeoutPreview() = DetailsPreview("http-regional")

@Preview(name = "Details · skipped", widthDp = 360, heightDp = 1000, showBackground = true)
@Composable
private fun DetailsSkippedPreview() = DetailsPreview("dns-tcp")

@Preview(name = "Details · inconclusive", widthDp = 360, heightDp = 1200, showBackground = true)
@Composable
private fun DetailsInconclusivePreview() = DetailsPreview("doh")

@Preview(name = "Details · restricted", widthDp = 360, heightDp = 1200, showBackground = true)
@Composable
private fun DetailsRestrictedPreview() = DetailsPreview("dot")

@Composable
private fun DetailsPreview(probeId: String) {
    val state = demoOverview(DemoScenario.PARTIAL)
    LinkSondaTheme(confirmedAggregateStatus = ConfirmedAggregateStatus(state.aggregateStatus)) {
        ProbeDetailsScreen(
            item = state.probes.firstOrNull { it.id.value == probeId },
            actions = ProbeActions(onRetry = {}, onEdit = {}),
            isDemo = true,
        )
    }
}
