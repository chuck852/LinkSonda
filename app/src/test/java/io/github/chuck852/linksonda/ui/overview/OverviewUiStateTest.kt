package io.github.chuck852.linksonda.ui.overview

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.ProbeStatus
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
    }

    private fun probe(id: String, groupId: String) = ProbeUiSummary(
        id = id,
        groupId = groupId,
        name = id,
        kind = ProbeKind.TCP,
        target = "192.0.2.1:443",
        status = ProbeStatus.UNKNOWN,
    )
}
