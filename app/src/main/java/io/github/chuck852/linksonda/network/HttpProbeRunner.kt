package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.HttpObservation
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeAttempt
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.ProbeRunner
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.RestrictionReason
import io.github.chuck852.linksonda.domain.SkipReason
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * HTTP and HTTPS checks with manual redirects.
 *
 * Each HTTP hop is authorized as one outbound attempt. Redirect targets are separately
 * authorized. DNS is performed by OkHttp inside that attempt, so there is no separate DNS grant.
 * Reused connections naturally produce no DNS/TCP/TLS phases.
 */
internal class HttpProbeRunner internal constructor(
    private val baseClient: OkHttpClient,
    private val clock: Clock,
) : ProbeRunner {
    constructor() : this(OkHttpClient(), Clock.systemUTC())

    override suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult {
        val probe = request.config.request
        if (probe !is ProbeRequest.Http) {
            return skipped(request, SkipReason.UNSUPPORTED)
        }

        val startNanos = System.nanoTime()
        val phases = mutableMapOf<ProbePhase, Duration>()
        val result = withTimeoutOrNull(request.config.timeout) {
            execute(request, probe, admission, startNanos, phases)
        }
        currentCoroutineContext().ensureActive()
        return result ?: completed(
            request = request,
            startNanos = startNanos,
            phases = phases,
            outcome = ProbeOutcome.Failed(FailureReason.TIMEOUT),
        )
    }

    private suspend fun execute(
        request: ProbeRunRequest,
        probe: ProbeRequest.Http,
        admission: ProbeAdmission,
        startNanos: Long,
        phases: MutableMap<ProbePhase, Duration>,
    ): ProbeResult {
        val client = clientFor(probe, phases)
        var current = probe.url
        var redirects = 0

        while (true) {
            val host = normalizeHost(current.host)
                ?: return completed(
                    request,
                    startNanos,
                    phases,
                    ProbeOutcome.Failed(FailureReason.DNS),
                )
            if (probe.family != null && isIpLiteral(host) && !literalMatches(current, probe.family)) {
                return completed(
                    request,
                    startNanos,
                    phases,
                    ProbeOutcome.Failed(FailureReason.DNS),
                )
            }

            val decision = admission.authorize(
                ProbeAttempt(endpointKey = host, infrastructureKey = host),
            )
            if (decision is AdmissionDecision.Denied) {
                return skipped(request, decision)
            }

            val httpRequest = try {
                Request.Builder()
                    .url(current.toString())
                    .method(probe.method.name, null)
                    .build()
            } catch (error: IllegalArgumentException) {
                return completed(
                    request,
                    startNanos,
                    phases,
                    ProbeOutcome.Failed(FailureReason.HTTP),
                )
            }
            val callClient = client.newBuilder()
                .callTimeout(remainingMillis(startNanos, request.config.timeout), TimeUnit.MILLISECONDS)
                .build()
            val call = callClient.newCall(httpRequest)

            val response = try {
                executeCall(call)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val blocked = error.findBlockedResponse()
                if (blocked != null) {
                    return completed(
                        request,
                        startNanos,
                        phases,
                        blocked.outcome(),
                        http = HttpObservation(blocked.status),
                    )
                }
                return completed(
                    request,
                    startNanos,
                    phases,
                    classifyFailure(error),
                )
            }

            response.use { received ->
                val status = received.code
                val restriction = restrictionReason(status)
                if (restriction != null) {
                    return completed(
                        request,
                        startNanos,
                        phases,
                        ProbeOutcome.Restricted(
                            reason = restriction,
                            retryAt = retryAt(received),
                        ),
                        http = HttpObservation(status),
                    )
                }

                if (status in REDIRECT_STATUSES) {
                    val location = received.header("Location")
                    if (location.isNullOrBlank()) {
                        return completed(
                            request,
                            startNanos,
                            phases,
                            ProbeOutcome.Failed(FailureReason.HTTP),
                        )
                    }
                    if (redirects >= MAX_REDIRECTS) {
                        return completed(
                            request,
                            startNanos,
                            phases,
                            ProbeOutcome.Failed(FailureReason.HTTP),
                        )
                    }
                    val next = try {
                        current.resolve(location)
                    } catch (error: IllegalArgumentException) {
                        null
                    }
                    val nextScheme = next?.scheme?.lowercase()
                    val downgrade = current.scheme.equals("https", ignoreCase = true) &&
                        nextScheme == "http"
                    if (next == null || nextScheme !in WEB_SCHEMES ||
                        next.host.isNullOrBlank() || next.userInfo != null || downgrade
                    ) {
                        return completed(
                            request,
                            startNanos,
                            phases,
                            ProbeOutcome.Failed(FailureReason.HTTP),
                        )
                    }
                    current = next
                    redirects += 1
                    return@use
                }

                if (status !in probe.expected.status) {
                    return completed(
                        request,
                        startNanos,
                        phases,
                        ProbeOutcome.Failed(FailureReason.UNEXPECTED_HTTP_STATUS),
                        http = HttpObservation(status),
                    )
                }

                val marker = probe.expected.bodyMarker
                val markerMatched = try {
                    marker?.let { expected ->
                        if (probe.method == HttpMethod.HEAD || status == 204 || status == 304) {
                            false
                        } else {
                            bodyMarkerMatched(received, call, expected)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return completed(
                        request,
                        startNanos,
                        phases,
                        classifyFailure(error),
                        http = HttpObservation(status),
                    )
                }
                val http = HttpObservation(status, markerMatched)
                val outcome = when {
                    markerMatched == false -> ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE)
                    else -> ProbeOutcome.Matched
                }
                return completed(request, startNanos, phases, outcome, http = http)
            }
        }
    }

    private fun clientFor(
        probe: ProbeRequest.Http,
        phases: MutableMap<ProbePhase, Duration>,
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .eventListener(PhaseListener(phases))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code in AUTO_FOLLOW_UP_STATUSES) {
                    val status = response.code
                    val retryAfter = response.header("Retry-After")
                    response.close()
                    throw BlockedResponseException(status, retryAfter)
                }
                response
            }
        if (probe.family != null) {
            val baseDns = baseClient.dns
            builder.dns(Dns { hostname ->
                val matching = baseDns.lookup(hostname).filter { address ->
                    address.family() == probe.family
                }
                if (matching.isEmpty()) throw UnknownHostException(hostname)
                matching
            })
        }
        return builder.build()
    }

    private suspend fun executeCall(call: Call): Response =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    continuation.resumeWith(Result.success(call.execute()))
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private suspend fun bodyMarkerMatched(response: Response, call: Call, marker: String): Boolean =
        withContext(Dispatchers.IO) {
            // shortcut: only the first 64 KiB is searched; scanning the full body risks unbounded reads.
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                    runCatching { response.close() }
                }
                try {
                    val markerBytes = marker.toByteArray(Charsets.UTF_8)
                    if (markerBytes.isEmpty() || markerBytes.size > MAX_BODY_BYTES) {
                        continuation.resumeWith(Result.success(false))
                        return@suspendCancellableCoroutine
                    }
                    val body = ByteArray(MAX_BODY_BYTES)
                    var total = 0
                    val input = response.body.byteStream()
                    while (total < MAX_BODY_BYTES) {
                        val read = input.read(body, total, MAX_BODY_BYTES - total)
                        if (read < 0) break
                        total += read
                    }
                    continuation.resumeWith(Result.success(indexOf(body, total, markerBytes) >= 0))
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        if (needle.size > length) return -1
        for (index in 0..length - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun retryAt(response: Response): Instant? =
        retryAt(response.header("Retry-After"))

    private fun retryAt(header: String?): Instant? {
        val value = header?.trim() ?: return null
        value.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) {
                return try {
                    clock.instant().plusSeconds(seconds)
                } catch (error: Exception) {
                    null
                }
            }
        }
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (error: Exception) {
            null
        }
    }

    private class BlockedResponseException(
        val status: Int,
        val retryAfter: String?,
    ) : IOException("HTTP $status has an automatic follow-up")

    private fun Throwable.findBlockedResponse(): BlockedResponseException? =
        generateSequence(this) { it.cause }.filterIsInstance<BlockedResponseException>().firstOrNull()

    private fun BlockedResponseException.outcome(): ProbeOutcome = when (status) {
        503 -> ProbeOutcome.Restricted(RestrictionReason.RATE_LIMITED, retryAt(retryAfter))
        408 -> ProbeOutcome.Failed(FailureReason.TIMEOUT)
        else -> ProbeOutcome.Failed(FailureReason.HTTP)
    }

    private fun classifyFailure(error: Throwable): ProbeOutcome {
        val chain = generateSequence(error) { it.cause }.toList()
        return when {
            chain.any { it is UnknownHostException } ->
                ProbeOutcome.Failed(FailureReason.DNS)
            chain.any { it is SSLException } ->
                ProbeOutcome.Failed(FailureReason.TLS)
            chain.any { it is SocketTimeoutException || it is InterruptedIOException } ->
                ProbeOutcome.Failed(FailureReason.TIMEOUT)
            chain.any { it.isPermissionDenied() } ->
                ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
            chain.any { it is NoRouteToHostException } ->
                ProbeOutcome.Failed(FailureReason.UNREACHABLE)
            chain.any { it is ConnectException } ->
                ProbeOutcome.Failed(FailureReason.REFUSED)
            chain.any { it is ProtocolException } ->
                ProbeOutcome.Failed(FailureReason.HTTP)
            else -> ProbeOutcome.Failed(FailureReason.TCP)
        }
    }

    private fun completed(
        request: ProbeRunRequest,
        startNanos: Long,
        phases: MutableMap<ProbePhase, Duration>,
        outcome: ProbeOutcome,
        http: HttpObservation? = null,
    ): ProbeResult.Completed {
        val snapshot = synchronized(phases) { phases.toMap() }
        val elapsed = (System.nanoTime() - startNanos).nanoseconds
        return ProbeResult.Completed(
            context = request.context,
            recordedAt = Instant.now(),
            timing = ProbeTiming(
                elapsed = elapsed,
                phases = snapshot,
            ),
            outcome = outcome,
            observations = ProbeObservations(http = http),
        )
    }

    private fun skipped(request: ProbeRunRequest, reason: SkipReason): ProbeResult.Skipped =
        ProbeResult.Skipped(
            context = request.context,
            recordedAt = Instant.now(),
            reason = reason,
        )

    private fun skipped(
        request: ProbeRunRequest,
        decision: AdmissionDecision.Denied,
    ): ProbeResult.Skipped = ProbeResult.Skipped(
        context = request.context,
        recordedAt = Instant.now(),
        reason = decision.reason,
        nextAllowedAt = decision.nextAllowedAt,
    )

    private fun restrictionReason(status: Int): RestrictionReason? = when (status) {
        429, 503 -> RestrictionReason.RATE_LIMITED
        403 -> RestrictionReason.ACCESS_DENIED
        else -> null
    }

    private fun literalMatches(uri: URI, family: IpFamily): Boolean {
        val address = try {
            InetAddress.getByName(normalizeHost(uri.host) ?: return false)
        } catch (error: Exception) {
            return false
        }
        return address.family() == family
    }

    private fun remainingMillis(startNanos: Long, timeout: Duration): Long {
        val remaining = timeout.inWholeNanoseconds -
            (System.nanoTime() - startNanos).coerceAtLeast(0L)
        return (remaining / 1_000_000L).coerceAtLeast(1L)
    }

    private fun normalizeHost(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        val withoutBrackets = if (trimmed.length >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        val normalized = withoutBrackets.removeSuffix(".").lowercase()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun isIpLiteral(value: String): Boolean {
        val ipv4 = IPV4.matches(value) && value.split('.').all { it.toIntOrNull() in 0..255 }
        return ipv4 || value.count { it == ':' } >= 2
    }

    private fun InetAddress.family(): IpFamily =
        if (this is Inet4Address) IpFamily.IPV4 else IpFamily.IPV6

    private fun Throwable.isPermissionDenied(): Boolean =
        this is SecurityException ||
            (this is SocketException && message?.let { text ->
                text.contains("EACCES", ignoreCase = true) ||
                    text.contains("EPERM", ignoreCase = true) ||
                    text.contains("permission denied", ignoreCase = true)
            } == true)

    private class PhaseListener(
        private val phases: MutableMap<ProbePhase, Duration>,
    ) : EventListener() {
        private var dnsStart = 0L
        private var tcpStart = 0L
        private var tlsStart = 0L
        private var firstByteStart = 0L

        override fun dnsStart(call: Call, domainName: String) {
            dnsStart = System.nanoTime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            record(ProbePhase.DNS, dnsStart)
            dnsStart = 0
        }

        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            tcpStart = System.nanoTime()
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: Protocol?,
        ) {
            // For TLS, TCP is recorded at secureConnectStart so the handshake is not counted twice.
            record(ProbePhase.TCP_CONNECT, tcpStart)
            tcpStart = 0
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            record(ProbePhase.TCP_CONNECT, tcpStart)
            tcpStart = 0
            record(ProbePhase.TLS_HANDSHAKE, tlsStart)
            tlsStart = 0
        }

        override fun secureConnectStart(call: Call) {
            record(ProbePhase.TCP_CONNECT, tcpStart)
            tcpStart = 0
            tlsStart = System.nanoTime()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            record(ProbePhase.TLS_HANDSHAKE, tlsStart)
            tlsStart = 0
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            firstByteStart = System.nanoTime()
        }

        override fun responseHeadersStart(call: Call) {
            record(ProbePhase.HTTP_FIRST_BYTE, firstByteStart)
            firstByteStart = 0
        }

        private fun record(phase: ProbePhase, startNanos: Long) {
            if (startNanos == 0L) return
            val duration = (System.nanoTime() - startNanos).nanoseconds
            synchronized(phases) {
                phases[phase] = (phases[phase] ?: Duration.ZERO) + duration
            }
        }
    }

    internal companion object {
        private const val MAX_REDIRECTS = 3
        const val MAX_BODY_BYTES = 64 * 1024
        private val WEB_SCHEMES = setOf("http", "https")
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val AUTO_FOLLOW_UP_STATUSES = setOf(408, 421, 503)
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
