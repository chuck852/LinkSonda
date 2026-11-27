package io.github.chuck852.linksonda.ui.checks

import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeKey
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRevision
import io.github.chuck852.linksonda.domain.ProbeState
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeListItemTest {
    private val probeId = ProbeId("example")
    private val networkId = NetworkId("wifi")
    private val recordedAt = Instant.parse("2026-11-27T16:00:00Z")
    private val request = ProbeRequest.TcpConnect(SocketEndpoint("example.test", 443))

    @Test
    fun stateMustBelongToTheSameConfigurationRevision() {
        val config = config(ProbeRevision(2))
        val state = state(ProbeRevision(1))

        assertThrows(IllegalArgumentException::class.java) {
            ProbeListItem(config, state, "global", "Global services")
        }
    }

    @Test
    fun matchingRevisionIsAccepted() {
        val config = config(ProbeRevision(2))
        val item = ProbeListItem(config, state(ProbeRevision(2)), "global", "Global services")

        assertEquals(config.key, item.key)
        assertEquals(config.key, item.context.probe)
    }

    @Test
    fun olderSkippedSlotIsNotTheLatestState() {
        val config = config(ProbeRevision(1))
        val context = ProbeContext(config.key, networkId)
        val completed = ProbeResult.Completed(
            context = context,
            recordedAt = recordedAt,
            timing = ProbeTiming(20.milliseconds),
            outcome = ProbeOutcome.Matched,
        )
        val olderSkip = ProbeResult.Skipped(
            context = context,
            recordedAt = recordedAt.minusSeconds(30),
            reason = SkipReason.BUDGET,
        )
        val item = ProbeListItem(
            config = config,
            state = ProbeState(
                context = context,
                status = ProbeStatus.UP,
                lifecycle = ProbeLifecycle.IDLE,
                freshness = ProbeFreshness.FRESH,
                lastCompleted = completed,
                lastSkipped = olderSkip,
            ),
            groupId = "global",
            groupName = "Global services",
        )

        assertEquals(olderSkip, item.lastSkipped)
        assertNull(item.latestSkipped)
    }

    @Test
    fun newerSkippedSlotIsTheLatestState() {
        val config = config(ProbeRevision(1))
        val context = ProbeContext(config.key, networkId)
        val completed = ProbeResult.Completed(
            context = context,
            recordedAt = recordedAt.minusSeconds(30),
            timing = ProbeTiming(20.milliseconds),
            outcome = ProbeOutcome.Matched,
        )
        val newerSkip = ProbeResult.Skipped(
            context = context,
            recordedAt = recordedAt,
            reason = SkipReason.BUDGET,
        )
        val item = ProbeListItem(
            config = config,
            state = ProbeState(
                context = context,
                status = ProbeStatus.UP,
                lifecycle = ProbeLifecycle.IDLE,
                freshness = ProbeFreshness.FRESH,
                lastCompleted = completed,
                lastSkipped = newerSkip,
            ),
            groupId = "global",
            groupName = "Global services",
        )

        assertNotNull(item.latestSkipped)
        assertEquals(newerSkip, item.latestSkipped)
    }

    @Test
    fun ipv6EndpointsUseBrackets() {
        assertEquals("[2001:db8::1]:443", formatEndpoint("2001:db8::1", 443))
        assertEquals("[2001:db8::1]:53", formatEndpoint("[2001:db8::1]", 53))
        assertEquals("example.test:443", formatEndpoint("example.test", 443))
        assertEquals("192.0.2.1:443", formatEndpoint("192.0.2.1", 443))
    }

    @Test
    fun targetTextFormatsIpv6Endpoints() {
        val tcp = ProbeRequest.TcpConnect(SocketEndpoint("2001:db8::1", 443))
        val dns = ProbeRequest.DirectDns(
            resolver = SocketEndpoint("2001:db8::53", 53),
            transport = io.github.chuck852.linksonda.domain.DnsTransport.UDP,
            question = io.github.chuck852.linksonda.domain.DnsQuestion("example.test", 1),
            expected = io.github.chuck852.linksonda.domain.DnsExpectation(0, true),
        )

        assertEquals("[2001:db8::1]:443", tcp.targetText())
        assertEquals("[2001:db8::53]:53 · example.test", dns.targetText())
    }

    private fun config(revision: ProbeRevision): ProbeConfig =
        ProbeConfig.restore(
            key = ProbeKey(probeId, revision),
            name = "Example",
            request = request,
            timeout = 3.seconds,
        )

    private fun state(revision: ProbeRevision): ProbeState = ProbeState(
        context = ProbeContext(ProbeKey(probeId, revision), networkId),
        status = ProbeStatus.UNKNOWN,
        lifecycle = ProbeLifecycle.IDLE,
        freshness = ProbeFreshness.MISSING,
    )
}
