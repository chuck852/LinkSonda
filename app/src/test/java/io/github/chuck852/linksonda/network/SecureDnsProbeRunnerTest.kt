package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsObservation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkSnapshot
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeAttempt
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeMode
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import javax.net.ssl.SSLContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureDnsProbeRunnerTest {
    @Test
    fun dohPostsDnsMessageAndMatches() {
        val server = LocalHttpServer(https = true) { call ->
            assertEquals("application/dns-message", call.header("Content-Type"))
            assertTrue(call.body.isNotEmpty())
            HttpResponseSpec(
                status = 200,
                body = dnsResponse(call.body, answerCount = 1, answerBytes = aRecord()),
                headers = mapOf("Content-Type" to "application/dns-message"),
            )
        }
        server.use {
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(DnsObservation(0, 1, false), completed.observations.dns)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertEquals(2, admission.attempts.size)
            assertEquals(ProbeAttempt("example.test", "127.0.0.1"), admission.attempts[0])
            assertEquals(ProbeAttempt("127.0.0.1", "127.0.0.1"), admission.attempts[1])
        }
    }

    @Test
    fun dohRejectsWrongContentType() {
        LocalHttpServer(https = true) { call ->
            HttpResponseSpec(
                status = 200,
                body = dnsResponse(call.body, answerCount = 1, answerBytes = aRecord()),
                headers = mapOf("Content-Type" to "text/plain"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            failed(result, FailureReason.HTTP)
        }
    }

    @Test
    fun dohRejectsNonSuccessStatus() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 503, headers = mapOf("Content-Type" to "application/dns-message"))
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            failed(result, FailureReason.HTTP)
        }
    }

    @Test
    fun dohRejectsOversizedBody() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(
                status = 200,
                body = ByteArray(SecureDnsProbeRunner.MAX_DNS_MESSAGE + 1),
                headers = mapOf("Content-Type" to "application/dns-message"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            failed(result, FailureReason.HTTP)
        }
    }

    @Test
    fun dohRejectsWrongDnsId() {
        LocalHttpServer(https = true) { call ->
            val response = dnsResponse(call.body, answerCount = 1, answerBytes = aRecord()).copyOf()
            response[0] = (response[0].toInt() xor 0xFF).toByte()
            HttpResponseSpec(
                status = 200,
                body = response,
                headers = mapOf("Content-Type" to "application/dns-message"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            failed(result, FailureReason.UNEXPECTED_RESPONSE)
        }
    }

    @Test
    fun dohTimeoutIsReportedAsTimeout() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(
                status = 200,
                body = ByteArray(0),
                headers = mapOf("Content-Type" to "application/dns-message"),
                delayBeforeHeadersMillis = 500,
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
                timeout = 100.milliseconds,
            )

            failed(result, FailureReason.TIMEOUT)
        }
    }

    @Test
    fun dohAdmissionDenialSendsNoRequest() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 200, headers = mapOf("Content-Type" to "application/dns-message"))
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
                admission = RecordingAdmission(listOf(AdmissionDecision.Denied(SkipReason.COOLDOWN))),
            )

            skipped(result, SkipReason.COOLDOWN)
            assertTrue(server.calls().isEmpty())
        }
    }

    @Test
    fun dotUsesTlsFramingAndSni() {
        DotDnsServer { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dotRequest(server.port, "localhost"),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(DnsObservation(0, 1, false), completed.observations.dns)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertEquals("localhost", server.sni())
            assertTrue(server.awaitReceived())
            assertEquals(2, admission.attempts.size)
            assertEquals(ProbeAttempt("127.0.0.1", "127.0.0.1"), admission.attempts[0])
            assertEquals(ProbeAttempt("example.test", "127.0.0.1"), admission.attempts[1])
        }
    }

    @Test
    fun dotRejectsUntrustedCertificate() {
        DotDnsServer { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = false),
                request = dotRequest(server.port, "localhost"),
            )

            failed(result, FailureReason.TLS)
        }
    }

    @Test
    fun dotRejectsWrongDnsId() {
        DotDnsServer { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord()).copyOf().also {
                it[0] = (it[0].toInt() xor 0xFF).toByte()
            }
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dotRequest(server.port, "localhost"),
            )

            failed(result, FailureReason.UNEXPECTED_RESPONSE)
        }
    }

    @Test
    fun dotTransportAdmissionDenialOpensNoConnection() {
        DotDnsServer { query -> dnsResponse(query, answerCount = 1, answerBytes = aRecord()) }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dotRequest(server.port, "localhost"),
                admission = RecordingAdmission(listOf(AdmissionDecision.Denied(SkipReason.COOLDOWN))),
            )

            skipped(result, SkipReason.COOLDOWN)
            assertTrue(!server.awaitAccepted(200))
        }
    }

    @Test
    fun dotHostnameResolverHasBootstrapAdmission() {
        DotDnsServer { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(
                    trustTestTls = true,
                    hostResolver = HostResolver { _, _ -> listOf(InetAddress.getByName("127.0.0.1")) },
                ),
                request = ProbeRequest.DnsOverTls(
                    resolver = SocketEndpoint("resolver.test", server.port),
                    serverName = "localhost",
                    question = question(),
                    expected = DnsExpectation(0, true),
                ),
                admission = admission,
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertEquals(
                listOf(
                    ProbeAttempt("resolver.test", "system-dns"),
                    ProbeAttempt("resolver.test", "resolver.test"),
                    ProbeAttempt("example.test", "resolver.test"),
                ),
                admission.attempts,
            )
        }
    }

    @Test
    fun dotTimeoutClosesConnection() {
        DotDnsServer(handler = { null }).use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dotRequest(server.port, "localhost"),
                timeout = 100.milliseconds,
            )

            failed(result, FailureReason.TIMEOUT)
            assertTrue(server.awaitAccepted())
        }
    }

    @Test
    fun dotIpv6LiteralUsesObservedFamily() {
        if (!ipv6LoopbackAvailable()) return
        DotDnsServer(bindAddress = "::1") { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aaaaRecord())
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = ProbeRequest.DnsOverTls(
                    resolver = SocketEndpoint("::1", server.port),
                    serverName = "localhost",
                    question = question(DnsQuestion("example.test", 28)),
                    expected = DnsExpectation(0, true),
                ),
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV6, completed.observations.connectedIpFamily)
        }
    }

    private fun runner(
        trustTestTls: Boolean = false,
        hostResolver: HostResolver = SystemHostResolver(),
    ): SecureDnsProbeRunner {
        val client = OkHttpClient.Builder().apply {
            if (trustTestTls) {
                sslSocketFactory(TestTls.clientContext.socketFactory, TestTls.clientTrustManager)
            }
        }.build()
        val tls = if (trustTestTls) TestTls.clientContext else SSLContext.getDefault()
        return SecureDnsProbeRunner(client, tls, hostResolver)
    }

    private fun runProbe(
        runner: SecureDnsProbeRunner,
        request: ProbeRequest,
        timeout: Duration = 2.seconds,
        admission: ProbeAdmission = RecordingAdmission(),
    ): ProbeResult = runBlocking {
        runner.run(probeRunRequest(request, timeout), admission)
    }

    private fun dohRequest(url: String): ProbeRequest.DnsOverHttps =
        ProbeRequest.DnsOverHttps(
            resolver = URI(url),
            question = question(),
            expected = DnsExpectation(0, true),
        )

    private fun dotRequest(port: Int, serverName: String): ProbeRequest.DnsOverTls =
        ProbeRequest.DnsOverTls(
            resolver = SocketEndpoint("127.0.0.1", port),
            serverName = serverName,
            question = question(),
            expected = DnsExpectation(0, true),
        )

    private fun question(question: DnsQuestion = DnsQuestion("example.test", 1)): DnsQuestion = question

    private fun probeRunRequest(request: ProbeRequest, timeout: Duration): ProbeRunRequest =
        ProbeRunRequest(
            config = ProbeConfig(ProbeId("test-secure-dns"), "Test secure DNS", request, timeout),
            network = NetworkSnapshot(
                id = NetworkId("local-test"),
                capturedAt = Instant.now(),
                transports = setOf(NetworkTransport.ETHERNET),
                ipFamilies = setOf(IpFamily.IPV4, IpFamily.IPV6),
                validation = NetworkValidation.VALIDATED,
            ),
            mode = ProbeMode.MANUAL,
        )

    private fun completed(result: ProbeResult): ProbeResult.Completed {
        assertTrue("Expected Completed, got $result", result is ProbeResult.Completed)
        return result as ProbeResult.Completed
    }

    private fun failed(result: ProbeResult, reason: FailureReason): ProbeResult.Completed {
        val completed = completed(result)
        assertEquals(ProbeOutcome.Failed(reason), completed.outcome)
        return completed
    }

    private fun skipped(result: ProbeResult, reason: SkipReason): ProbeResult.Skipped {
        assertTrue("Expected Skipped, got $result", result is ProbeResult.Skipped)
        val skipped = result as ProbeResult.Skipped
        assertEquals(reason, skipped.reason)
        return skipped
    }

    private class RecordingAdmission(
        private val decisions: List<AdmissionDecision> = listOf(AdmissionDecision.Allowed),
    ) : ProbeAdmission {
        val attempts = mutableListOf<ProbeAttempt>()

        override suspend fun authorize(attempt: ProbeAttempt): AdmissionDecision {
            attempts += attempt
            return decisions.getOrElse(attempts.lastIndex) { AdmissionDecision.Allowed }
        }
    }
}
