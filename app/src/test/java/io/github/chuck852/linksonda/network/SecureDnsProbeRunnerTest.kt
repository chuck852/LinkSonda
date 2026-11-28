package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsObservation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.InconclusiveReason
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
import io.github.chuck852.linksonda.domain.RestrictionReason
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SecureDnsProbeRunnerTest {
    private val fixedNow = Instant.parse("2026-11-28T10:00:00Z")

    @Test
    fun dohPostsDnsMessageAndMatches() {
        val server = LocalHttpServer(https = true) { call ->
            assertEquals("POST", call.method)
            assertEquals("application/dns-message", call.header("Content-Type"))
            assertEquals("application/dns-message", call.header("Accept"))
            assertEquals("identity", call.header("Accept-Encoding"))
            assertTrue(call.body.isNotEmpty())
            assertEquals(0, ((call.body[0].toInt() and 0xFF) shl 8) or (call.body[1].toInt() and 0xFF))
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
    fun dohRejectsServerErrorStatus() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 500, headers = mapOf("Content-Type" to "application/dns-message"))
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            failed(result, FailureReason.HTTP)
        }
    }

    @Test
    fun dohTooManyRequestsIsRateLimitedWithRetryAfter() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 429, headers = mapOf("Retry-After" to "120"))
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true, clock = Clock.fixed(fixedNow, java.time.ZoneOffset.UTC)),
                request = dohRequest(server.url("/dns-query")),
            )

            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, fixedNow.plusSeconds(120)),
                completed(result).outcome,
            )
        }
    }

    @Test
    fun dohServiceUnavailableIsRateLimitedWithHttpDate() {
        val retryAt = Instant.parse("2026-11-28T11:30:00Z")
        LocalHttpServer(https = true) {
            HttpResponseSpec(
                status = 503,
                headers = mapOf("Retry-After" to "Sat, 28 Nov 2026 11:30:00 GMT"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true, clock = Clock.fixed(fixedNow, java.time.ZoneOffset.UTC)),
                request = dohRequest(server.url("/dns-query")),
            )

            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, retryAt),
                completed(result).outcome,
            )
        }
    }

    @Test
    fun dohServiceUnavailableWithoutRetryAfterIsRateLimitedWithoutTime() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 503)
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, null),
                completed(result).outcome,
            )
        }
    }

    @Test
    fun dohServiceUnavailableWithZeroRetryAfterIsNotRetried() {
        var requests = 0
        LocalHttpServer(https = true) { call ->
            requests += 1
            if (requests == 1) {
                HttpResponseSpec(status = 503, headers = mapOf("Retry-After" to "0"))
            } else {
                HttpResponseSpec(
                    status = 200,
                    body = dnsResponse(call.body, answerCount = 1, answerBytes = aRecord()),
                    headers = mapOf("Content-Type" to "application/dns-message"),
                )
            }
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true, clock = Clock.fixed(fixedNow, java.time.ZoneOffset.UTC)),
                request = dohRequest(server.url("/dns-query")),
            )

            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, fixedNow),
                completed(result).outcome,
            )
            assertEquals(1, server.calls().size)
        }
    }

    @Test
    fun dohForbiddenIsAccessDenied() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 403)
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.ACCESS_DENIED, null),
                completed(result).outcome,
            )
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
                timeout = 1.seconds,
            )

            failed(result, FailureReason.TIMEOUT)
            assertTrue(server.awaitAccepted())
            assertTrue(server.awaitClosed(2_000))
        }
    }

    @Test
    fun dotIpv6LiteralUsesObservedFamily() {
        assumeTrue(ipv6LoopbackAvailable())
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

    @Test
    fun dohRejectsInvalidQuestionNameBeforeAdmission() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner(trustTestTls = true),
            request = dohRequest(
                url = "https://127.0.0.1:1/dns-query",
                question = DnsQuestion("a..b", 1),
            ),
            admission = admission,
        )

        failed(result, FailureReason.DNS)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun dotRejectsInvalidQuestionNameBeforeAdmission() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner(trustTestTls = true),
            request = dotRequest(
                port = 1,
                serverName = "localhost",
                question = DnsQuestion("a".repeat(64) + ".test", 1),
            ),
            admission = admission,
        )

        failed(result, FailureReason.DNS)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun dotInvalidIpLiteralIsDnsFailureWithoutException() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner(trustTestTls = true),
            request = ProbeRequest.DnsOverTls(
                resolver = SocketEndpoint("::gg", 853),
                serverName = "localhost",
                question = question(),
                expected = DnsExpectation(0, true),
            ),
            admission = admission,
        )

        failed(result, FailureReason.DNS)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun dotBootstrapSecurityExceptionIsPermissionDenied() {
        val result = runProbe(
            runner = runner(
                trustTestTls = true,
                hostResolver = HostResolver { _, _ -> throw SecurityException("denied") },
            ),
            request = ProbeRequest.DnsOverTls(
                resolver = SocketEndpoint("resolver.test", 853),
                serverName = "localhost",
                question = question(),
                expected = DnsExpectation(0, true),
            ),
        )

        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
            completed(result).outcome,
        )
    }

    @Test
    fun dotBootstrapEaccesIsPermissionDenied() {
        val result = runProbe(
            runner = runner(
                trustTestTls = true,
                hostResolver = HostResolver { _, _ -> throw SocketException("EACCES") },
            ),
            request = ProbeRequest.DnsOverTls(
                resolver = SocketEndpoint("resolver.test", 853),
                serverName = "localhost",
                question = question(),
                expected = DnsExpectation(0, true),
            ),
        )

        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
            completed(result).outcome,
        )
    }

    @Test
    fun dohBootstrapEaccesIsPermissionDenied() {
        val result = runProbe(
            runner = runner(
                trustTestTls = true,
                hostResolver = HostResolver { _, _ -> throw SocketException("EACCES") },
            ),
            request = dohRequest("https://localhost:1/dns-query"),
        )

        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
            completed(result).outcome,
        )
    }

    @Test
    fun dohBootstrapGenericIoIsDnsFailure() {
        val result = runProbe(
            runner = runner(
                trustTestTls = true,
                hostResolver = HostResolver { _, _ -> throw IOException("resolver failure") },
            ),
            request = dohRequest("https://localhost:1/dns-query"),
        )

        failed(result, FailureReason.DNS)
    }

    @Test
    fun dotRejectsHostnameMismatch() {
        DotDnsServer { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dotRequest(server.port, "wrong.test"),
            )

            failed(result, FailureReason.TLS)
        }
    }

    @Test
    fun dohRejectsUntrustedCertificate() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(
                status = 200,
                body = ByteArray(0),
                headers = mapOf("Content-Type" to "application/dns-message"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = false),
                request = dohRequest(server.url("/dns-query")),
            )

            failed(result, FailureReason.TLS)
        }
    }

    @Test
    fun dohSlowBodyIsTimeout() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(
                status = 200,
                body = ByteArray(10_000) { 1 },
                headers = mapOf("Content-Type" to "application/dns-message"),
                bodyChunkSize = 100,
                bodyChunkMillis = 100,
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true, readTimeout = 100.milliseconds),
                request = dohRequest(server.url("/dns-query")),
                timeout = 2.seconds,
            )

            failed(result, FailureReason.TIMEOUT)
            assertTrue(server.awaitCalls(1))
        }
    }

    @Test
    fun dohHostnameUsesBootstrapAndTransportAdmission() {
        LocalHttpServer(https = true) { call ->
            HttpResponseSpec(
                status = 200,
                body = dnsResponse(call.body, answerCount = 1, answerBytes = aRecord()),
                headers = mapOf("Content-Type" to "application/dns-message"),
            )
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(
                    trustTestTls = true,
                    hostResolver = HostResolver { _, _ -> listOf(InetAddress.getByName("127.0.0.1")) },
                ),
                request = dohRequest("https://localhost:${server.port}/dns-query"),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertEquals(
                listOf(
                    ProbeAttempt("example.test", "localhost"),
                    ProbeAttempt("localhost", "system-dns"),
                    ProbeAttempt("localhost", "localhost"),
                ),
                admission.attempts,
            )
        }
    }

    @Test
    fun dohAuthorizesOnlyAddressesActuallyAttempted() {
        LocalHttpServer(https = true) { call ->
            HttpResponseSpec(
                status = 200,
                body = dnsResponse(call.body, answerCount = 1, answerBytes = aRecord()),
                headers = mapOf("Content-Type" to "application/dns-message"),
            )
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(
                    trustTestTls = true,
                    hostResolver = HostResolver { _, _ ->
                        listOf(
                            InetAddress.getByName("127.0.0.1"),
                            InetAddress.getByName("127.0.0.2"),
                        )
                    },
                ),
                request = dohRequest("https://localhost:${server.port}/dns-query"),
                admission = admission,
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertEquals(
                listOf(
                    ProbeAttempt("example.test", "localhost"),
                    ProbeAttempt("localhost", "system-dns"),
                    ProbeAttempt("localhost", "localhost"),
                ),
                admission.attempts,
            )
        }
    }

    @Test
    fun dohIgnoresSystemHttpProxy() {
        val closedPort = ServerSocket(0).use { it.localPort }
        LocalHttpServer(https = true) { call ->
            HttpResponseSpec(
                status = 200,
                body = dnsResponse(call.body, answerCount = 1, answerBytes = aRecord()),
                headers = mapOf("Content-Type" to "application/dns-message"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(
                    trustTestTls = true,
                    proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", closedPort)),
                ),
                request = dohRequest("https://localhost:${server.port}/dns-query"),
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertEquals(1, server.calls().size)
        }
    }

    @Test
    fun dohTransportAdmissionDenialSendsNoRequest() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 200, headers = mapOf("Content-Type" to "application/dns-message"))
        }.use { server ->
            val result = runProbe(
                runner = runner(
                    trustTestTls = true,
                    hostResolver = HostResolver { _, _ -> listOf(InetAddress.getByName("127.0.0.1")) },
                ),
                request = dohRequest("https://localhost:${server.port}/dns-query"),
                admission = RecordingAdmission(
                    listOf(
                        AdmissionDecision.Allowed,
                        AdmissionDecision.Allowed,
                        AdmissionDecision.Denied(SkipReason.COOLDOWN),
                    ),
                ),
            )

            skipped(result, SkipReason.COOLDOWN)
            assertTrue(server.calls().isEmpty())
        }
    }

    @Test
    fun dohBootstrapCancellationInterruptsResolver() {
        val entered = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val resolver = HostResolver { _, _ ->
            entered.countDown()
            try {
                blocked.await()
                emptyList()
            } catch (error: InterruptedException) {
                interrupted.set(true)
                throw IOException("interrupted")
            }
        }
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(100.milliseconds) {
                    runner(trustTestTls = true, hostResolver = resolver).run(
                        probeRunRequest(
                            dohRequest("https://localhost:1/dns-query"),
                            5.seconds,
                        ),
                        RecordingAdmission(),
                    )
                }
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + 1_000_000_000L
        while (!interrupted.get() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        blocked.countDown()
        assertTrue(interrupted.get())
    }

    @Test
    fun dohBootstrapDenialStopsBeforeHttpRequest() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 200, headers = mapOf("Content-Type" to "application/dns-message"))
        }.use { server ->
            val result = runProbe(
                runner = runner(
                    trustTestTls = true,
                    hostResolver = HostResolver { _, _ -> listOf(InetAddress.getByName("127.0.0.1")) },
                ),
                request = dohRequest("https://localhost:${server.port}/dns-query"),
                admission = RecordingAdmission(
                    listOf(
                        AdmissionDecision.Allowed,
                        AdmissionDecision.Denied(SkipReason.COOLDOWN),
                    ),
                ),
            )

            skipped(result, SkipReason.COOLDOWN)
            assertTrue(server.calls().isEmpty())
        }
    }

    @Test
    fun dohDoesNotFollowRedirects() {
        LocalHttpServer(https = true) { call ->
            when (call.path) {
                "/dns-query" -> HttpResponseSpec(
                    status = 302,
                    headers = mapOf("Location" to "/other"),
                )
                else -> HttpResponseSpec(status = 500)
            }
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = dohRequest(server.url("/dns-query")),
            )

            failed(result, FailureReason.HTTP)
            assertEquals(1, server.calls().size)
        }
    }

    @Test
    fun dotSplitsTheTimeoutAcrossResolvedAddresses() {
        DotDnsServer(bindAddress = "127.0.0.2", handler = { null }).use { silent ->
            DotDnsServer(
                bindAddress = "127.0.0.1",
                port = silent.port,
                handler = { query ->
                    dnsResponse(query, answerCount = 1, answerBytes = aRecord())
                },
            ).use { server ->
                val started = System.nanoTime()
                val result = runProbe(
                    runner = runner(
                        trustTestTls = true,
                        hostResolver = HostResolver { _, _ ->
                            listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
                        },
                    ),
                    request = ProbeRequest.DnsOverTls(
                        resolver = SocketEndpoint("resolver.test", server.port),
                        serverName = "localhost",
                        question = question(),
                        expected = DnsExpectation(0, true),
                    ),
                    timeout = 4.seconds,
                )

                assertEquals(ProbeOutcome.Matched, completed(result).outcome)
                assertTrue((System.nanoTime() - started).nanoseconds < 4_500.milliseconds)
                assertTrue(silent.awaitAccepted())
                assertTrue(server.awaitReceived())
            }
        }
    }

    @Test
    fun dotSlowDripResponseHonorsAddressBudget() {
        DotDnsServer(
            bindAddress = "127.0.0.2",
            handler = { query -> dnsResponse(query, answerCount = 1, answerBytes = aRecord()) },
            responseChunkSize = 1,
            responseChunkMillis = 200,
        ).use { slow ->
            DotDnsServer(
                bindAddress = "127.0.0.1",
                port = slow.port,
                handler = { query -> dnsResponse(query, answerCount = 1, answerBytes = aRecord()) },
            ).use { server ->
                val started = System.nanoTime()
                val result = runProbe(
                    runner = runner(
                        trustTestTls = true,
                        hostResolver = HostResolver { _, _ ->
                            listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
                        },
                    ),
                    request = ProbeRequest.DnsOverTls(
                        resolver = SocketEndpoint("resolver.test", server.port),
                        serverName = "localhost",
                        question = question(),
                        expected = DnsExpectation(0, true),
                    ),
                    timeout = 3.seconds,
                )

                assertEquals(ProbeOutcome.Matched, completed(result).outcome)
                assertTrue((System.nanoTime() - started).nanoseconds < 3.seconds)
                assertTrue(slow.awaitReceived())
                assertTrue(server.awaitReceived())
            }
        }
    }

    @Test
    fun dohExternalCancellationPropagates() {
        LocalHttpServer(https = true) {
            HttpResponseSpec(status = 200, delayBeforeHeadersMillis = 2_000)
        }.use { server ->
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(100.milliseconds) {
                        runner(trustTestTls = true).run(
                            probeRunRequest(dohRequest(server.url("/dns-query")), 5.seconds),
                            RecordingAdmission(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun dotExternalCancellationPropagates() {
        DotDnsServer(handler = { null }).use { server ->
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(100.milliseconds) {
                        runner(trustTestTls = true).run(
                            probeRunRequest(dotRequest(server.port, "localhost"), 5.seconds),
                            RecordingAdmission(),
                        )
                    }
                }
            }
        }
    }

    private fun runner(
        trustTestTls: Boolean = false,
        hostResolver: HostResolver = SystemHostResolver(),
        readTimeout: Duration? = null,
        clock: Clock = Clock.systemUTC(),
        proxy: Proxy? = null,
    ): SecureDnsProbeRunner {
        val client = OkHttpClient.Builder().apply {
            if (trustTestTls) {
                sslSocketFactory(TestTls.clientContext.socketFactory, TestTls.clientTrustManager)
            }
            if (readTimeout != null) {
                readTimeout(readTimeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            if (proxy != null) {
                proxy(proxy)
            }
        }.build()
        val tls = if (trustTestTls) TestTls.clientContext else SSLContext.getDefault()
        return SecureDnsProbeRunner(client, tls, hostResolver, clock)
    }

    private fun runProbe(
        runner: SecureDnsProbeRunner,
        request: ProbeRequest,
        timeout: Duration = 2.seconds,
        admission: ProbeAdmission = RecordingAdmission(),
    ): ProbeResult = runBlocking {
        runner.run(probeRunRequest(request, timeout), admission)
    }

    private fun dohRequest(
        url: String,
        question: DnsQuestion = question(),
    ): ProbeRequest.DnsOverHttps = ProbeRequest.DnsOverHttps(
        resolver = URI(url),
        question = question,
        expected = DnsExpectation(0, true),
    )

    private fun dotRequest(
        port: Int,
        serverName: String,
        question: DnsQuestion = question(),
    ): ProbeRequest.DnsOverTls = ProbeRequest.DnsOverTls(
        resolver = SocketEndpoint("127.0.0.1", port),
        serverName = serverName,
        question = question,
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
