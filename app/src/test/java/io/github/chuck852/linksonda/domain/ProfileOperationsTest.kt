package io.github.chuck852.linksonda.domain

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileOperationsTest {
    private val tcp = ProbeConfig(
        ProbeId("tcp"),
        "TCP",
        ProbeRequest.TcpConnect(SocketEndpoint("example.com", 443)),
        5.seconds,
    )
    private val dns = ProbeConfig(
        ProbeId("dns"),
        "DNS",
        ProbeRequest.SystemDns(
            DnsQuestion("example.com", 1),
            DnsExpectation(responseCode = 0, requiresAnswer = true),
        ),
        3.seconds,
    )
    private val source = MonitoringProfile(
        id = ProfileId("source"),
        name = "Source",
        preset = Preset("source:preset", "Everyday", listOf(tcp, dns)),
        groups = listOf(ProbeGroup("Web", setOf(tcp.id))),
        schedule = MonitoringSchedule(5.minutes, 2),
        thresholds = AlertThresholds(3, 2),
        notifications = NotificationSettings(true, 30.minutes),
    )

    @Test
    fun newProfileUsesDefaultsAndNoCatalog() {
        val profile = newMonitoringProfile(ProfileId("new"), "New")

        assertEquals(60.seconds, profile.schedule.interval)
        assertEquals(4, profile.schedule.maxConcurrentProbes)
        assertEquals(3, profile.thresholds.consecutiveFailures)
        assertEquals(2, profile.thresholds.consecutiveSuccesses)
        assertEquals(false, profile.notifications.enabled)
        assertTrue(profile.preset.probes.isEmpty())
        assertTrue(profile.groups.isEmpty())
    }

    @Test
    fun copyIsIndependentSnapshot() {
        val copy = copyMonitoringProfile(source, ProfileId("copy"), "Copy")

        assertEquals("Copy", copy.name)
        assertNotEquals(source.id, copy.id)
        assertEquals(source.schedule, copy.schedule)
        assertEquals(source.thresholds, copy.thresholds)
        assertEquals(source.notifications, copy.notifications)
        assertEquals(source.preset.probes.size, copy.preset.probes.size)
        assertTrue(copy.preset.probes.none { candidate -> source.preset.probes.any { it.id == candidate.id } })
        assertTrue(copy.preset.probes.all { it.revision == ProbeRevision(1) })
        assertEquals(setOf(copy.preset.probes.first().id), copy.groups.single().probeIds)

        val editedCopy = copy.addOrReplaceProbe(
            copy.preset.probes.first().update(name = "Changed"),
            "Other",
        )
        assertEquals("TCP", source.preset.probes.first().name)
        assertEquals("Changed", editedCopy.preset.probes.first().name)
        assertEquals("Web", source.groups.single().name)
    }

    @Test
    fun addReplaceAndMoveProbeKeepsOneGroup() {
        val withNew = source.addOrReplaceProbe(dns, "DNS")
        assertEquals(setOf("Web", "DNS"), withNew.groups.map { it.name }.toSet())
        assertEquals(setOf(dns.id), withNew.groups.first { it.name == "DNS" }.probeIds)

        val moved = withNew.addOrReplaceProbe(tcp, "DNS")
        assertEquals(setOf(dns.id, tcp.id), moved.groups.single().probeIds)
        assertEquals("DNS", moved.groups.single().name)
        assertEquals(2, moved.preset.probes.size)
    }

    @Test
    fun removeProbeDropsEmptyGroup() {
        val withoutTcp = source.removeProbe(tcp.id)

        assertEquals(listOf(dns), withoutTcp.preset.probes)
        assertTrue(withoutTcp.groups.isEmpty())
    }

    @Test
    fun renameGroupRejectsDuplicateAndMissing() {
        val renamed = source.renameGroup("Web", "Endpoints")
        assertEquals("Endpoints", renamed.groups.single().name)
        assertEquals(source.groups.single().probeIds, renamed.groups.single().probeIds)

        assertEquals(source, source.renameGroup("Web", "Web"))
        assertThrows(IllegalArgumentException::class.java) { source.renameGroup("Missing", "Other") }
    }
}
