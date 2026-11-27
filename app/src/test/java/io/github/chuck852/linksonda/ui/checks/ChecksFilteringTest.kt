package io.github.chuck852.linksonda.ui.checks

import androidx.compose.runtime.saveable.SaverScope
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeState
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.SocketEndpoint
import java.net.URI
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksFilteringTest {
    private val https = item(
        id = "https-global",
        name = "Global HTTPS",
        groupId = "global",
        groupName = "Global services",
        request = ProbeRequest.Http(
            url = URI("https://example.com/"),
            method = HttpMethod.GET,
            expected = HttpExpectation(200..299),
        ),
        status = ProbeStatus.UP,
        freshness = ProbeFreshness.FRESH,
    )
    private val tcp = item(
        id = "tcp-regional",
        name = "Regional TCP",
        groupId = "regional",
        groupName = "Regional services",
        request = ProbeRequest.TcpConnect(SocketEndpoint("192.0.2.1", 443)),
        status = ProbeStatus.DOWN,
        freshness = ProbeFreshness.FRESH,
    )
    private val dns = item(
        id = "dns-global",
        name = "Global DNS",
        groupId = "global",
        groupName = "Global services",
        request = ProbeRequest.DirectDns(
            resolver = SocketEndpoint("192.0.2.53", 53),
            transport = io.github.chuck852.linksonda.domain.DnsTransport.UDP,
            question = io.github.chuck852.linksonda.domain.DnsQuestion("example.com", 1),
            expected = io.github.chuck852.linksonda.domain.DnsExpectation(0, true),
        ),
        status = ProbeStatus.UP,
        freshness = ProbeFreshness.STALE,
    )
    private val items = listOf(https, tcp, dns)

    @Test
    fun queryMatchesNameGroupTargetAndProtocol() {
        assertEquals(listOf(https), items.applyFilters(ChecksFilterState(query = "https")))
        assertEquals(listOf(tcp), items.applyFilters(ChecksFilterState(query = "Regional")))
        assertEquals(listOf(dns), items.applyFilters(ChecksFilterState(query = "192.0.2.53")))
        assertEquals(listOf(https), items.applyFilters(ChecksFilterState(query = "HTTPS")))
    }

    @Test
    fun groupAndProtocolFiltersAreIndependent() {
        assertEquals(
            listOf(https, dns),
            items.applyFilters(ChecksFilterState(groupId = "global")),
        )
        assertEquals(
            listOf(dns),
            items.applyFilters(ChecksFilterState(groupId = "global", protocol = ProbeProtocol.DNS_UDP)),
        )
    }

    @Test
    fun statusFilterIncludesStalePresentationState() {
        assertEquals(listOf(tcp), items.applyFilters(ChecksFilterState(status = ChecksStatusFilter.DOWN)))
        assertEquals(listOf(dns), items.applyFilters(ChecksFilterState(status = ChecksStatusFilter.STALE)))
    }

    @Test
    fun activeFiltersAreDetected() {
        assertTrue(ChecksFilterState().hasActiveFilters.not())
        assertTrue(ChecksFilterState(query = " ").hasActiveFilters.not())
        assertTrue(ChecksFilterState(groupId = "global").hasActiveFilters)
        assertTrue(ChecksFilterState(status = ChecksStatusFilter.UP).hasActiveFilters)
        assertTrue(ChecksFilterState(protocol = ProbeProtocol.TCP).hasActiveFilters)
    }

    @Test
    fun routeGroupInitializesOnlyTheGroupFilter() {
        assertEquals(ChecksFilterState(groupId = "regional"), initialChecksFilters("regional"))
        assertEquals(ChecksFilterState(), initialChecksFilters(null))
    }

    @Test
    fun saverRestoresQueryAndFilters() {
        val state = ChecksFilterState(
            query = "regional",
            groupId = "regional",
            status = ChecksStatusFilter.STALE,
            protocol = ProbeProtocol.TCP,
        )
        val scope = SaverScope { true }
        val saved = ChecksFilterStateSaver.run { scope.save(state) }
        val restored = ChecksFilterStateSaver.restore(requireNotNull(saved))

        assertEquals(state, restored)
    }

    private fun item(
        id: String,
        name: String,
        groupId: String,
        groupName: String,
        request: ProbeRequest,
        status: ProbeStatus,
        freshness: ProbeFreshness,
    ): ProbeListItem {
        val config = ProbeConfig(ProbeId(id), name, request, 3.seconds)
        val context = ProbeContext(config.key, NetworkId("test-network"))
        val completed = ProbeResult.Completed(
            context = context,
            recordedAt = Instant.parse("2026-11-27T15:00:00Z"),
            timing = ProbeTiming(1.seconds),
            outcome = ProbeOutcome.Matched,
        )
        val state = ProbeState(
            context = context,
            status = status,
            lifecycle = ProbeLifecycle.IDLE,
            freshness = freshness,
            lastCompleted = if (freshness == ProbeFreshness.MISSING) null else completed,
        )
        return ProbeListItem(config, state, groupId, groupName)
    }
}
