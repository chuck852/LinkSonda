package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsObservation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeAttempt
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.ProbeRunner
import io.github.chuck852.linksonda.domain.ProbeTiming
import io.github.chuck852.linksonda.domain.RestrictionReason
import io.github.chuck852.linksonda.domain.SkipReason
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** DNS over HTTPS (RFC 8484) and DNS over TLS (RFC 7858) with mandatory TLS validation. */
internal class SecureDnsProbeRunner internal constructor(
    private val baseClient: OkHttpClient,
    private val sslContext: SSLContext,
    hostResolver: HostResolver = SystemHostResolver(),
    private val clock: Clock = Clock.systemUTC(),
) : ProbeRunner {
    private val resolver = BoundedHostResolver(hostResolver)
    private val random = SecureRandom()

    constructor() : this(OkHttpClient(), SSLContext.getDefault())

    override suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult {
        val probe = request.config.request
        val startNanos = System.nanoTime()
        val state = RunState()
        val result = when (probe) {
            is ProbeRequest.DnsOverHttps -> withTimeoutOrNull(request.config.timeout) {
                runDoh(request, probe, admission, startNanos, state)
            }
            is ProbeRequest.DnsOverTls -> withTimeoutOrNull(request.config.timeout) {
                runDot(request, probe, admission, startNanos, state)
            }
            else -> return skipped(request, SkipReason.UNSUPPORTED)
        }
        currentCoroutineContext().ensureActive()
        return result ?: completed(
            request,
            startNanos,
            state,
            ProbeOutcome.Failed(FailureReason.TIMEOUT),
        )
    }

    private suspend fun runDoh(
        request: ProbeRunRequest,
        probe: ProbeRequest.DnsOverHttps,
        admission: ProbeAdmission,
        startNanos: Long,
        state: RunState,
    ): ProbeResult {
        val resolverHost = normalizeHost(probe.resolver.host)
            ?: return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
        val questionHost = normalizeQuestionHost(probe.question.name)
            ?: return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
        if (familyMismatch(probe.family, resolverHost)) {
            return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
        }

        val decision = admission.authorize(ProbeAttempt(questionHost, resolverHost))
        if (decision is AdmissionDecision.Denied) return skipped(request, decision)

        val dnsState = DohDnsState()
        val useCustomDns = !isIpLiteral(resolverHost)
        if (!useCustomDns) {
            val transport = admission.authorize(ProbeAttempt(resolverHost, resolverHost))
            if (transport is AdmissionDecision.Denied) return skipped(request, transport)
        }
        val client = dohClient(
            probe = probe,
            resolverHost = resolverHost,
            admission = admission,
            state = dnsState,
            runState = state,
            useCustomDns = useCustomDns,
            startNanos = startNanos,
            timeout = request.config.timeout,
        )
        val callClient = client.newBuilder()
            .callTimeout(remainingMillis(startNanos, request.config.timeout), TimeUnit.MILLISECONDS)
            .build()
        val query = DnsWire.query(0, probe.question)
        val httpRequest = Request.Builder()
            .url(probe.resolver.toString())
            .header("Accept", DNS_MEDIA_TYPE)
            .header("Content-Type", DNS_MEDIA_TYPE)
            .header("Accept-Encoding", "identity")
            .post(query.toRequestBody(DNS_MEDIA_TYPE.toMediaType()))
            .build()
        val call = callClient.newCall(httpRequest)

        val response = try {
            executeCall(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            (error.findAdmissionDenied()?.decision ?: dnsState.denied)?.let {
                return skipped(request, it)
            }
            if (error.hasCause<DnsCapacityExceededException>()) {
                return completed(request, startNanos, state, ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION))
            }
            error.findBootstrapDns()?.let { bootstrap ->
                return completed(
                    request,
                    startNanos,
                    state,
                    if (bootstrap.cause?.isPermissionDenied() == true) {
                        ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
                    } else {
                        ProbeOutcome.Failed(FailureReason.DNS)
                    },
                )
            }
            error.findBlockedResponse()?.let { blocked ->
                return completed(
                    request,
                    startNanos,
                    state,
                    ProbeOutcome.Restricted(
                        RestrictionReason.RATE_LIMITED,
                        retryAt(blocked.retryAfter, clock),
                    ),
                )
            }
            return completed(request, startNanos, state, classifyTransport(error))
        }

        response.use { received ->
            val restriction = restrictionReason(received.code)
            if (restriction != null) {
                return completed(
                    request,
                    startNanos,
                    state,
                    ProbeOutcome.Restricted(
                        restriction,
                        retryAt(received.header("Retry-After"), clock),
                    ),
                )
            }
            if (received.code != 200) {
                return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.HTTP))
            }
            if (!isDnsMediaType(received.header("Content-Type"))) {
                return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.HTTP))
            }
            val body = try {
                readLimited(received, call)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return completed(request, startNanos, state, classifyTransport(error))
            } ?: return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.HTTP))
            val message = try {
                DnsWire.parseResponse(body, queryId(query), probe.question)
            } catch (error: MalformedDnsResponseException) {
                return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE))
            }
            if (message.truncated) {
                return completed(
                    request,
                    startNanos,
                    state,
                    ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE),
                    DnsObservation(message.responseCode, message.answerCount, true),
                )
            }
            return evaluate(request, probe.expected, message, startNanos, state)
        }
    }

    private suspend fun runDot(
        request: ProbeRunRequest,
        probe: ProbeRequest.DnsOverTls,
        admission: ProbeAdmission,
        startNanos: Long,
        state: RunState,
    ): ProbeResult {
        val resolverHost = normalizeHost(probe.resolver.host)
            ?: return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
        val questionHost = normalizeQuestionHost(probe.question.name)
            ?: return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
        val serverName = normalizeHost(probe.serverName)
            ?: return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.TLS))
        if (familyMismatch(probe.resolver.family, resolverHost)) {
            return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
        }
        val addresses = when {
            isIpLiteral(resolverHost) -> try {
                listOf(InetAddress.getByName(resolverHost))
            } catch (error: SecurityException) {
                return completed(
                    request,
                    startNanos,
                    state,
                    ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
                )
            } catch (error: UnknownHostException) {
                return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
            }
            else -> {
                val decision = admission.authorize(ProbeAttempt(resolverHost, SYSTEM_DNS))
                if (decision is AdmissionDecision.Denied) return skipped(request, decision)
                try {
                    resolver.resolve(resolverHost, probe.resolver.family)
                        .filter { probe.resolver.family == null || it.family() == probe.resolver.family }
                        .sortedBy { it.family() !in request.network.ipFamilies }
                } catch (error: DnsCapacityExceededException) {
                    return completed(request, startNanos, state, ProbeOutcome.Inconclusive(InconclusiveReason.OS_LIMITATION))
                } catch (error: SecurityException) {
                    return completed(
                        request,
                        startNanos,
                        state,
                        ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED),
                    )
                } catch (error: UnknownHostException) {
                    return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
                } catch (error: IOException) {
                    return completed(
                        request,
                        startNanos,
                        state,
                        if (error.isPermissionDenied()) {
                            ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
                        } else {
                            ProbeOutcome.Failed(FailureReason.DNS)
                        },
                    )
                }
            }
        }
        if (addresses.isEmpty()) {
            return completed(request, startNanos, state, ProbeOutcome.Failed(FailureReason.DNS))
        }

        var last: ProbeOutcome? = null
        for ((index, address) in addresses.withIndex()) {
            val now = System.nanoTime()
            val remaining = request.config.timeout.inWholeNanoseconds - (now - startNanos)
            val addressesLeft = addresses.size - index
            val budget = (remaining.coerceAtLeast(MIN_ATTEMPT_NANOS) / addressesLeft)
                .coerceAtLeast(MIN_ATTEMPT_NANOS)
            val deadlineNanos = now + budget
            val transport = admission.authorize(ProbeAttempt(resolverHost, resolverHost))
            if (transport is AdmissionDecision.Denied) return skipped(request, transport)
            val query = admission.authorize(ProbeAttempt(questionHost, resolverHost))
            if (query is AdmissionDecision.Denied) return skipped(request, query)
            try {
                val message = answerDot(
                    address = address,
                    port = probe.resolver.port,
                    serverName = serverName,
                    question = probe.question,
                    deadlineNanos = deadlineNanos,
                    state = state,
                )
                return evaluate(request, probe.expected, message, startNanos, state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                last = classifyTransport(error)
                if (last is ProbeOutcome.Inconclusive) return completed(request, startNanos, state, last)
            }
        }
        return completed(request, startNanos, state, last ?: ProbeOutcome.Failed(FailureReason.TCP))
    }

    private fun dohClient(
        probe: ProbeRequest.DnsOverHttps,
        resolverHost: String,
        admission: ProbeAdmission,
        state: DohDnsState,
        runState: RunState,
        useCustomDns: Boolean,
        startNanos: Long,
        timeout: Duration,
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .fastFallback(false)
            .proxy(Proxy.NO_PROXY)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 503) {
                    val retryAfter = response.header("Retry-After")
                    response.close()
                    throw BlockedResponseException(503, retryAfter)
                }
                response
            }
            .eventListener(object : EventListener() {
                override fun connectStart(
                    call: Call,
                    inetSocketAddress: java.net.InetSocketAddress,
                    proxy: Proxy,
                ) {
                    if (!useCustomDns) return
                    val decision = runBlocking {
                        admission.authorize(ProbeAttempt(resolverHost, resolverHost))
                    }
                    if (decision is AdmissionDecision.Denied) {
                        if (state.denied == null) state.denied = decision
                        throw AdmissionDeniedException(decision)
                    }
                }

                override fun connectionAcquired(call: Call, connection: Connection) {
                    runState.connectedIpFamily = connection.route().socketAddress.address.family()
                }
            })
        if (useCustomDns) {
            builder.dns(Dns { hostname ->
                val host = normalizeHost(hostname) ?: throw UnknownHostException(hostname)
                try {
                    runBlocking {
                        withTimeout(remainingMillis(startNanos, timeout).milliseconds) {
                            val dns = admission.authorize(ProbeAttempt(host, SYSTEM_DNS))
                            if (dns is AdmissionDecision.Denied) throw AdmissionDeniedException(dns)
                            val addresses = try {
                                resolver.resolve(host, probe.family)
                                    .filter { probe.family == null || it.family() == probe.family }
                            } catch (error: DnsCapacityExceededException) {
                                throw error
                            } catch (error: SecurityException) {
                                throw BootstrapDnsException(error)
                            } catch (error: IOException) {
                                throw BootstrapDnsException(error)
                            }
                            if (addresses.isEmpty()) throw UnknownHostException(hostname)
                            addresses
                        }
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    throw SocketTimeoutException("DNS lookup timed out")
                }
            })
        }
        return builder.build()
    }

    private suspend fun answerDot(
        address: InetAddress,
        port: Int,
        serverName: String,
        question: DnsQuestion,
        deadlineNanos: Long,
        state: RunState,
    ): DnsMessage = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val raw = Socket()
            continuation.invokeOnCancellation { raw.closeQuietly() }
            var ssl: SSLSocket? = null
            try {
                raw.connect(
                    java.net.InetSocketAddress(address, port),
                    remainingMillis(deadlineNanos).toInt(),
                )
                raw.soTimeout = remainingMillis(deadlineNanos).toInt()
                ssl = sslContext.socketFactory.createSocket(raw, serverName, port, true) as SSLSocket
                val params = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    val secureProtocols = protocols.filter { it in TLS_PROTOCOLS }
                    if (secureProtocols.isEmpty()) {
                        throw SSLException("No TLS 1.2 or 1.3 protocol available")
                    }
                    protocols = secureProtocols.toTypedArray()
                }
                if (!isIpLiteral(serverName)) {
                    params.serverNames = listOf(SNIHostName(serverName))
                }
                ssl.sslParameters = params
                ssl.startHandshake()
                if (ssl.session.protocol !in TLS_PROTOCOLS) {
                    throw SSLException("Negotiated protocol is not TLS 1.2 or 1.3")
                }
                state.connectedIpFamily = address.family()
                ssl.soTimeout = remainingMillis(deadlineNanos).toInt()
                val query = DnsWire.query(nextId(), question)
                val output = DataOutputStream(ssl.outputStream)
                output.writeShort(query.size)
                output.write(query)
                output.flush()
                val input = ssl.inputStream
                val lengthBytes = ByteArray(2)
                readFullyWithin(input, ssl, lengthBytes, deadlineNanos)
                val length = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
                if (length !in 1..MAX_DNS_MESSAGE) {
                    throw MalformedDnsResponseException("invalid DNS response length")
                }
                val response = ByteArray(length)
                readFullyWithin(input, ssl, response, deadlineNanos)
                val message = try {
                    DnsWire.parseResponse(response, queryId(query), question)
                } catch (error: MalformedDnsResponseException) {
                    throw error
                }
                continuation.resumeWith(Result.success(message))
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                raw.closeQuietly()
                runCatching { ssl?.close() }
            }
        }
    }

    private fun evaluate(
        request: ProbeRunRequest,
        expected: DnsExpectation,
        message: DnsMessage,
        startNanos: Long,
        state: RunState,
    ): ProbeResult.Completed {
        val outcome = when {
            message.truncated -> ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE)
            message.responseCode != expected.responseCode ->
                ProbeOutcome.Failed(FailureReason.UNEXPECTED_DNS_RESPONSE_CODE)
            expected.requiresAnswer && message.matchingAnswerCount == 0 ->
                ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE)
            else -> ProbeOutcome.Matched
        }
        return completed(
            request,
            startNanos,
            state,
            outcome,
            DnsObservation(message.responseCode, message.answerCount, message.truncated),
        )
    }

    private suspend fun executeCall(call: Call): Response =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                try {
                    val response = call.execute()
                    if (continuation.isActive) {
                        continuation.resume(response) { _, value, _ ->
                            runCatching { value.close() }
                        }
                    } else {
                        response.close()
                    }
                } catch (error: Throwable) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private suspend fun readLimited(response: Response, call: Call): ByteArray? =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                    runCatching { response.close() }
                }
                try {
                    val length = response.body.contentLength()
                    if (length > MAX_DNS_MESSAGE) {
                        continuation.resumeWith(Result.success(null))
                        return@suspendCancellableCoroutine
                    }
                    val input = response.body.byteStream()
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > MAX_DNS_MESSAGE) {
                            continuation.resumeWith(Result.success(null))
                            return@suspendCancellableCoroutine
                        }
                        output.write(buffer, 0, read)
                    }
                    continuation.resumeWith(Result.success(output.toByteArray()))
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
        }

    private fun classifyTransport(error: Throwable): ProbeOutcome {
        val chain = generateSequence(error) { it.cause }.toList()
        return when {
            chain.any { it is UnknownHostException } -> ProbeOutcome.Failed(FailureReason.DNS)
            chain.any { it is SSLException } -> ProbeOutcome.Failed(FailureReason.TLS)
            chain.any { it is SocketTimeoutException || it is InterruptedIOException } ->
                ProbeOutcome.Failed(FailureReason.TIMEOUT)
            chain.any { it.isPermissionDenied() } ->
                ProbeOutcome.Inconclusive(InconclusiveReason.PERMISSION_DENIED)
            chain.any { it is NoRouteToHostException } ->
                ProbeOutcome.Failed(FailureReason.UNREACHABLE)
            chain.any { it is ConnectException } -> {
                val message = chain.filterIsInstance<ConnectException>()
                    .joinToString(" ") { it.message.orEmpty() }
                when {
                    message.contains("timed out", ignoreCase = true) ->
                        ProbeOutcome.Failed(FailureReason.TIMEOUT)
                    message.contains("unreachable", ignoreCase = true) ->
                        ProbeOutcome.Failed(FailureReason.UNREACHABLE)
                    else -> ProbeOutcome.Failed(FailureReason.REFUSED)
                }
            }
            chain.any { it is MalformedDnsResponseException || it is EOFException } ->
                ProbeOutcome.Failed(FailureReason.UNEXPECTED_RESPONSE)
            chain.any { it is ProtocolException } -> ProbeOutcome.Failed(FailureReason.HTTP)
            else -> ProbeOutcome.Failed(FailureReason.TCP)
        }
    }

    private fun completed(
        request: ProbeRunRequest,
        startNanos: Long,
        state: RunState,
        outcome: ProbeOutcome,
        dns: DnsObservation? = null,
    ): ProbeResult.Completed = ProbeResult.Completed(
        context = request.context,
        recordedAt = Instant.now(),
        timing = ProbeTiming(elapsed = (System.nanoTime() - startNanos).nanoseconds),
        outcome = outcome,
        observations = ProbeObservations(connectedIpFamily = state.connectedIpFamily, dns = dns),
    )

    private fun skipped(request: ProbeRunRequest, reason: SkipReason): ProbeResult.Skipped =
        ProbeResult.Skipped(request.context, Instant.now(), reason)

    private fun skipped(request: ProbeRunRequest, decision: AdmissionDecision.Denied): ProbeResult.Skipped =
        ProbeResult.Skipped(request.context, Instant.now(), decision.reason, decision.nextAllowedAt)

    private fun remainingMillis(startNanos: Long, timeout: Duration): Long =
        ((timeout.inWholeNanoseconds - (System.nanoTime() - startNanos)).coerceAtLeast(1_000_000L) / 1_000_000L)

    private fun remainingMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()).coerceAtLeast(1_000_000L) / 1_000_000L)

    private fun readFullyWithin(
        input: InputStream,
        socket: SSLSocket,
        buffer: ByteArray,
        deadlineNanos: Long,
    ) {
        var offset = 0
        while (offset < buffer.size) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("DNS exchange deadline exceeded")
            socket.soTimeout = (remaining / 1_000_000L).coerceAtLeast(1L).toInt()
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }

    private fun normalizeHost(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        val withoutBrackets = if (trimmed.length >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed
        return withoutBrackets.removeSuffix(".").lowercase().takeIf { it.isNotEmpty() }
    }

    private fun normalizeQuestionHost(value: String): String? {
        if (value.any { it.isWhitespace() || it.isISOControl() } || '/' in value || '\\' in value) {
            return null
        }
        val ascii = try {
            IDN.toASCII(value.trim().removeSuffix("."), IDN.ALLOW_UNASSIGNED)
        } catch (error: IllegalArgumentException) {
            return null
        }
        if (ascii.isEmpty() || ascii.length > 253) return null
        if (ascii.split('.').any { it.isEmpty() || it.length > 63 }) return null
        return ascii.lowercase().takeIf { !it.startsWith("-") }
    }

    private fun isIpLiteral(host: String): Boolean {
        val ipv4 = IPV4.matches(host) && host.split('.').all { it.toIntOrNull() in 0..255 }
        return ipv4 || host.count { it == ':' } >= 2
    }

    private fun literalFamily(host: String): IpFamily? = when {
        isIpLiteral(host) && host.count { it == ':' } >= 2 -> IpFamily.IPV6
        isIpLiteral(host) -> IpFamily.IPV4
        else -> null
    }

    private fun familyMismatch(family: IpFamily?, host: String): Boolean =
        family != null && literalFamily(host)?.let { it != family } == true

    private fun InetAddress.family(): IpFamily = if (this is Inet4Address) IpFamily.IPV4 else IpFamily.IPV6

    private fun Throwable.isPermissionDenied(): Boolean =
        this is SecurityException ||
            (this is SocketException && message?.let {
                it.contains("EACCES", true) || it.contains("EPERM", true) ||
                    it.contains("permission denied", true)
            } == true)

    private fun isDnsMediaType(value: String?): Boolean =
        value?.substringBefore(';')?.trim()?.equals(DNS_MEDIA_TYPE, ignoreCase = true) == true

    private fun nextId(): Int = random.nextInt(0x10000)

    private fun queryId(query: ByteArray): Int =
        ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)

    private fun Socket.closeQuietly() = runCatching { close() }

    private fun Throwable.findAdmissionDenied(): AdmissionDeniedException? =
        generateSequence(this) { it.cause }.filterIsInstance<AdmissionDeniedException>().firstOrNull()

    private fun Throwable.findBootstrapDns(): BootstrapDnsException? =
        generateSequence(this) { it.cause }.filterIsInstance<BootstrapDnsException>().firstOrNull()

    private fun Throwable.findBlockedResponse(): BlockedResponseException? =
        generateSequence(this) { it.cause }.filterIsInstance<BlockedResponseException>().firstOrNull()

    private fun restrictionReason(status: Int): RestrictionReason? = when (status) {
        429, 503 -> RestrictionReason.RATE_LIMITED
        403 -> RestrictionReason.ACCESS_DENIED
        else -> null
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }

    private class RunState {
        @Volatile var connectedIpFamily: IpFamily? = null
    }

    private class DohDnsState {
        @Volatile var denied: AdmissionDecision.Denied? = null
    }

    private class AdmissionDeniedException(val decision: AdmissionDecision.Denied) :
        RuntimeException("admission denied")

    private class BlockedResponseException(
        val status: Int,
        val retryAfter: String?,
    ) : IOException("HTTP $status has an automatic follow-up")

    private class BootstrapDnsException(cause: Throwable) :
        IOException("bootstrap DNS failed", cause)

    internal companion object {
        const val MAX_DNS_MESSAGE = 65_535
        private const val DNS_MEDIA_TYPE = "application/dns-message"
        private const val SYSTEM_DNS = "system-dns"
        private const val MIN_ATTEMPT_NANOS = 1_000_000L
        private val TLS_PROTOCOLS = setOf("TLSv1.2", "TLSv1.3")
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
