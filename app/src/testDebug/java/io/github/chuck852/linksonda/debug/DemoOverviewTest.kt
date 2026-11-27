package io.github.chuck852.linksonda.debug

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.ui.overview.ManualCheckState
import io.github.chuck852.linksonda.ui.overview.OverviewFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoOverviewTest {
    @Test
    fun everyScenarioIsMarkedAndInternallyConsistent() {
        for (scenario in DemoScenario.entries) {
            val state = demoOverview(scenario)
            assertTrue("$scenario must be marked as demo", state.isDemo)
            assertEquals("$scenario has duplicate probes", state.probes.size, state.probes.map { it.id }.distinct().size)
            assertEquals("$scenario has duplicate groups", state.groups.size, state.groups.map { it.id }.distinct().size)
            for (group in state.groups) {
                val probes = state.probesFor(group.id)
                assertEquals("$scenario ${group.id} total", group.totalProbes, probes.size)
                assertEquals(
                    "$scenario ${group.id} available",
                    group.availableProbes,
                    probes.count { it.status == ProbeStatus.UP },
                )
            }
            assertEquals(
                "$scenario probe count",
                state.probes.size,
                state.groups.sumOf { it.totalProbes },
            )
        }
    }

    @Test
    fun scenariosCoverTheRequiredPresentationStates() {
        assertEquals(OverviewFreshness.MISSING, demoOverview(DemoScenario.EMPTY).freshness)
        assertEquals(OverviewFreshness.MISSING, demoOverview(DemoScenario.UNKNOWN).freshness)
        assertEquals(AggregateStatus.UNKNOWN, demoOverview(DemoScenario.UNKNOWN).groups.first().status)
        assertEquals(ManualCheckState.RUNNING, demoOverview(DemoScenario.RUNNING).manualCheck)
        assertEquals(AggregateStatus.AVAILABLE, demoOverview(DemoScenario.RUNNING).aggregateStatus)
        assertEquals(AggregateStatus.PARTIAL, demoOverview(DemoScenario.PARTIAL).aggregateStatus)
        assertEquals(AggregateStatus.UNAVAILABLE, demoOverview(DemoScenario.DOWN).aggregateStatus)
        assertEquals(AggregateStatus.AVAILABLE, demoOverview(DemoScenario.UP).aggregateStatus)
    }
}
