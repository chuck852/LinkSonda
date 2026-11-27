package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
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
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.domain.TlsVersion
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
            assertEquals("127.0.0.1", admission.attempts.single().endpointKey)
            assertEquals("127.0.0.1", admission.attempts.single().infrastructureKey)
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
            assertEquals("localhost", admission.attempts[0].endpointKey)
            assertEquals("system-dns", admission.attempts[0].infrastructureKey)
            assertEquals("localhost", admission.attempts[1].endpointKey)
            assertEquals("localhost", admission.attempts[1].infrastructureKey)
        }
    }

    @Test
    fun transportAdmissionKeysUseNormalizedHostOnly() {
        TcpServer().use { tcpServer ->
            TlsServer().use { tlsServer ->
                val resolver = HostResolver { _, _ -> listOf(InetAddress.getByName("127.0.0.1")) }
                val tcpAdmission = RecordingAdmission()
                val tlsAdmission = RecordingAdmission()

                val tcpResult = runProbe(
                    runner = runner(hostResolver = resolver),
                    request = ProbeRequest.TcpConnect(
                        SocketEndpoint("Identity.TEST", tcpServer.port, IpFamily.IPV4),
                    ),
                    admission = tcpAdmission,
                )
                val tlsResult = runProbe(
                    runner = runner(hostResolver = resolver),
                    request = ProbeRequest.TlsHandshake(
                        endpoint = SocketEndpoint("Identity.TEST", tlsServer.port, IpFamily.IPV4),
                        serverName = "localhost",
                    ),
                    admission = tlsAdmission,
                )

                assertEquals(ProbeOutcome.Matched, completed(tcpResult).outcome)
                assertEquals(ProbeOutcome.Matched, completed(tlsResult).outcome)

                val tcpSocketAttempt = tcpAdmission.attempts.single { it.infrastructureKey == "identity.test" }
                val tlsSocketAttempt = tlsAdmission.attempts.single { it.infrastructureKey == "identity.test" }
                assertEquals("identity.test", tcpSocketAttempt.endpointKey)
                assertEquals(tcpSocketAttempt, tlsSocketAttempt)
                assertEquals("identity.test", tcpAdmission.attempts.first().endpointKey)
                assertEquals("system-dns", tcpAdmission.attempts.first().infrastructureKey)
            }
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
        assertEquals("missing.test", admission.attempts.single().endpointKey)
        assertEquals("system-dns", admission.attempts.single().infrastructureKey)
    }

    @Test
    fun dualStackHostnameFallsBackToTheNextAddress() {
        TcpServer().use { server ->
            val resolver = HostResolver { _, _ ->
                listOf(
                    InetAddress.getByName("127.0.0.2"),
                    InetAddress.getByName("127.0.0.1"),
                )
            }
            val admission = RecordingAdmission()

            val result = runProbe(
                runner = runner(hostResolver = resolver),
                request = ProbeRequest.TcpConnect(
                    SocketEndpoint("dual.test", server.port, IpFamily.IPV4),
                ),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
            assertEquals(3, admission.attempts.size)
            assertEquals("dual.test", admission.attempts[0].endpointKey)
            assertEquals("system-dns", admission.attempts[0].infrastructureKey)
            assertEquals("dual.test", admission.attempts[1].endpointKey)
            assertEquals("dual.test", admission.attempts[1].infrastructureKey)
            assertEquals("dual.test", admission.attempts[2].endpointKey)
            assertEquals("dual.test", admission.attempts[2].infrastructureKey)
        }
    }

    @Test
    fun blockingDnsResolutionDoesNotEscapeTheOverallTimeout() {
        val resolver = HostResolver { _, _ ->
            Thread.sleep(2_000)
            emptyList()
        }

        val result = runProbe(
            runner = runner(hostResolver = resolver),
            request = ProbeRequest.TcpConnect(SocketEndpoint("blocked.test", 80)),
            timeout = 100.milliseconds,
        )

        val completed = failed(result, FailureReason.TIMEOUT)
        assertTrue(completed.timing.elapsed < 1.seconds)
    }

    @Test
    fun externalCancellationPropagatesInsteadOfBeingReportedAsTimeout() {
        val resolver = HostResolver { _, _ ->
            Thread.sleep(2_000)
            emptyList()
        }
        var returned: ProbeResult? = null

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(100.milliseconds) {
                    returned = runner(hostResolver = resolver).run(
                        probeRunRequest(
                            ProbeRequest.TcpConnect(SocketEndpoint("external-timeout.test", 80)),
                            5.seconds,
                        ),
                        RecordingAdmission(),
                    )
                }
            }
        }
        assertNull(returned)
    }

    @Test
    fun dnsResolutionIgnoringInterruptionStillHonorsTheProbeTimeout() {
        val executor = boundedDnsExecutor(1, 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolver = HostResolver { _, _ ->
            started.countDown()
            release.await()
            emptyList()
        }
        val runner = runner(hostResolver = resolver, dnsExecutor = executor)

        try {
            runBlocking {
                val result = async(Dispatchers.Default) {
                    runner.run(
                        probeRunRequest(
                            ProbeRequest.TcpConnect(SocketEndpoint("stuck-dns.test", 80)),
                            100.milliseconds,
                        ),
                        RecordingAdmission(),
                    )
                }

                assertTrue(started.await(1, TimeUnit.SECONDS))
                val completed = completed(withTimeout(1.seconds) { result.await() })
                assertEquals(ProbeOutcome.Failed(FailureReason.TIMEOUT), completed.outcome)
                assertTrue(completed.timing.elapsed < 1.seconds)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun dnsExecutorExhaustionIsInconclusive() {
        val executor = boundedDnsExecutor(1, 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolver = HostResolver { _, _ ->
            started.countDown()
            release.await()
            emptyList()
        }
        val runner = runner(hostResolver = resolver, dnsExecutor = executor)

        try {
            runBlocking {
                val first = launch(Dispatchers.Default) {
                    runner.run(
                        probeRunRequest(
                            ProbeRequest.TcpConnect(SocketEndpoint("blocked-dns.test", 80)),
                            10.seconds,
                        ),
                        RecordingAdmission(),
                    )
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))

                val second = launch(Dispatchers.Default) {
                    runner.run(
                        probeRunRequest(
                            ProbeRequest.TcpConnect(SocketEndpoint("queued-dns.test", 80)),
                            10.seconds,
                        ),
                        RecordingAdmission(),
                    )
                }
                withTimeout(1.seconds) {
                    while (executor.queue.isEmpty()) {
                        yield()
                    }
                }

                val result = runner.run(
                    probeRunRequest(
                        ProbeRequest.TcpConnect(SocketEndpoint("rejected-dns.test", 80)),
                        1.seconds,
                    ),
                    RecordingAdmission(),
                )

                val completed = completed(result)
                assertEquals(
                    ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION),
                    completed.outcome,
                )

                release.countDown()
                first.cancelAndJoin()
                second.cancelAndJoin()
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
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
            assertEquals("localhost", admission.attempts.single().endpointKey)
            assertEquals("system-dns", admission.attempts.single().infrastructureKey)
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
            assertEquals("localhost", admission.attempts[0].endpointKey)
            assertEquals("system-dns", admission.attempts[0].infrastructureKey)
            assertEquals("localhost", admission.attempts[1].endpointKey)
            assertEquals("localhost", admission.attempts[1].infrastructureKey)
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
            assertEquals("127.0.0.1", admission.attempts.single().endpointKey)
            assertEquals("127.0.0.1", admission.attempts.single().infrastructureKey)
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
    fun tlsRejectsIpAddressWithoutIpSan() {
        TlsServer(serverContext = TestTls.serverContextWithoutIpSan).use { server ->
            val result = runProbe(
                runner = runner(sslContext = TestTls.clientContextWithoutIpSan),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = null,
                ),
            )

            failed(result, FailureReason.TLS)
            assertTrue(server.awaitFinished())
        }
    }

    @Test
    fun tlsReportsTls13WhenItIsNegotiated() {
        TlsServer().use { server ->
            val result = runProbe(
                runner = runner(),
                request = ProbeRequest.TlsHandshake(
                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                    serverName = "localhost",
                    minimumVersion = TlsVersion.TLS_1_3,
                ),
            )

            val completed = completed(result)
            assertEquals(TlsVersion.TLS_1_3, completed.observations.tls?.negotiatedVersion)
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
            assertEquals(IpFamily.IPV4, completed.observations.connectedIpFamily)
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
                val returned = CompletableDeferred<ProbeResult>()
                val job = launch(Dispatchers.Default) {
                    returned.complete(
                        runner().run(
                            probeRunRequest(
                                ProbeRequest.TlsHandshake(
                                    endpoint = SocketEndpoint("127.0.0.1", server.port),
                                    serverName = "localhost",
                                ),
                                timeout = 10.seconds,
                            ),
                            RecordingAdmission(),
                        ),
                    )
                }

                assertTrue(server.awaitAccepted())
                assertTrue(clientHelloReceived.await(3, TimeUnit.SECONDS))
                withTimeout(1.seconds) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertFalse(returned.isCompleted)
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
    fun permissionFailuresAreInconclusive() {
        val expected = ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)

        assertEquals(expected, classifyConnectFailure(SecurityException("denied")))
        assertEquals(expected, classifyConnectFailure(SocketException("socket failed: EACCES (Permission denied)")))
        assertEquals(expected, classifyTlsFailure(SocketException("socket failed: EPERM (Operation not permitted)")))
    }

    @Test
    fun connectFailuresAreClassified() {
        assertEquals(
            ProbeOutcome.Failed(FailureReason.UNREACHABLE),
            classifyConnectFailure(NoRouteToHostException("No route to host")),
        )
        assertEquals(
            ProbeOutcome.Failed(FailureReason.TIMEOUT),
            classifyConnectFailure(ConnectException("Connection timed out")),
        )
        assertEquals(
            ProbeOutcome.Failed(FailureReason.UNREACHABLE),
            classifyConnectFailure(ConnectException("Network is unreachable")),
        )
        assertEquals(
            ProbeOutcome.Failed(FailureReason.REFUSED),
            classifyConnectFailure(ConnectException("Connection refused")),
        )
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
        dnsExecutor: ThreadPoolExecutor? = null,
    ): SocketProbeRunner = if (dnsExecutor == null) {
        SocketProbeRunner(hostResolver = hostResolver, sslContext = sslContext)
    } else {
        SocketProbeRunner(
            hostResolver = hostResolver,
            sslContext = sslContext,
            dnsExecutor = dnsExecutor,
        )
    }

    private fun boundedDnsExecutor(workers: Int, queueCapacity: Int): ThreadPoolExecutor =
        ThreadPoolExecutor(
            workers,
            workers,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queueCapacity),
        ) { runnable ->
            Thread(runnable, "linksonda-test-dns").apply { isDaemon = true }
        }

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
