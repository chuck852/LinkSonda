package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
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
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.domain.TlsVersion
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SocketProbeRunnerTest {
    @Test
    fun tcpConnectToLocalServerReportsMatchAndPhases() {
        TcpServer().use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TcpConnect(SocketEndpoint("127.0.0.1", server.port)),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertNull(completed.timing.phases[ProbePhase.DNS])
            val connect = requireNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
            assertTrue(connect <= completed.timing.elapsed)
            assertEquals(1, admission.attempts.size)
            assertTrue(admission.attempts.single().endpointKey.startsWith("tcp:"))
            assertTrue(server.awaitFinished())
            assertNull(server.failure())
        }
    }

    @Test
    fun hostnameConnectAuthorizesDnsThenTcpAndUsesIpv4() {
        assumeTrue(InetAddress.getAllByName("localhost").any { it is Inet4Address })

        TcpServer().use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TcpConnect(SocketEndpoint("localhost", server.port, IpFamily.IPV4)),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertNotNull(completed.timing.phases[ProbePhase.DNS])
            assertEquals(2, admission.attempts.size)
            assertTrue(admission.attempts[0].endpointKey.startsWith("dns:"))
            assertTrue(admission.attempts[1].endpointKey.startsWith("tcp:"))
        }
    }

    @Test
    fun dnsFailureIsReportedSeparatelyFromTcpFailure() {
        val admission = RecordingAdmission()
        val resolver = HostResolver { _, _ -> throw UnknownHostException("controlled test failure") }

        val result = runProbe(
            runner = runner(hostResolver = resolver),
            request = ProbeRequest.TcpConnect(SocketEndpoint("missing.test", 80)),
            admission = admission,
        )

        val completed = failed(result, FailureReason.DNS)
        assertNotNull(completed.timing.phases[ProbePhase.DNS])
        assertNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
        assertEquals(1, admission.attempts.size)
        assertTrue(admission.attempts.single().endpointKey.startsWith("dns:"))
    }

    @Test
    fun refusedConnectionIsReportedAsRefused() {
        val port = unusedLocalPort()

        val result = runProbe(
            runner = runner(),
            request = ProbeRequest.TcpConnect(SocketEndpoint("127.0.0.1", port)),
        )

        val completed = failed(result, FailureReason.REFUSED)
        assertNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
    }

    @Test
    fun deniedDnsAdmissionSkipsWithoutOpeningASocket() {
        TcpServer().use { server ->
            val retryAt = Instant.parse("2026-11-27T20:00:00Z")
            val admission = RecordingAdmission(
                listOf(AdmissionDecision.Denied(SkipReason.COOLDOWN, retryAt)),
            )

            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TcpConnect(SocketEndpoint("localhost", server.port, IpFamily.IPV4)),
                admission = admission,
            )

            val skipped = skipped(result, SkipReason.COOLDOWN)
            assertEquals(retryAt, skipped.nextAllowedAt)
            assertFalse(server.awaitAccepted(300))
            assertEquals(1, admission.attempts.size)
            assertTrue(admission.attempts.single().endpointKey.startsWith("dns:"))
        }
    }

    @Test
    fun deniedSocketAdmissionSkipsAfterDnsAndBeforeConnecting() {
        TcpServer().use { server ->
            val admission = RecordingAdmission(
                listOf(AdmissionDecision.Allowed, AdmissionDecision.Denied(SkipReason.BUDGET)),
            )

            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TcpConnect(SocketEndpoint("localhost", server.port, IpFamily.IPV4)),
                admission = admission,
            )

            skipped(result, SkipReason.BUDGET)
            assertFalse(server.awaitAccepted(300))
            assertEquals(2, admission.attempts.size)
            assertTrue(admission.attempts[0].endpointKey.startsWith("dns:"))
            assertTrue(admission.attempts[1].endpointKey.startsWith("tcp:"))
        }
    }

    @Test
    fun tlsHandshakeUsesSniAndReportsNegotiatedVersion() {
        TlsServer().use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = "localhost",
                    minimumVersion = TlsVersion.TLS_1_2,
                ),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            val tls = requireNotNull(completed.observations.tls)
            assertTrue(tls.negotiatedVersion == TlsVersion.TLS_1_2 || tls.negotiatedVersion == TlsVersion.TLS_1_3)
            assertNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
            assertNotNull(completed.timing.phases[ProbePhase.TLS_HANDSHAKE])
            assertEquals(1, admission.attempts.size)
            assertTrue(admission.attempts.single().endpointKey.startsWith("tls:"))
            assertTrue(server.awaitHandshake())
            assertEquals("localhost", server.sni.get())
            assertNull(server.handshakeFailure())
        }
    }

    @Test
    fun tlsRejectsUntrustedCertificate() {
        TlsServer().use { server ->
            val result = runProbe(
                runner = SocketProbeRunner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = "localhost",
                ),
            )

            val completed = failed(result, FailureReason.TLS)
            assertNotNull(completed.timing.phases[ProbePhase.TLS_HANDSHAKE])
            assertTrue(server.awaitFinished())
        }
    }

    @Test
    fun tlsRejectsHostnameMismatch() {
        TlsServer().use { server ->
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = "wrong.example",
                ),
            )

            failed(result, FailureReason.TLS)
        }
    }

    @Test
    fun tlsWithoutServerNameVerifiesIpAndSendsNoSni() {
        TlsServer().use { server ->
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = null,
                ),
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertTrue(server.awaitHandshake())
            assertNull(server.sni.get())
        }
    }

    @Test
    fun tlsReportsTls12WhenItIsNegotiated() {
        TlsServer(enabledProtocols = arrayOf("TLSv1.2")).use { server ->
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = "localhost",
                    minimumVersion = TlsVersion.TLS_1_2,
                ),
            )

            val completed = completed(result)
            assertEquals(TlsVersion.TLS_1_2, completed.observations.tls?.negotiatedVersion)
        }
    }

    @Test
    fun tlsMinimumVersionIsEnforced() {
        TlsServer(enabledProtocols = arrayOf("TLSv1.2")).use { server ->
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = "localhost",
                    minimumVersion = TlsVersion.TLS_1_3,
                ),
            )

            failed(result, FailureReason.TLS)
        }
    }

    @Test
    fun tlsHandshakeTimeoutIsMeasuredAndReleasesTheSocket() {
        TcpServer(onAccepted = { socket ->
            socket.soTimeout = 5_000
            val buffer = ByteArray(1024)
            while (socket.getInputStream().read(buffer) != -1) {
                // Consume the ClientHello and wait for the client to close.
            }
        }).use { server ->
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = "localhost",
                ),
                timeout = 250.milliseconds,
            )

            val completed = failed(result, FailureReason.TIMEOUT)
            assertTrue(completed.timing.elapsed < 2.seconds)
            assertTrue(server.awaitFinished())
        }
    }

    @Test
    fun cancellationClosesTheSocketAndPropagates() {
        val clientHelloReceived = CountDownLatch(1)
        TcpServer(onAccepted = { socket ->
            socket.soTimeout = 30_000
            val buffer = ByteArray(1024)
            val firstRead = socket.getInputStream().read(buffer)
            if (firstRead >= 0) clientHelloReceived.countDown()
            while (socket.getInputStream().read(buffer) != -1) {
                // Wait for cancellation-driven close.
            }
        }).use { server ->
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    runner().run(
                        probeRunRequest(
                            ProbeRequest.TlsHandshake(
                                endpoint = SocketEndpoint("127.0.0.1", server.port),
                                serverName = "localhost",
                            ),
                            timeout = 10.seconds,
                        ),
                        RecordingAdmission(),
                    )
                }

                assertTrue(server.awaitAccepted())
                assertTrue(clientHelloReceived.await(3, TimeUnit.SECONDS))
                withTimeout(1.seconds) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
            assertTrue(server.awaitFinished())
        }
    }

    @Test
    fun ipv6LiteralConnectsWhenLoopbackIsAvailable() {
        assumeTrue(ipv6LoopbackAvailable())

        TcpServer(bindAddress = "::1").use { server ->
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TcpConnect(SocketEndpoint("[::1]", server.port, IpFamily.IPV6)),
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV6, completed.observations.connectedIpFamily)
        }
    }

    @Test
    fun unsupportedRequestIsSkippedWithoutAdmission() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner(),
            request = ProbeRequest.Http(
                url = URI("http://127.0.0.1/"),
                method = HttpMethod.GET,
                expected = HttpExpectation(200..299),
            ),
            admission = admission,
        )

        skipped(result, SkipReason.UNSUPPORTED)
        assertTrue(admission.attempts.isEmpty())
    }

    private fun runner(
        hostResolver: HostResolver = SystemHostResolver(),
        sslContext: SSLContext = TestTls.clientContext,
    ): SocketProbeRunner = SocketProbeRunner(hostResolver = hostResolver, sslContext = sslContext)

    private fun runProbe(
        runner: SocketProbeRunner,
        request: ProbeRequest,
        timeout: Duration = 3.seconds,
        admission: ProbeAdmission = RecordingAdmission(),
    ): ProbeResult = runBlocking {
        runner.run(probeRunRequest(request, timeout), admission)
    }

    private fun probeRunRequest(request: ProbeRequest, timeout: Duration): ProbeRunRequest =
        ProbeRunRequest(
            config = ProbeConfig(ProbeId("test-probe"), "Test probe", request, timeout),
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
