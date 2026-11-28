package io.github.chuck852.linksonda.ui.profiles

import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.AlertThresholds
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.MonitoringSchedule
import io.github.chuck852.linksonda.domain.NotificationSettings
import io.github.chuck852.linksonda.domain.Preset
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeGroup
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.ProfileId
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.ui.overview.ManualCheckState
import io.github.chuck852.linksonda.ui.overview.OverviewFreshness
import io.github.chuck852.linksonda.ui.overview.OverviewMonitoring
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileOverviewTest {
    private val tcp = ProbeConfig(
        ProbeId("tcp"),
        "TCP",
        ProbeRequest.TcpConnect(SocketEndpoint("example.com", 443)),
        5.minutes,
    )
    private val dns = ProbeConfig(
        ProbeId("dns"),
        "DNS",
        ProbeRequest.IcmpEcho("example.com", 1, 1),
        5.minutes,
    )
    private val profile = MonitoringProfile(
        id = ProfileId("home"),
        name = "Home",
        preset = Preset("home:preset", "Custom", listOf(tcp, dns)),
        groups = listOf(ProbeGroup("Web", setOf(tcp.id))),
        schedule = MonitoringSchedule(5.minutes, 2),
        thresholds = AlertThresholds(3, 2),
        notifications = NotificationSettings(false),
    )

    @Test
    fun mapsStoredProfileWithoutInventingResults() {
        val state = profile.toOverviewUiState("Other checks")

        assertEquals("home", state.activeProfile?.id)
        assertEquals(300L, state.activeProfile?.intervalSeconds)
        assertEquals(listOf("Web", UNGROUPED_GROUP_ID), state.groups.map { it.id })
        assertEquals("Other checks", state.groups.last().name)
        assertEquals(2, state.probes.size)
        assertTrue(state.probes.all { it.status == ProbeStatus.UNKNOWN })
        assertEquals(AggregateStatus.UNKNOWN, state.aggregateStatus)
        assertEquals(OverviewFreshness.MISSING, state.freshness)
        assertEquals(OverviewMonitoring.NOT_CONFIGURED, state.monitoring)
        assertEquals(ManualCheckState.UNAVAILABLE, state.manualCheck)
        assertFalse(state.isDemo)
        assertEquals(tcp, state.probe(tcp.id)?.config)
        assertEquals("dns", state.probe(dns.id)?.id?.value)
    }

    @Test
    fun emptyProfileHasNoGroupsOrChecks() {
        val empty = MonitoringProfile(
            id = ProfileId("empty"),
            name = "Empty",
            preset = Preset("empty:preset", "Custom", emptyList()),
            groups = emptyList(),
            schedule = MonitoringSchedule(1.minutes, 1),
            thresholds = AlertThresholds(1, 1),
            notifications = NotificationSettings(false),
        ).toOverviewUiState("Other checks")

        assertNull(empty.groups.firstOrNull())
        assertNull(empty.probes.firstOrNull())
        assertEquals("Empty", empty.activeProfile?.name)
    }
}
