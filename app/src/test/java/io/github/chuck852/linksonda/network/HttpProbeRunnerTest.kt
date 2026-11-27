package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.HttpObservation
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
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.RestrictionReason
import io.github.chuck852.linksonda.domain.SkipReason
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpProbeRunnerTest {
    private val fixedNow = Instant.parse("2026-11-28T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Test
    fun getMatchesStatusAndBodyMarker() {
        LocalHttpServer { _ ->
            HttpResponseSpec(status = 200, body = "hello marker world".toByteArray())
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/check"), bodyMarker = "marker"),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(HttpObservation(200, true), completed.observations.http)
            assertNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
            assertNotNull(completed.timing.phases[ProbePhase.HTTP_FIRST_BYTE])
            assertNull(completed.timing.phases[ProbePhase.TLS_HANDSHAKE])
            assertEquals(listOf(ProbeAttempt("127.0.0.1", "127.0.0.1")), admission.attempts)
            assertEquals(1, server.calls().size)
            assertNull(server.failure())
        }
    }

    @Test
    fun headChecksOnlyStatus() {
        LocalHttpServer { _ ->
            HttpResponseSpec(status = 200, body = "ignored body".toByteArray())
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/"), method = HttpMethod.HEAD, expected = 200..204),
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(HttpObservation(200, null), completed.observations.http)
            assertEquals("HEAD", server.calls().single().method)
        }
    }

    @Test
    fun noContentMatchesWithoutABody() {
        LocalHttpServer { _ -> HttpResponseSpec(status = 204) }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/"), expected = 204..204),
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertEquals(HttpObservation(204, null), completed(result).observations.http)
        }
    }

    @Test
    fun redirectIsFollowedWithSeparateAdmission() {
        LocalHttpServer { call ->
            when (call.path) {
                "/start" -> HttpResponseSpec(
                    status = 302,
                    headers = mapOf("Location" to "/final"),
                )
                "/final" -> HttpResponseSpec(status = 200, body = "ok".toByteArray())
                else -> HttpResponseSpec(status = 404)
            }
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/start")),
                admission = admission,
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertEquals(listOf("/start", "/final"), server.calls().map { it.path })
            assertEquals(2, admission.attempts.size)
            assertEquals(admission.attempts[0], admission.attempts[1])
        }
    }

    @Test
    fun httpsRedirectToCleartextIsRejected() {
        LocalHttpServer(https = true) { _ ->
            HttpResponseSpec(
                status = 302,
                headers = mapOf("Location" to "http://127.0.0.1/final"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = httpRequest(server.url("/start")),
            )

            failed(result, FailureReason.HTTP)
            assertEquals(1, server.calls().size)
        }
    }

    @Test
    fun redirectTargetWithUserInfoIsRejectedBeforeTheNextRequest() {
        LocalHttpServer { call ->
            when (call.path) {
                "/start" -> HttpResponseSpec(
                    status = 302,
                    headers = mapOf("Location" to "http://user:secret@127.0.0.1/final"),
                )
                else -> HttpResponseSpec(status = 200, body = "should not be reached".toByteArray())
            }
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/start")),
                admission = admission,
            )

            failed(result, FailureReason.HTTP)
            assertEquals(1, server.calls().size)
            assertEquals(1, admission.attempts.size)
        }
    }

    @Test
    fun redirectAdmissionDenialStopsBeforeTarget() {
        LocalHttpServer { call ->
            when (call.path) {
                "/start" -> HttpResponseSpec(
                    status = 302,
                    headers = mapOf("Location" to "/final"),
                )
                else -> HttpResponseSpec(status = 200, body = "ok".toByteArray())
            }
        }.use { server ->
            val admission = RecordingAdmission(
                listOf(
                    AdmissionDecision.Allowed,
                    AdmissionDecision.Denied(SkipReason.COOLDOWN),
                ),
            )
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/start")),
                admission = admission,
            )

            skipped(result, SkipReason.COOLDOWN)
            assertEquals(1, server.calls().size)
            assertEquals(2, admission.attempts.size)
        }
    }

    @Test
    fun unexpectedBodyMarkerFailsButKeepsStatus() {
        LocalHttpServer { _ ->
            HttpResponseSpec(status = 200, body = "hello".toByteArray())
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/"), bodyMarker = "missing"),
            )

            val completed = failed(result, FailureReason.UNEXPECTED_RESPONSE)
            assertEquals(HttpObservation(200, false), completed.observations.http)
        }
    }

    @Test
    fun unexpectedStatusKeepsHttpObservation() {
        LocalHttpServer { _ -> HttpResponseSpec(status = 500, body = "error".toByteArray()) }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/")),
            )

            val completed = failed(result, FailureReason.UNEXPECTED_HTTP_STATUS)
            assertEquals(HttpObservation(500), completed.observations.http)
        }
    }

    @Test
    fun serviceUnavailableWithRetryAfterIsRestrictedNotMissingTransport() {
        LocalHttpServer { _ ->
            HttpResponseSpec(
                status = 503,
                headers = mapOf("Retry-After" to "120"),
                body = "busy".toByteArray(),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/")),
            )

            val completed = completed(result)
            assertEquals(
                ProbeOutcome.Restricted(
                    RestrictionReason.RATE_LIMITED,
                    fixedNow.plusSeconds(120),
                ),
                completed.outcome,
            )
            assertEquals(HttpObservation(503), completed.observations.http)
        }
    }

    @Test
    fun tooManyRequestsAcceptsHttpDateRetryAfter() {
        val retryAt = Instant.parse("2026-11-28T12:30:00Z")
        LocalHttpServer { _ ->
            HttpResponseSpec(
                status = 429,
                headers = mapOf("Retry-After" to "Sat, 28 Nov 2026 12:30:00 GMT"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/")),
            )

            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, retryAt),
                completed(result).outcome,
            )
        }
    }

    @Test
    fun hugeRetryAfterIsIgnoredInsteadOfFailingTheRun() {
        LocalHttpServer { _ ->
            HttpResponseSpec(
                status = 503,
                headers = mapOf("Retry-After" to "9223372036854775807"),
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/")),
            )

            assertNull(server.failure())
            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, null),
                completed(result).outcome,
            )
        }
    }

    @Test
    fun forbiddenIsAccessDenied() {
        LocalHttpServer { _ -> HttpResponseSpec(status = 403) }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/")),
            )

            assertEquals(
                ProbeOutcome.Restricted(RestrictionReason.ACCESS_DENIED),
                completed(result).outcome,
            )
        }
    }

    @Test
    fun timeoutIsReportedAsTransportTimeout() {
        LocalHttpServer { _ ->
            HttpResponseSpec(status = 200, body = "late".toByteArray(), delayBeforeHeadersMillis = 500)
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/")),
                timeout = 100.milliseconds,
            )

            failed(result, FailureReason.TIMEOUT)
            assertTrue(completed(result).timing.elapsed < 1.seconds)
        }
    }

    @Test
    fun untrustedTlsCertificateFailsAsTls() {
        LocalHttpServer(https = true) { _ ->
            HttpResponseSpec(status = 200, body = "secure".toByteArray())
        }.use { server ->
            val result = runProbe(
                runner = HttpProbeRunner(OkHttpClient(), clock),
                request = httpRequest(server.url("/")),
            )

            failed(result, FailureReason.TLS)
        }
    }

    @Test
    fun trustedTlsCertificateMatchesAndRecordsTlsPhase() {
        LocalHttpServer(https = true) { _ ->
            HttpResponseSpec(status = 200, body = "secure".toByteArray())
        }.use { server ->
            val result = runProbe(
                runner = runner(trustTestTls = true),
                request = httpRequest(server.url("/")),
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertNotNull(completed.timing.phases[ProbePhase.TLS_HANDSHAKE])
        }
    }

    @Test
    fun ipv6LiteralIsSupportedWithoutDns() {
        if (!ipv6LoopbackAvailable()) return
        LocalHttpServer(bindAddress = "::1") { _ ->
            HttpResponseSpec(status = 200, body = "v6".toByteArray())
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/"), family = IpFamily.IPV6),
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertNull(completed(result).timing.phases[ProbePhase.DNS])
        }
    }

    @Test
    fun dnsFailureIsSeparateFromTcpFailure() {
        val failingDns = Dns { throw UnknownHostException("unresolvable.test") }
        val result = runProbe(
            runner = runner(dns = failingDns),
            request = httpRequest("http://unresolvable.test/"),
        )

        failed(result, FailureReason.DNS)
        assertNull(completed(result).timing.phases[ProbePhase.TCP_CONNECT])
    }

    @Test
    fun reusedConnectionDoesNotInventDnsTcpOrTlsPhases() {
        LocalHttpServer { _ ->
            HttpResponseSpec(status = 200, body = "reuse".toByteArray())
        }.use { server ->
            val runner = runner()
            val first = completed(
                runProbe(runner = runner, request = httpRequest(server.url("/"))),
            )
            val second = completed(
                runProbe(runner = runner, request = httpRequest(server.url("/"))),
            )

            assertNotNull(first.timing.phases[ProbePhase.TCP_CONNECT])
            assertNull(second.timing.phases[ProbePhase.TCP_CONNECT])
            assertNull(second.timing.phases[ProbePhase.DNS])
            assertNull(second.timing.phases[ProbePhase.TLS_HANDSHAKE])
        }
    }

    @Test
    fun slowResponseBodyIsStillBoundedByTheTimeout() {
        LocalHttpServer { _ ->
            HttpResponseSpec(
                status = 200,
                body = "slow-body".toByteArray(),
                bodyChunkMillis = 200,
                bodyChunkSize = 1,
            )
        }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/"), bodyMarker = "body"),
                timeout = 100.milliseconds,
            )

            failed(result, FailureReason.TIMEOUT)
            assertTrue(completed(result).timing.elapsed < 1.seconds)
        }
    }

    @Test
    fun redirectLoopStopsAfterTheConfiguredLimit() {
        LocalHttpServer { _ ->
            HttpResponseSpec(status = 302, headers = mapOf("Location" to "/loop"))
        }.use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/loop")),
                admission = admission,
            )

            failed(result, FailureReason.HTTP)
            assertEquals(4, server.calls().size)
            assertEquals(4, admission.attempts.size)
        }
    }

    @Test
    fun familyFilterMakesAMismatchedDnsResultADnsFailure() {
        val ipv6Only = Dns { listOf(InetAddress.getByName("::1")) }
        val result = runProbe(
            runner = runner(dns = ipv6Only),
            request = httpRequest("http://family.test/", family = IpFamily.IPV4),
        )

        failed(result, FailureReason.DNS)
    }

    @Test
    fun externalCancellationPropagatesInsteadOfBecomingATimeoutResult() {
        LocalHttpServer { _ ->
            HttpResponseSpec(status = 200, body = "late".toByteArray(), delayBeforeHeadersMillis = 2_000)
        }.use { server ->
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(100.milliseconds) {
                        runner().run(
                            probeRunRequest(httpRequest(server.url("/")), 5.seconds),
                            RecordingAdmission(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun bodyMarkerBeyondTheLimitIsNotClaimedAsFound() {
        val marker = "after-limit"
        val body = ByteArray(HttpProbeRunner.MAX_BODY_BYTES + 1) { 'a'.code.toByte() } + marker.toByteArray()
        LocalHttpServer { _ -> HttpResponseSpec(status = 200, body = body) }.use { server ->
            val result = runProbe(
                runner = runner(),
                request = httpRequest(server.url("/"), bodyMarker = marker),
            )

            assertEquals(HttpObservation(200, false), completed(result).observations.http)
            failed(result, FailureReason.UNEXPECTED_RESPONSE)
        }
    }

    private fun runner(
        trustTestTls: Boolean = false,
        dns: Dns? = null,
    ): HttpProbeRunner {
        val builder = OkHttpClient.Builder()
        if (trustTestTls) {
            builder.sslSocketFactory(TestTls.clientContext.socketFactory, TestTls.clientTrustManager)
        }
        if (dns != null) builder.dns(dns)
        return HttpProbeRunner(builder.build(), clock)
    }

    private fun runProbe(
        runner: HttpProbeRunner,
        request: ProbeRequest,
        timeout: Duration = 3.seconds,
        admission: ProbeAdmission = RecordingAdmission(),
    ): ProbeResult = runBlocking {
        runner.run(probeRunRequest(request, timeout), admission)
    }

    private fun httpRequest(
        url: String,
        method: HttpMethod = HttpMethod.GET,
        expected: IntRange = 200..299,
        bodyMarker: String? = null,
        family: IpFamily? = null,
    ): ProbeRequest.Http = ProbeRequest.Http(
        url = URI(url),
        method = method,
        expected = HttpExpectation(expected, bodyMarker),
        family = family,
    )

    private fun probeRunRequest(request: ProbeRequest, timeout: Duration): ProbeRunRequest =
        ProbeRunRequest(
            config = ProbeConfig(ProbeId("test-http"), "Test HTTP probe", request, timeout),
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
