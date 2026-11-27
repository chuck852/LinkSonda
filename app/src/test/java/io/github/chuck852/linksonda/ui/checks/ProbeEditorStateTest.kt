package io.github.chuck852.linksonda.ui.checks

import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.DnsTransport
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeRevision
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.domain.TlsVersion
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeEditorStateTest {
    @Test
    fun validHttpsBuildsDomainRequest() {
        val validation = validHttps().validate()

        assertTrue(validation.errors.toString(), validation.isValid)
        val config = requireNotNull(validation.config)
        assertEquals(ProbeProtocol.HTTPS, config.request.toProtocol())
        assertEquals(1L, config.revision.value)
        assertTrue(config.request is ProbeRequest.Http)
    }

    @Test
    fun invalidFieldsPreventSaving() {
        val state = validHttps().copy(
            name = " ",
            url = "example.com",
            expectedStatus = "300-200",
            timeoutSeconds = "0",
        )

        val validation = state.validate()

        assertNull(validation.config)
        assertEquals(ProbeEditorError.REQUIRED, validation.errors[ProbeEditorField.NAME])
        assertEquals(ProbeEditorError.INVALID_URL, validation.errors[ProbeEditorField.URL])
        assertEquals(ProbeEditorError.INVALID_STATUS_RANGE, validation.errors[ProbeEditorField.EXPECTED_STATUS])
        assertEquals(ProbeEditorError.INVALID_TIMEOUT, validation.errors[ProbeEditorField.TIMEOUT])
    }

    @Test
    fun invalidPortAndHostAreRejected() {
        val validation = ProbeEditorState(
            probeId = "tcp",
            name = "TCP",
            protocol = ProbeProtocol.TCP,
            host = "https://example.com/path",
            port = "70000",
        ).validate()

        assertNull(validation.config)
        assertEquals(ProbeEditorError.INVALID_HOST, validation.errors[ProbeEditorField.HOST])
        assertEquals(ProbeEditorError.INVALID_PORT, validation.errors[ProbeEditorField.PORT])
    }

    @Test
    fun ipv6HostIsAccepted() {
        val validation = ProbeEditorState(
            probeId = "tcp",
            name = "IPv6 TCP",
            protocol = ProbeProtocol.TCP,
            host = "2001:db8::1",
            port = "443",
        ).validate()

        assertTrue(validation.errors.toString(), validation.isValid)
        assertEquals("[2001:db8::1]:443", requireNotNull(validation.config).request.targetText())
    }

    @Test
    fun ipFamilyMustMatchALiteralAddress() {
        val ipv6WithIpv4Family = ProbeEditorState(
            probeId = "tcp",
            name = "Mismatch",
            protocol = ProbeProtocol.TCP,
            host = "2001:db8::1",
            port = "443",
            ipFamily = IpFamily.IPV4,
        ).validate()
        val ipv4WithIpv6Family = ProbeEditorState(
            probeId = "https",
            name = "Mismatch",
            protocol = ProbeProtocol.HTTPS,
            url = "https://192.0.2.1/",
            ipFamily = IpFamily.IPV6,
        ).validate()

        assertNull(ipv6WithIpv4Family.config)
        assertEquals(ProbeEditorError.FAMILY_MISMATCH, ipv6WithIpv4Family.errors[ProbeEditorField.IP_FAMILY])
        assertNull(ipv4WithIpv6Family.config)
        assertEquals(ProbeEditorError.FAMILY_MISMATCH, ipv4WithIpv6Family.errors[ProbeEditorField.IP_FAMILY])
    }

    @Test
    fun tlsRequiresADnsServerName() {
        val missing = validTls().copy(serverName = "")
        val ipAddress = validTls().copy(serverName = "192.0.2.1")

        assertEquals(
            ProbeEditorError.REQUIRED,
            missing.validate().errors[ProbeEditorField.SERVER_NAME],
        )
        assertEquals(
            ProbeEditorError.INVALID_SERVER_NAME,
            ipAddress.validate().errors[ProbeEditorField.SERVER_NAME],
        )
        assertNull(missing.validate().config)
        assertNull(ipAddress.validate().config)
    }

    @Test
    fun tlsPassesTheMandatoryServerNameToTheDomain() {
        val config = requireNotNull(validTls().validate().config)
        val request = config.request as ProbeRequest.TlsHandshake

        assertEquals("example.test", request.serverName)
        assertEquals(TlsVersion.TLS_1_2, request.minimumVersion)
    }

    @Test
    fun changingProtocolResetsIncompatibleFields() {
        val http = validHttps().copy(
            bodyMarker = "ready",
            ipFamily = io.github.chuck852.linksonda.domain.IpFamily.IPV4,
        )

        assertEquals(http, http.withProtocol(ProbeProtocol.HTTPS))
        val tcp = http.withProtocol(ProbeProtocol.TCP)
        assertEquals("", tcp.url)
        assertEquals("", tcp.bodyMarker)
        assertEquals("200-299", tcp.expectedStatus)
        assertEquals("443", tcp.port)
        assertNull(tcp.ipFamily)

        val tls = http.withProtocol(ProbeProtocol.TLS)
        assertEquals("", tls.serverName)
        assertNull(tls.minimumTls)
        assertEquals("443", tls.port)

        val dns = http.withProtocol(ProbeProtocol.DNS_UDP)
        assertEquals("53", dns.port)
        assertEquals("1", dns.recordType)
        assertEquals("0", dns.expectedRcode)
        assertTrue(dns.requiresAnswer)
    }

    @Test
    fun completedTestIsHiddenAfterExecutionParametersChange() {
        val base = ProbeConfig(
            id = ProbeId("https"),
            name = "HTTPS",
            request = ProbeRequest.Http(
                url = URI("https://example.test/"),
                method = HttpMethod.GET,
                expected = HttpExpectation(200..299),
            ),
            timeout = 5.seconds,
        )
        val changed = base.update(
            request = ProbeRequest.Http(
                url = URI("https://example.test/health"),
                method = HttpMethod.GET,
                expected = HttpExpectation(200..299),
            ),
        )
        val status = ProbeEditorStatus.TestCompleted(
            configKey = base.key,
            outcome = ProbeOutcome.Matched,
            elapsed = 35.milliseconds,
            isDemo = true,
        )

        assertEquals(status, status.forConfiguration(base))
        assertNull(status.forConfiguration(changed))
        assertEquals(ProbeEditorStatus.Running, ProbeEditorStatus.Running.forConfiguration(changed))
    }

    @Test
    fun editingAParameterAdvancesTheRevision() {
        val base = ProbeConfig(
            id = ProbeId("https"),
            name = "HTTPS",
            request = ProbeRequest.Http(
                url = URI("https://example.test/"),
                method = HttpMethod.GET,
                expected = HttpExpectation(200..299),
            ),
            timeout = 5.seconds,
        )
        val state = ProbeEditorState.from(base, "global", "Global")
        val renamed = state.copy(name = "Renamed")
        val changed = state.copy(url = "https://example.test/health")
        val slower = state.copy(timeoutSeconds = "10")

        assertEquals(1L, requireNotNull(renamed.validate(base).config).revision.value)
        assertEquals(2L, requireNotNull(changed.validate(base).config).revision.value)
        assertEquals(2L, requireNotNull(slower.validate(base).config).revision.value)
    }

    @Test
    fun headRequestRejectsABodyMarker() {
        val validation = validHttps().copy(
            method = HttpMethod.HEAD,
            bodyMarker = "ready",
        ).validate()

        assertNull(validation.config)
        assertEquals(ProbeEditorError.HEAD_WITH_BODY_MARKER, validation.errors[ProbeEditorField.BODY_MARKER])
    }

    @Test
    fun nonZeroRcodeClearsRequiresAnswer() {
        val state = ProbeEditorState(
            probeId = "dns",
            name = "DNS",
            protocol = ProbeProtocol.SYSTEM_DNS,
            dnsName = "example.test",
        ).withExpectedRcode("3")

        assertTrue(state.requiresAnswer.not())
        assertTrue(state.validate().isValid)
    }

    @Test
    fun roundTripsEverySupportedProtocol() {
        val question = DnsQuestion("example.test", 1)
        val expectation = DnsExpectation(0, true)
        val requests = listOf(
            ProbeRequest.TcpConnect(SocketEndpoint("192.0.2.1", 443, IpFamily.IPV4)),
            ProbeRequest.TlsHandshake(
                SocketEndpoint("example.test", 443, IpFamily.IPV4),
                serverName = "example.test",
                minimumVersion = TlsVersion.TLS_1_3,
            ),
            ProbeRequest.Http(URI("http://example.test/"), HttpMethod.GET, HttpExpectation(200..299, "ok"), IpFamily.IPV4),
            ProbeRequest.Http(URI("https://example.test/"), HttpMethod.HEAD, HttpExpectation(200..399)),
            ProbeRequest.SystemDns(question, expectation),
            ProbeRequest.DirectDns(SocketEndpoint("192.0.2.53", 53, IpFamily.IPV4), DnsTransport.UDP, question, expectation),
            ProbeRequest.DirectDns(SocketEndpoint("192.0.2.54", 53), DnsTransport.TCP, question, expectation),
            ProbeRequest.DnsOverHttps(URI("https://dns.example/dns-query"), question, expectation, IpFamily.IPV4),
            ProbeRequest.DnsOverTls(SocketEndpoint("dns.example", 853, IpFamily.IPV4), "dns.example", question, expectation),
            ProbeRequest.IcmpEcho("192.0.2.1", 4, 3, IpFamily.IPV4),
        )

        for ((index, request) in requests.withIndex()) {
            val config = ProbeConfig(ProbeId("probe-$index"), "Probe $index", request, 5.seconds)
            val validation = ProbeEditorState.from(config, "global", "Global").validate(config)

            assertTrue("$request: ${validation.errors}", validation.isValid)
            assertEquals(request, requireNotNull(validation.config).request)
            assertEquals(config.revision, requireNotNull(validation.config).revision)
        }
    }

    @Test
    fun acceptsCompressedIpv6WithEightGroups() {
        assertTrue(isValidHost("1::2:3:4:5:6:7:8"))
        assertTrue(isValidHost("2001:db8::1"))
        assertTrue(isValidHost("::1"))
        assertTrue(isValidHost("::"))
        assertTrue(isValidHost("2001:db8::1::2").not())
    }

    @Test
    fun doesNotKeepAnIpv6BracketTwice() {
        assertEquals(
            "[2001:db8::1]:53",
            formatEndpoint("[2001:db8::1]", 53),
        )
    }

    private fun validHttps(): ProbeEditorState = ProbeEditorState(
        probeId = "https",
        name = "Example HTTPS",
        protocol = ProbeProtocol.HTTPS,
        url = "https://example.test/health",
        expectedStatus = "200-299",
        timeoutSeconds = "5",
    )

    private fun validTls(): ProbeEditorState = ProbeEditorState(
        probeId = "tls",
        name = "Example TLS",
        protocol = ProbeProtocol.TLS,
        host = "example.test",
        port = "443",
        serverName = "example.test",
        minimumTls = TlsVersion.TLS_1_2,
        timeoutSeconds = "5",
    )
}
