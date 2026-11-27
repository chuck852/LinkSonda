package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsObservation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.DnsTransport
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
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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

class DnsProbeRunnerTest {
    @Test
    fun systemDnsCountsMatchingAAnswersAndRecordsOnlyObservedFacts() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner { _, _ ->
                listOf(loopbackV4(), loopbackV6())
            },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("host.test", A),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
            admission = admission,
        )

        val completed = completed(result)
        assertEquals(ProbeOutcome.Matched, completed.outcome)
        val dns = requireNotNull(completed.observations.dns)
        assertNull(dns.responseCode)
        assertNull(dns.truncated)
        assertEquals(1, dns.answerCount)
        assertNotNull(completed.timing.phases[ProbePhase.DNS])
        assertNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
        assertEquals(listOf(ProbeAttempt("host.test", "system-dns")), admission.attempts)
    }

    @Test
    fun systemDnsCountsMatchingAaaaAnswers() {
        val result = runProbe(
            runner = runner { _, _ -> listOf(loopbackV4(), loopbackV6()) },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("host.test", AAAA),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
        )

        val dns = requireNotNull(completed(result).observations.dns)
        assertEquals(1, dns.answerCount)
    }

    @Test
    fun systemDnsUnknownHostFailsWithoutInventingRcode() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner { _, _ -> throw UnknownHostException("missing.test") },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("missing.test", A),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
            admission = admission,
        )

        val completed = completed(result)
        assertEquals(ProbeOutcome.Failed(FailureReason.DNS), completed.outcome)
        assertNull(completed.observations.dns)
        assertNotNull(completed.timing.phases[ProbePhase.DNS])
        assertEquals(1, admission.attempts.size)
    }

    @Test
    fun systemDnsEmptyResultFailsWhenAnAnswerIsRequired() {
        val result = runProbe(
            runner = runner { _, _ -> emptyList() },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("empty.test", A),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
        )

        assertEquals(ProbeOutcome.Failed(FailureReason.DNS), completed(result).outcome)
        assertNull(completed(result).observations.dns)
    }

    @Test
    fun systemDnsEmptyResultFailsEvenWhenAnAnswerIsNotRequired() {
        val result = runProbe(
            runner = runner { _, _ -> emptyList() },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("empty.test", A),
                expected = DnsExpectation(0, requiresAnswer = false),
            ),
        )

        assertEquals(ProbeOutcome.Failed(FailureReason.DNS), completed(result).outcome)
    }

    @Test
    fun systemDnsUnsupportedRecordTypeSkipsWithoutAdmissionOrResolution() {
        val admission = RecordingAdmission()
        val calls = AtomicInteger()
        val result = runProbe(
            runner = runner { _, _ ->
                calls.incrementAndGet()
                listOf(loopbackV4())
            },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("host.test", 16),
                expected = DnsExpectation(0, requiresAnswer = false),
            ),
            admission = admission,
        )

        skipped(result, SkipReason.UNSUPPORTED)
        assertTrue(admission.attempts.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun systemDnsNonZeroExpectedRcodeSkipsWithoutAdmission() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner { _, _ -> listOf(loopbackV4()) },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("host.test", A),
                expected = DnsExpectation(3, requiresAnswer = false),
            ),
            admission = admission,
        )

        skipped(result, SkipReason.UNSUPPORTED)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun systemDnsAdmissionDenialSkipsWithoutResolution() {
        val admission = RecordingAdmission(listOf(AdmissionDecision.Denied(SkipReason.COOLDOWN)))
        val calls = AtomicInteger()
        val result = runProbe(
            runner = runner { _, _ ->
                calls.incrementAndGet()
                listOf(loopbackV4())
            },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("host.test", A),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
            admission = admission,
        )

        skipped(result, SkipReason.COOLDOWN)
        assertEquals(0, calls.get())
    }

    @Test
    fun systemDnsTimeoutIsReportedAndKeepsTheStartedPhase() {
        val executor = boundedDnsExecutor(workers = 1, queueCapacity = 1)
        try {
            val result = runProbe(
                runner = runner(
                    hostResolver = { _, _ ->
                        Thread.sleep(2_000)
                        listOf(loopbackV4())
                    },
                    dnsExecutor = executor,
                ),
                request = ProbeRequest.SystemDns(
                    question = DnsQuestion("slow.test", A),
                    expected = DnsExpectation(0, requiresAnswer = true),
                ),
                timeout = 100.milliseconds,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Failed(FailureReason.TIMEOUT), completed.outcome)
            assertTrue(completed.timing.elapsed < 1.seconds)
            assertNotNull(completed.timing.phases[ProbePhase.DNS])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun systemDnsExternalCancellationPropagates() {
        val executor = boundedDnsExecutor(workers = 1, queueCapacity = 1)
        try {
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(100.milliseconds) {
                        runner(
                            hostResolver = { _, _ ->
                                Thread.sleep(2_000)
                                listOf(loopbackV4())
                            },
                            dnsExecutor = executor,
                        ).run(
                            probeRunRequest(
                                ProbeRequest.SystemDns(
                                    question = DnsQuestion("slow.test", A),
                                    expected = DnsExpectation(0, requiresAnswer = true),
                                ),
                                timeout = 5.seconds,
                            ),
                            RecordingAdmission(),
                        )
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun systemDnsPermissionFailureIsInconclusive() {
        val result = runProbe(
            runner = runner { _, _ -> throw SecurityException("denied") },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("host.test", A),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
        )

        val completed = completed(result)
        assertEquals(
            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
            completed.outcome,
        )
        assertNull(completed.observations.dns)
    }

    @Test
    fun systemDnsCapacityExhaustionIsInconclusive() {
        val executor = boundedDnsExecutor(workers = 1, queueCapacity = 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = runner(
            hostResolver = { _, _ ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                listOf(loopbackV4())
            },
            dnsExecutor = executor,
        )
        val request = ProbeRequest.SystemDns(
            question = DnsQuestion("slow.test", A),
            expected = DnsExpectation(0, requiresAnswer = true),
        )
        try {
            runBlocking {
                val first = launch(Dispatchers.Default) {
                    runner.run(probeRunRequest(request, 5.seconds), RecordingAdmission())
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val second = launch(Dispatchers.Default) {
                    runner.run(probeRunRequest(request, 5.seconds), RecordingAdmission())
                }
                while (executor.queue.isEmpty()) yield()

                val third = completed(runProbe(runner, request, timeout = 5.seconds))
                assertEquals(
                    ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION),
                    third.outcome,
                )

                release.countDown()
                first.join()
                second.join()
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun directUdpAdmissionDenialDoesNotOpenSocket() {
        UdpDnsServer(handler = { query -> dnsResponse(query) }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.UDP),
                admission = RecordingAdmission(
                    listOf(AdmissionDecision.Denied(SkipReason.COOLDOWN)),
                ),
            )

            skipped(result, SkipReason.COOLDOWN)
            assertFalse(server.awaitReceived(200))
        }
    }

    @Test
    fun directDnsAdmissionKeysIgnorePortTransportAndQuestionType() {
        TcpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aaaaRecord())
        }).use { tcp ->
            UdpDnsServer(handler = { query ->
                dnsResponse(query, answerCount = 1, answerBytes = aRecord())
            }).use { udp ->
                val admission = RecordingAdmission()
                val udpResult = runProbe(
                    runner = runner { _, _ -> error("unexpected resolver call") },
                    request = directRequest(
                        udp.port,
                        DnsTransport.UDP,
                        questionName = "DIRECT.TEST.",
                    ),
                    admission = admission,
                )
                val tcpResult = runProbe(
                    runner = runner { _, _ -> error("unexpected resolver call") },
                    request = directRequest(
                        tcp.port,
                        DnsTransport.TCP,
                        questionName = "direct.test",
                        recordType = AAAA,
                    ),
                    admission = admission,
                )

                assertEquals(ProbeOutcome.Matched, completed(udpResult).outcome)
                assertEquals(ProbeOutcome.Matched, completed(tcpResult).outcome)
                assertEquals(
                    listOf(
                        ProbeAttempt("direct.test", "127.0.0.1"),
                        ProbeAttempt("direct.test", "127.0.0.1"),
                    ),
                    admission.attempts,
                )
            }
        }
    }

    @Test
    fun directUdpSuccessRecordsObservedWireFacts() {
        UdpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }).use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner { _, _ -> error("IP literal must not be resolved") },
                request = directRequest(server.port, DnsTransport.UDP),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(DnsObservation(0, 1, false), completed.observations.dns)
            assertNotNull(completed.timing.phases[ProbePhase.DNS])
            assertNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
            assertEquals(listOf(ProbeAttempt("direct.test", "127.0.0.1")), admission.attempts)
            assertTrue(server.awaitReceived())
            assertNull(server.failure())
        }
    }

    @Test
    fun directUdpSendWithoutAResponseIsATimeoutNotSuccess() {
        UdpDnsServer(handler = { null }).use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.UDP),
                timeout = 100.milliseconds,
                admission = admission,
            )

            failed(result, FailureReason.TIMEOUT)
            assertNull(completed(result).observations.dns)
            assertEquals(1, admission.attempts.size)
            assertTrue(server.awaitReceived())
        }
    }

    @Test
    fun directUdpNxdomainIsRecordedAndMatchesTheExpectedCode() {
        UdpDnsServer(handler = { query -> dnsResponse(query, rcode = 3) }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(
                    server.port,
                    DnsTransport.UDP,
                    expected = DnsExpectation(3, requiresAnswer = false),
                ),
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(DnsObservation(3, 0, false), completed.observations.dns)
        }
    }

    @Test
    fun directUdpServfailMismatchIsADnsResponseCodeFailure() {
        UdpDnsServer(handler = { query -> dnsResponse(query, rcode = 2) }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.UDP),
            )

            failed(result, FailureReason.UNEXPECTED_DNS_RESPONSE_CODE)
            assertEquals(DnsObservation(2, 0, false), completed(result).observations.dns)
        }
    }

    @Test
    fun directUdpMissingRequiredAnswerIsUnexpectedResponse() {
        UdpDnsServer(handler = { query -> dnsResponse(query, answerCount = 0) }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.UDP),
            )

            failed(result, FailureReason.UNEXPECTED_RESPONSE)
            assertEquals(DnsObservation(0, 0, false), completed(result).observations.dns)
        }
    }

    @Test
    fun directUdpWrongTransactionIdIsMalformed() {
        UdpDnsServer(handler = { query ->
            dnsResponse(query).also { it[1] = (it[1].toInt() xor 1).toByte() }
        }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.UDP),
            )

            failed(result, FailureReason.UNEXPECTED_RESPONSE)
            assertNull(completed(result).observations.dns)
        }
    }

    @Test
    fun directUdpMalformedResponseIsUnexpectedResponse() {
        UdpDnsServer(handler = { query -> query.copyOf(10) }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.UDP),
            )

            failed(result, FailureReason.UNEXPECTED_RESPONSE)
            assertNull(completed(result).observations.dns)
        }
    }

    @Test
    fun directUdpRetriesTheNextAddressAfterAMalformedResponse() {
        val malformed = UdpDnsServer(
            bindAddress = "127.0.0.2",
            handler = { query -> query.copyOf(10) },
        )
        malformed.use {
            UdpDnsServer(
                bindAddress = "127.0.0.1",
                port = malformed.port,
                handler = { query -> dnsResponse(query, answerCount = 1, answerBytes = aRecord()) },
            ).use { server ->
                val result = runProbe(
                    runner = runner { host, family ->
                        assertEquals("resolver.test", host)
                        assertNull(family)
                        listOf(InetAddress.getByName("127.0.0.2"), loopbackV4())
                    },
                    request = directRequest(
                        server.port,
                        DnsTransport.UDP,
                        resolverHost = "resolver.test",
                    ),
                )

                assertEquals(ProbeOutcome.Matched, completed(result).outcome)
                assertTrue(malformed.awaitReceived())
                assertTrue(server.awaitReceived())
            }
        }
    }

    @Test
    fun directUdpCnameOnlyDoesNotSatisfyARequiredAddressAnswer() {
        UdpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = cnameRecord())
        }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.UDP),
            )

            failed(result, FailureReason.UNEXPECTED_RESPONSE)
            assertEquals(DnsObservation(0, 1, false), completed(result).observations.dns)
        }
    }

    @Test
    fun directUdpTruncationFallsBackToTcpWithASeparateAdmission() {
        TcpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }).use { tcp ->
            UdpDnsServer(port = tcp.port, handler = { query ->
                dnsResponse(query, truncated = true)
            }).use { udp ->
                val admission = RecordingAdmission()
                val result = runProbe(
                    runner = runner { _, _ -> error("unexpected resolver call") },
                    request = directRequest(udp.port, DnsTransport.UDP),
                    admission = admission,
                )

                val completed = completed(result)
                assertEquals(ProbeOutcome.Matched, completed.outcome)
                assertEquals(DnsObservation(0, 1, true), completed.observations.dns)
                assertNotNull(completed.timing.phases[ProbePhase.DNS])
                assertNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
                assertEquals(
                    listOf(
                        ProbeAttempt("direct.test", "127.0.0.1"),
                        ProbeAttempt("direct.test", "127.0.0.1"),
                    ),
                    admission.attempts,
                )
                assertTrue(tcp.awaitReceived())
                assertNull(tcp.failure())
                assertNull(udp.failure())
            }
        }
    }

    @Test
    fun directUdpPartialTruncatedSectionStillFallsBackToTcp() {
        TcpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }).use { tcp ->
            val partialRecord = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
            UdpDnsServer(port = tcp.port, handler = { query ->
                dnsResponse(
                    query,
                    truncated = true,
                    answerCount = 1,
                    answerBytes = partialRecord,
                )
            }).use { udp ->
                val result = runProbe(
                    runner = runner { _, _ -> error("unexpected resolver call") },
                    request = directRequest(udp.port, DnsTransport.UDP),
                )

                assertEquals(ProbeOutcome.Matched, completed(result).outcome)
                assertEquals(DnsObservation(0, 1, true), completed(result).observations.dns)
                assertTrue(tcp.awaitReceived())
            }
        }
    }

    @Test
    fun directUdpTruncationKeepsUdpFactsWhenTcpFails() {
        TcpDnsServer(handler = { throw IOException("no TCP answer") }).use { tcp ->
            UdpDnsServer(port = tcp.port, handler = { query ->
                dnsResponse(query, truncated = true, answerCount = 1, answerBytes = aRecord())
            }).use { udp ->
                val result = runProbe(
                    runner = runner { _, _ -> error("unexpected resolver call") },
                    request = directRequest(udp.port, DnsTransport.UDP),
                )

                failed(result, FailureReason.DNS)
                assertEquals(
                    DnsObservation(0, 1, true),
                    completed(result).observations.dns,
                )
                assertTrue(tcp.awaitAccepted())
            }
        }
    }

    @Test
    fun directUdpTruncationDoesNotSucceedWhenFallbackIsDenied() {
        TcpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }).use { tcp ->
            UdpDnsServer(port = tcp.port, handler = { query ->
                dnsResponse(query, truncated = true)
            }).use { udp ->
                val admission = RecordingAdmission(
                    listOf(
                        AdmissionDecision.Allowed,
                        AdmissionDecision.Denied(SkipReason.COOLDOWN),
                    ),
                )
                val result = runProbe(
                    runner = runner { _, _ -> error("unexpected resolver call") },
                    request = directRequest(udp.port, DnsTransport.UDP),
                    admission = admission,
                )

                skipped(result, SkipReason.COOLDOWN)
                assertEquals(2, admission.attempts.size)
                assertFalse(tcp.awaitAccepted(200))
            }
        }
    }

    @Test
    fun directTcpSuccessRecordsConnectAndWireFacts() {
        TcpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }).use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.TCP),
                admission = admission,
            )

            val completed = completed(result)
            assertEquals(ProbeOutcome.Matched, completed.outcome)
            assertEquals(DnsObservation(0, 1, false), completed.observations.dns)
            assertNotNull(completed.timing.phases[ProbePhase.DNS])
            assertNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
            assertEquals(listOf(ProbeAttempt("direct.test", "127.0.0.1")), admission.attempts)
            assertTrue(server.awaitReceived())
            assertNull(server.failure())
        }
    }

    @Test
    fun directTcpTimeoutKeepsConnectAndDnsPhases() {
        TcpDnsServer(handler = { null }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.TCP),
                timeout = 100.milliseconds,
            )

            val completed = failed(result, FailureReason.TIMEOUT)
            assertNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
            assertNotNull(completed.timing.phases[ProbePhase.DNS])
            assertTrue(server.awaitReceived())
        }
    }

    @Test
    fun directTcpMalformedResponseIsUnexpectedResponse() {
        TcpDnsServer(handler = { query -> query.copyOf(10) }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.TCP),
            )

            val completed = failed(result, FailureReason.UNEXPECTED_RESPONSE)
            assertNotNull(completed.timing.phases[ProbePhase.TCP_CONNECT])
            assertNotNull(completed.timing.phases[ProbePhase.DNS])
            assertTrue(server.awaitReceived())
        }
    }

    @Test
    fun directTcpCancellationPropagates() {
        TcpDnsServer(handler = { null }).use { server ->
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(100.milliseconds) {
                        runner { _, _ -> error("unexpected resolver call") }.run(
                            probeRunRequest(directRequest(server.port, DnsTransport.TCP), 5.seconds),
                            RecordingAdmission(),
                        )
                    }
                }
            }
            assertTrue(server.awaitAccepted())
        }
    }

    @Test
    fun directTcpWorksOverIpv6Loopback() {
        assumeTrue(ipv6LoopbackAvailable())
        TcpDnsServer(
            bindAddress = "::1",
            handler = { query -> dnsResponse(query, answerCount = 1, answerBytes = aaaaRecord()) },
        ).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(
                    server.port,
                    DnsTransport.TCP,
                    resolverHost = "::1",
                    recordType = AAAA,
                ),
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertEquals(DnsObservation(0, 1, false), completed(result).observations.dns)
            assertTrue(server.awaitReceived())
        }
    }

    @Test
    fun directTcpTruncatedResponseIsNotSuccess() {
        TcpDnsServer(handler = { query ->
            dnsResponse(query, truncated = true, answerCount = 1, answerBytes = aRecord())
        }).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(server.port, DnsTransport.TCP),
            )

            failed(result, FailureReason.UNEXPECTED_RESPONSE)
            assertEquals(DnsObservation(0, 1, true), completed(result).observations.dns)
        }
    }

    @Test
    fun invalidDnsQuestionNameFailsWithoutAdmissionOrNetwork() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner { _, _ -> error("unexpected resolver call") },
            request = ProbeRequest.DirectDns(
                resolver = SocketEndpoint("127.0.0.1", 9),
                transport = DnsTransport.UDP,
                question = DnsQuestion("${"a".repeat(64)}.test", A),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
            admission = admission,
        )

        failed(result, FailureReason.DNS)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun overlongNameFailsWithoutAdmissionOrNetwork() {
        val admission = RecordingAdmission()
        val name = List(4) { "a".repeat(63) }.joinToString(".")
        val result = runProbe(
            runner = runner { _, _ -> error("unexpected resolver call") },
            request = directRequest(9, DnsTransport.UDP, questionName = name),
            admission = admission,
        )

        failed(result, FailureReason.DNS)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun systemDnsInvalidQuestionNameFailsWithoutAdmission() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner { _, _ -> error("unexpected resolver call") },
            request = ProbeRequest.SystemDns(
                question = DnsQuestion("${"a".repeat(64)}.test", A),
                expected = DnsExpectation(0, requiresAnswer = true),
            ),
            admission = admission,
        )

        failed(result, FailureReason.DNS)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun directDnsExtendedExpectedRcodeSkipsWithoutAdmission() {
        val admission = RecordingAdmission()
        val result = runProbe(
            runner = runner { _, _ -> error("unexpected resolver call") },
            request = directRequest(
                port = 9,
                transport = DnsTransport.UDP,
                expected = DnsExpectation(16, requiresAnswer = false),
            ),
            admission = admission,
        )

        skipped(result, SkipReason.UNSUPPORTED)
        assertTrue(admission.attempts.isEmpty())
    }

    @Test
    fun directTcpConnectionRefusedIsNotDnsFailure() {
        val port = ServerSocket(0).use { it.localPort }
        val result = runProbe(
            runner = runner { _, _ -> error("unexpected resolver call") },
            request = directRequest(port, DnsTransport.TCP),
            timeout = 1.seconds,
        )

        failed(result, FailureReason.REFUSED)
    }

    @Test
    fun directDnsHostnameResolverIsAuthorizedSeparatelyFromTheQuery() {
        UdpDnsServer(handler = { query ->
            dnsResponse(query, answerCount = 1, answerBytes = aRecord())
        }).use { server ->
            val admission = RecordingAdmission()
            val result = runProbe(
                runner = runner { host, family ->
                    assertEquals("resolver.test", host)
                    assertNull(family)
                    listOf(loopbackV4())
                },
                request = directRequest(server.port, DnsTransport.UDP, resolverHost = "resolver.test"),
                admission = admission,
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertEquals(
                listOf(
                    ProbeAttempt("resolver.test", "system-dns"),
                    ProbeAttempt("direct.test", "resolver.test"),
                ),
                admission.attempts,
            )
        }
    }

    @Test
    fun directUdpWorksOverIpv6Loopback() {
        assumeTrue(ipv6LoopbackAvailable())
        UdpDnsServer(
            bindAddress = "::1",
            handler = { query -> dnsResponse(query, answerCount = 1, answerBytes = aaaaRecord()) },
        ).use { server ->
            val result = runProbe(
                runner = runner { _, _ -> error("unexpected resolver call") },
                request = directRequest(
                    server.port,
                    DnsTransport.UDP,
                    resolverHost = "::1",
                    recordType = AAAA,
                ),
            )

            assertEquals(ProbeOutcome.Matched, completed(result).outcome)
            assertEquals(DnsObservation(0, 1, false), completed(result).observations.dns)
        }
    }

    @Test
    fun directDnsCancellationPropagates() {
        UdpDnsServer(handler = { null }).use { server ->
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withTimeout(100.milliseconds) {
                        runner { _, _ -> error("unexpected resolver call") }.run(
                            probeRunRequest(directRequest(server.port, DnsTransport.UDP), 5.seconds),
                            RecordingAdmission(),
                        )
                    }
                }
            }
        }
    }

    private fun directRequest(
        port: Int,
        transport: DnsTransport,
        resolverHost: String = "127.0.0.1",
        recordType: Int = A,
        questionName: String = "direct.test",
        expected: DnsExpectation = DnsExpectation(0, requiresAnswer = true),
    ): ProbeRequest.DirectDns = ProbeRequest.DirectDns(
        resolver = SocketEndpoint(resolverHost, port),
        transport = transport,
        question = DnsQuestion(questionName, recordType),
        expected = expected,
    )

    private fun runner(
        dnsExecutor: ThreadPoolExecutor? = null,
        hostResolver: (String, IpFamily?) -> List<InetAddress>,
    ): DnsProbeRunner = if (dnsExecutor == null) {
        DnsProbeRunner(hostResolver = HostResolver(hostResolver))
    } else {
        DnsProbeRunner(hostResolver = HostResolver(hostResolver), dnsExecutor = dnsExecutor)
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
        runner: DnsProbeRunner,
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

    private fun loopbackV4(): InetAddress = InetAddress.getByName("127.0.0.1")

    private fun loopbackV6(): InetAddress = InetAddress.getByName("::1")

    private class RecordingAdmission(
        private val decisions: List<AdmissionDecision> = listOf(AdmissionDecision.Allowed),
    ) : ProbeAdmission {
        val attempts = mutableListOf<ProbeAttempt>()

        override suspend fun authorize(attempt: ProbeAttempt): AdmissionDecision {
            attempts += attempt
            return decisions.getOrElse(attempts.lastIndex) { AdmissionDecision.Allowed }
        }
    }

    private companion object {
        const val A = 1
        const val AAAA = 28
    }
}
