package io.github.chuck852.linksonda.ui.overview

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeState
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.ui.checks.ProbeListItem
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewUiStateTest {
    @Test
    fun emptyStateContainsNoDemoOrProductionResults() {
        val state = OverviewUiState.Empty

        assertEquals(AggregateStatus.UNKNOWN, state.aggregateStatus)
        assertEquals(OverviewFreshness.MISSING, state.freshness)
        assertEquals(OverviewMonitoring.NOT_CONFIGURED, state.monitoring)
        assertEquals(ManualCheckState.UNAVAILABLE, state.manualCheck)
        assertFalse(state.network.contextKnown)
        assertTrue(state.groups.isEmpty())
        assertTrue(state.probes.isEmpty())
        assertFalse(state.isDemo)
    }

    @Test
    fun probesForKeepsOnlyTheRequestedGroup() {
        val first = probe(id = "first", groupId = "global")
        val second = probe(id = "second", groupId = "regional")
        val state = OverviewUiState(probes = listOf(first, second))

        assertEquals(listOf(first), state.probesFor("global"))
        assertEquals(listOf(second), state.probesFor("regional"))
        assertTrue(state.probesFor("missing").isEmpty())
        assertEquals(first, state.probe(first.id))
    }

    private fun probe(id: String, groupId: String): ProbeListItem {
        val config = ProbeConfig(
            id = ProbeId(id),
            name = id,
            request = ProbeRequest.TcpConnect(SocketEndpoint("192.0.2.1", 443)),
            timeout = 3.seconds,
        )
        val context = ProbeContext(config.key, NetworkId("test-network"))
        return ProbeListItem(
            config = config,
            state = ProbeState(
                context = context,
                status = ProbeStatus.UNKNOWN,
                lifecycle = ProbeLifecycle.IDLE,
                freshness = ProbeFreshness.MISSING,
            ),
            groupId = groupId,
            groupName = groupId,
        )
    }
}
