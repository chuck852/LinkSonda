package io.github.chuck852.linksonda.storage

import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.DnsTransport
import io.github.chuck852.linksonda.domain.DnsObservation
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.HttpObservation
import io.github.chuck852.linksonda.domain.IcmpObservation
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.RttSummary
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.domain.TlsObservation
import io.github.chuck852.linksonda.domain.TlsVersion
import java.net.URI
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StorageJsonTest {
    @Test
    fun everyRequestVariantRoundTrips() {
        val requests = listOf(
            ProbeRequest.TcpConnect(SocketEndpoint("example.com", 443)),
            ProbeRequest.TlsHandshake(SocketEndpoint("example.com", 443), "example.com", TlsVersion.TLS_1_3),
            ProbeRequest.Http(
                URI("https://example.com/path?q=1"),
                HttpMethod.GET,
                HttpExpectation(200..299, "ok"),
            ),
            ProbeRequest.SystemDns(DnsQuestion("example.com", 1), DnsExpectation(0, true)),
            ProbeRequest.DirectDns(
                SocketEndpoint("1.1.1.1", 53),
                DnsTransport.UDP,
                DnsQuestion("example.com", 28),
                DnsExpectation(0, true),
            ),
            ProbeRequest.DnsOverHttps(
                URI("https://cloudflare-dns.com/dns-query"),
                DnsQuestion("example.com", 1),
                DnsExpectation(0, true),
            ),
            ProbeRequest.DnsOverTls(
                SocketEndpoint("1.1.1.1", 853),
                "cloudflare-dns.com",
                DnsQuestion("example.com", 1),
                DnsExpectation(0, true),
            ),
            ProbeRequest.IcmpEcho("example.com", packetCount = 3, minimumReplies = 2, family = IpFamily.IPV4),
        )

        requests.forEach { request ->
            val json = storageJson.encodeToString(ProbeRequest.serializer(), request)
            assertEquals(request, storageJson.decodeFromString(ProbeRequest.serializer(), json))
        }
    }

    @Test
    fun resultObservationsRoundTripWithoutResponseBodies() {
        val result = ProbeResult.Completed(
            context = io.github.chuck852.linksonda.domain.ProbeContext(
                StorageFixtures.tcp.key,
                StorageFixtures.networkId,
            ),
            recordedAt = Instant.parse("2026-11-01T10:00:00Z"),
            timing = io.github.chuck852.linksonda.domain.ProbeTiming(120.milliseconds),
            outcome = ProbeOutcome.Restricted(
                io.github.chuck852.linksonda.domain.RestrictionReason.RATE_LIMITED,
                retryAt = Instant.parse("2026-11-01T10:01:00Z"),
            ),
            observations = ProbeObservations(
                connectedIpFamily = IpFamily.IPV4,
                http = HttpObservation(200, true),
                dns = DnsObservation(0, 2, false),
                tls = TlsObservation(TlsVersion.TLS_1_3),
                icmp = IcmpObservation(3, 2, RttSummary(1.milliseconds, 2.milliseconds, 3.milliseconds)),
            ),
        )

        val json = storageJson.encodeToString(ProbeResult.serializer(), result)
        assertEquals(result, storageJson.decodeFromString(ProbeResult.serializer(), json))
        assertFalse(json.contains("responseBody", ignoreCase = true))
        assertFalse(json.contains("authorization", ignoreCase = true))
        assertFalse(json.contains("headers", ignoreCase = true))
    }
}
