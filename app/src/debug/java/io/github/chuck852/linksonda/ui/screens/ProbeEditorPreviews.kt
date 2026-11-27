package io.github.chuck852.linksonda.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.chuck852.linksonda.debug.DemoScenario
import io.github.chuck852.linksonda.debug.demoOverview
import io.github.chuck852.linksonda.ui.checks.ProbeEditorState
import io.github.chuck852.linksonda.ui.theme.ConfirmedAggregateStatus
import io.github.chuck852.linksonda.ui.theme.LinkSondaTheme

@Preview(name = "Editor · HTTPS", widthDp = 360, heightDp = 1100, showBackground = true)
@Composable
private fun EditorHttpsPreview() = EditorPreview("https-global")

@Preview(name = "Editor · TLS", widthDp = 360, heightDp = 1100, showBackground = true)
@Composable
private fun EditorTlsPreview() = EditorPreview("tls-global")

@Preview(name = "Editor · DNS over UDP", widthDp = 360, heightDp = 1100, showBackground = true)
@Composable
private fun EditorDnsPreview() = EditorPreview("dns-udp")

@Preview(name = "Editor · ICMP", widthDp = 360, heightDp = 1000, showBackground = true)
@Composable
private fun EditorIcmpPreview() = EditorPreview("icmp-regional")

@Composable
private fun EditorPreview(probeId: String) {
    val state = demoOverview(DemoScenario.PARTIAL)
    val item = state.probes.first { it.id.value == probeId }
    LinkSondaTheme(confirmedAggregateStatus = ConfirmedAggregateStatus(state.aggregateStatus)) {
        ProbeEditorScreen(
            initialState = ProbeEditorState.from(item.config, item.groupId, item.groupName),
            baseConfig = item.config,
            isDemo = true,
            isBusy = false,
            canSave = true,
            canTest = true,
            status = null,
            onSave = {},
            onTest = {},
            onDraftChange = {},
        )
    }
}
