package io.github.chuck852.linksonda.ui.checks

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import io.github.chuck852.linksonda.domain.DnsExpectation
import io.github.chuck852.linksonda.domain.DnsQuestion
import io.github.chuck852.linksonda.domain.DnsTransport
import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.domain.TlsVersion
import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class ProbeEditorField {
    NAME,
    HOST,
    PORT,
    URL,
    SERVER_NAME,
    DNS_NAME,
    RECORD_TYPE,
    EXPECTED_STATUS,
    BODY_MARKER,
    EXPECTED_RCODE,
    IP_FAMILY,
    TIMEOUT,
    PACKET_COUNT,
    MINIMUM_REPLIES,
}

enum class ProbeEditorError {
    REQUIRED,
    INVALID_HOST,
    INVALID_PORT,
    INVALID_URL,
    INVALID_SERVER_NAME,
    INVALID_DNS_NAME,
    INVALID_RECORD_TYPE,
    INVALID_STATUS_RANGE,
    HEAD_WITH_BODY_MARKER,
    FAMILY_MISMATCH,
    INVALID_RCODE,
    ANSWER_NOT_ALLOWED,
    INVALID_TIMEOUT,
    INVALID_PACKET_COUNT,
    INVALID_MINIMUM_REPLIES,
}

data class ProbeEditorValidation(
    val errors: Map<ProbeEditorField, ProbeEditorError>,
    val config: ProbeConfig?,
) {
    val isValid: Boolean get() = config != null
}

/**
 * Pure editor state for all supported P0 request families.
 *
 * Protocol-specific fields are kept as text so that partially typed, invalid input never reaches the
 * domain model. [validate] is the only place that constructs [ProbeConfig]; changing the protocol
 * resets fields that do not apply to the new request family.
 */
data class ProbeEditorState(
    val probeId: String,
    val name: String = "",
    val groupId: String = DEFAULT_GROUP_ID,
    val groupName: String = DEFAULT_GROUP_NAME,
    val protocol: ProbeProtocol = ProbeProtocol.HTTPS,
    val host: String = "",
    val port: String = defaultPort(ProbeProtocol.HTTPS),
    val url: String = "",
    val method: HttpMethod = HttpMethod.GET,
    val expectedStatus: String = DEFAULT_STATUS_RANGE,
    val bodyMarker: String = "",
    val serverName: String = "",
    val minimumTls: TlsVersion? = null,
    val dnsName: String = "",
    val recordType: String = "1",
    val expectedRcode: String = "0",
    val requiresAnswer: Boolean = true,
    val ipFamily: IpFamily? = null,
    val timeoutSeconds: String = "5",
    val packetCount: String = "4",
    val minimumReplies: String = "3",
) {
    init {
        require(probeId.isNotBlank())
    }

    fun withProtocol(protocol: ProbeProtocol): ProbeEditorState = copy(
        protocol = protocol,
        host = "",
        port = defaultPort(protocol),
        url = "",
        method = HttpMethod.GET,
        expectedStatus = DEFAULT_STATUS_RANGE,
        bodyMarker = "",
        serverName = "",
        minimumTls = null,
        dnsName = "",
        recordType = "1",
        expectedRcode = "0",
        requiresAnswer = true,
        ipFamily = null,
        packetCount = "4",
        minimumReplies = "3",
    )

    fun withExpectedRcode(value: String): ProbeEditorState {
        val parsed = value.trim().toIntOrNull()
        return copy(
            expectedRcode = value,
            requiresAnswer = if (parsed == null || parsed == 0) requiresAnswer else false,
        )
    }

    fun validate(baseConfig: ProbeConfig? = null): ProbeEditorValidation {
        val errors = linkedMapOf<ProbeEditorField, ProbeEditorError>()
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            errors[ProbeEditorField.NAME] = ProbeEditorError.REQUIRED
        }
        val timeout = parseTimeout(timeoutSeconds)
        if (timeout == null) {
            errors[ProbeEditorField.TIMEOUT] = ProbeEditorError.INVALID_TIMEOUT
        }

        val request = validateRequest(errors)
        val config = if (errors.isEmpty() && request != null && timeout != null) {
            if (baseConfig == null || baseConfig.id.value != probeId.trim()) {
                ProbeConfig(ProbeId(probeId.trim()), trimmedName, request, timeout)
            } else {
                baseConfig.update(name = trimmedName, request = request, timeout = timeout)
            }
        } else {
            null
        }
        return ProbeEditorValidation(errors = errors, config = config)
    }

    private fun validateRequest(errors: MutableMap<ProbeEditorField, ProbeEditorError>): ProbeRequest? =
        when (protocol) {
            ProbeProtocol.TCP -> validateSocket(errors)?.let(ProbeRequest::TcpConnect)
            ProbeProtocol.TLS -> {
                val endpoint = validateSocket(errors)
                val server = serverName.trim()
                if (server.isEmpty()) {
                    errors[ProbeEditorField.SERVER_NAME] = ProbeEditorError.REQUIRED
                } else if (!isValidServerName(server)) {
                    errors[ProbeEditorField.SERVER_NAME] = ProbeEditorError.INVALID_SERVER_NAME
                }
                if (endpoint == null || errors.containsKey(ProbeEditorField.SERVER_NAME)) {
                    null
                } else {
                    ProbeRequest.TlsHandshake(
                        endpoint = endpoint,
                        serverName = server,
                        minimumVersion = minimumTls,
                    )
                }
            }

            ProbeProtocol.HTTP,
            ProbeProtocol.HTTPS,
            -> {
                val scheme = if (protocol == ProbeProtocol.HTTPS) "https" else "http"
                val parsedUrl = validateUrl(errors, url, scheme)
                val status = parseStatusRange(expectedStatus)
                if (status == null) {
                    errors[ProbeEditorField.EXPECTED_STATUS] = ProbeEditorError.INVALID_STATUS_RANGE
                } else if (method == HttpMethod.HEAD && bodyMarker.trim().isNotEmpty()) {
                    errors[ProbeEditorField.BODY_MARKER] = ProbeEditorError.HEAD_WITH_BODY_MARKER
                }
                if (parsedUrl == null || status == null ||
                    errors.containsKey(ProbeEditorField.BODY_MARKER) ||
                    errors.containsKey(ProbeEditorField.IP_FAMILY)
                ) {
                    null
                } else {
                    ProbeRequest.Http(
                        url = parsedUrl,
                        method = method,
                        expected = HttpExpectation(status, bodyMarker.trim().ifEmpty { null }),
                        family = ipFamily,
                    )
                }
            }

            ProbeProtocol.SYSTEM_DNS -> {
                val question = validateDnsQuestion(errors)
                val expected = validateDnsExpectation(errors)
                if (question == null || expected == null) null else ProbeRequest.SystemDns(question, expected)
            }

            ProbeProtocol.DNS_UDP,
            ProbeProtocol.DNS_TCP,
            -> {
                val resolver = validateSocket(errors)
                val question = validateDnsQuestion(errors)
                val expected = validateDnsExpectation(errors)
                if (resolver == null || question == null || expected == null) {
                    null
                } else {
                    ProbeRequest.DirectDns(
                        resolver = resolver,
                        transport = if (protocol == ProbeProtocol.DNS_UDP) DnsTransport.UDP else DnsTransport.TCP,
                        question = question,
                        expected = expected,
                    )
                }
            }

            ProbeProtocol.DOH -> {
                val resolver = validateUrl(errors, url, "https")
                val question = validateDnsQuestion(errors)
                val expected = validateDnsExpectation(errors)
                if (resolver == null || question == null || expected == null || errors.containsKey(ProbeEditorField.IP_FAMILY)) {
                    null
                } else {
                    ProbeRequest.DnsOverHttps(
                        resolver = resolver,
                        question = question,
                        expected = expected,
                        family = ipFamily,
                    )
                }
            }

            ProbeProtocol.DOT -> {
                val resolver = validateSocket(errors)
                val server = serverName.trim()
                if (server.isEmpty()) {
                    errors[ProbeEditorField.SERVER_NAME] = ProbeEditorError.REQUIRED
                } else if (!isValidServerName(server)) {
                    errors[ProbeEditorField.SERVER_NAME] = ProbeEditorError.INVALID_SERVER_NAME
                }
                val question = validateDnsQuestion(errors)
                val expected = validateDnsExpectation(errors)
                if (resolver == null || errors.containsKey(ProbeEditorField.SERVER_NAME) || question == null || expected == null) {
                    null
                } else {
                    ProbeRequest.DnsOverTls(
                        resolver = resolver,
                        serverName = server,
                        question = question,
                        expected = expected,
                    )
                }
            }

            ProbeProtocol.ICMP -> {
                val target = host.trim()
                if (target.isEmpty()) {
                    errors[ProbeEditorField.HOST] = ProbeEditorError.REQUIRED
                } else if (!isValidHost(target)) {
                    errors[ProbeEditorField.HOST] = ProbeEditorError.INVALID_HOST
                } else if (isAddressFamilyMismatch(target, ipFamily)) {
                    errors[ProbeEditorField.IP_FAMILY] = ProbeEditorError.FAMILY_MISMATCH
                }
                val packets = packetCount.trim().toIntOrNull()
                if (packets == null || packets !in MIN_PACKETS..MAX_PACKETS) {
                    errors[ProbeEditorField.PACKET_COUNT] = ProbeEditorError.INVALID_PACKET_COUNT
                }
                val replies = minimumReplies.trim().toIntOrNull()
                if (packets == null) {
                    errors[ProbeEditorField.MINIMUM_REPLIES] = ProbeEditorError.INVALID_MINIMUM_REPLIES
                } else if (replies == null || replies !in 1..packets) {
                    errors[ProbeEditorField.MINIMUM_REPLIES] = ProbeEditorError.INVALID_MINIMUM_REPLIES
                }
                if (errors.containsKey(ProbeEditorField.HOST) ||
                    errors.containsKey(ProbeEditorField.PACKET_COUNT) ||
                    errors.containsKey(ProbeEditorField.MINIMUM_REPLIES) ||
                    errors.containsKey(ProbeEditorField.IP_FAMILY)
                ) {
                    null
                } else {
                    ProbeRequest.IcmpEcho(
                        host = target,
                        packetCount = requireNotNull(packets),
                        minimumReplies = requireNotNull(replies),
                        family = ipFamily,
                    )
                }
            }
        }

    private fun validateSocket(errors: MutableMap<ProbeEditorField, ProbeEditorError>): SocketEndpoint? {
        val target = host.trim()
        if (target.isEmpty()) {
            errors[ProbeEditorField.HOST] = ProbeEditorError.REQUIRED
        } else if (!isValidHost(target)) {
            errors[ProbeEditorField.HOST] = ProbeEditorError.INVALID_HOST
        }
        if (isValidHost(target) && isAddressFamilyMismatch(target, ipFamily)) {
            errors[ProbeEditorField.IP_FAMILY] = ProbeEditorError.FAMILY_MISMATCH
        }
        val parsedPort = port.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            errors[ProbeEditorField.PORT] = ProbeEditorError.INVALID_PORT
        }
        return if (errors.containsKey(ProbeEditorField.HOST) ||
            errors.containsKey(ProbeEditorField.PORT) ||
            errors.containsKey(ProbeEditorField.IP_FAMILY)
        ) {
            null
        } else {
            SocketEndpoint(target, requireNotNull(parsedPort), ipFamily)
        }
    }

    private fun validateUrl(
        errors: MutableMap<ProbeEditorField, ProbeEditorError>,
        value: String,
        scheme: String,
    ): URI? {
        val parsed = parseUrl(value, scheme)
        if (parsed == null) {
            errors[ProbeEditorField.URL] = ProbeEditorError.INVALID_URL
        } else if (parsed.host?.let { isAddressFamilyMismatch(it, ipFamily) } == true) {
            errors[ProbeEditorField.IP_FAMILY] = ProbeEditorError.FAMILY_MISMATCH
        }
        return parsed
    }

    private fun validateDnsQuestion(errors: MutableMap<ProbeEditorField, ProbeEditorError>): DnsQuestion? {
        val target = dnsName.trim()
        if (target.isEmpty()) {
            errors[ProbeEditorField.DNS_NAME] = ProbeEditorError.REQUIRED
        } else if (!isValidDnsName(target)) {
            errors[ProbeEditorField.DNS_NAME] = ProbeEditorError.INVALID_DNS_NAME
        }
        val parsedType = recordType.trim().toIntOrNull()
        if (parsedType == null || parsedType !in 1..65535) {
            errors[ProbeEditorField.RECORD_TYPE] = ProbeEditorError.INVALID_RECORD_TYPE
        }
        return if (errors.containsKey(ProbeEditorField.DNS_NAME) || errors.containsKey(ProbeEditorField.RECORD_TYPE)) {
            null
        } else {
            DnsQuestion(target, requireNotNull(parsedType))
        }
    }

    private fun validateDnsExpectation(
        errors: MutableMap<ProbeEditorField, ProbeEditorError>,
    ): DnsExpectation? {
        val parsedRcode = expectedRcode.trim().toIntOrNull()
        if (parsedRcode == null || parsedRcode !in 0..4095) {
            errors[ProbeEditorField.EXPECTED_RCODE] = ProbeEditorError.INVALID_RCODE
            return null
        }
        if (parsedRcode != 0 && requiresAnswer) {
            errors[ProbeEditorField.EXPECTED_RCODE] = ProbeEditorError.ANSWER_NOT_ALLOWED
            return null
        }
        return DnsExpectation(responseCode = parsedRcode, requiresAnswer = requiresAnswer)
    }

    companion object {
        fun forNew(
            probeId: String,
            groupId: String = DEFAULT_GROUP_ID,
            groupName: String = DEFAULT_GROUP_NAME,
            protocol: ProbeProtocol = ProbeProtocol.HTTPS,
        ): ProbeEditorState = ProbeEditorState(
            probeId = probeId,
            groupId = groupId,
            groupName = groupName,
            protocol = protocol,
            port = defaultPort(protocol),
        )

        fun from(config: ProbeConfig, groupId: String, groupName: String): ProbeEditorState {
            val base = ProbeEditorState(
                probeId = config.id.value,
                name = config.name,
                groupId = groupId,
                groupName = groupName,
                protocol = config.request.toProtocol(),
                timeoutSeconds = formatSeconds(config.timeout),
            )
            return when (val request = config.request) {
                is ProbeRequest.TcpConnect -> base.copy(
                    host = request.endpoint.host,
                    port = request.endpoint.port.toString(),
                    ipFamily = request.endpoint.family,
                )

                is ProbeRequest.TlsHandshake -> base.copy(
                    host = request.endpoint.host,
                    port = request.endpoint.port.toString(),
                    serverName = request.serverName.orEmpty(),
                    minimumTls = request.minimumVersion,
                    ipFamily = request.endpoint.family,
                )

                is ProbeRequest.Http -> base.copy(
                    url = request.url.toString(),
                    method = request.method,
                    expectedStatus = formatStatusRange(request.expected.status),
                    bodyMarker = request.expected.bodyMarker.orEmpty(),
                    ipFamily = request.family,
                )

                is ProbeRequest.SystemDns -> base.copy(
                    dnsName = request.question.name,
                    recordType = request.question.recordType.toString(),
                    expectedRcode = request.expected.responseCode.toString(),
                    requiresAnswer = request.expected.requiresAnswer,
                )

                is ProbeRequest.DirectDns -> base.copy(
                    host = request.resolver.host,
                    port = request.resolver.port.toString(),
                    ipFamily = request.resolver.family,
                    dnsName = request.question.name,
                    recordType = request.question.recordType.toString(),
                    expectedRcode = request.expected.responseCode.toString(),
                    requiresAnswer = request.expected.requiresAnswer,
                )

                is ProbeRequest.DnsOverHttps -> base.copy(
                    url = request.resolver.toString(),
                    ipFamily = request.family,
                    dnsName = request.question.name,
                    recordType = request.question.recordType.toString(),
                    expectedRcode = request.expected.responseCode.toString(),
                    requiresAnswer = request.expected.requiresAnswer,
                )

                is ProbeRequest.DnsOverTls -> base.copy(
                    host = request.resolver.host,
                    port = request.resolver.port.toString(),
                    ipFamily = request.resolver.family,
                    serverName = request.serverName,
                    dnsName = request.question.name,
                    recordType = request.question.recordType.toString(),
                    expectedRcode = request.expected.responseCode.toString(),
                    requiresAnswer = request.expected.requiresAnswer,
                )

                is ProbeRequest.IcmpEcho -> base.copy(
                    host = request.host,
                    packetCount = request.packetCount.toString(),
                    minimumReplies = request.minimumReplies.toString(),
                    ipFamily = request.family,
                )
            }
        }
    }
}

const val DEFAULT_GROUP_ID = "custom"
const val DEFAULT_GROUP_NAME = "Custom checks"
private const val DEFAULT_STATUS_RANGE = "200-299"
private const val MIN_PACKETS = 1
private const val MAX_PACKETS = 10

fun defaultPort(protocol: ProbeProtocol): String = when (protocol) {
    ProbeProtocol.TCP, ProbeProtocol.TLS, ProbeProtocol.HTTPS -> "443"
    ProbeProtocol.DNS_UDP, ProbeProtocol.DNS_TCP -> "53"
    ProbeProtocol.DOT -> "853"
    ProbeProtocol.HTTP, ProbeProtocol.SYSTEM_DNS, ProbeProtocol.DOH, ProbeProtocol.ICMP -> ""
}

fun parseStatusRange(value: String): IntRange? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val parts = when {
        ".." in text -> text.split("..", limit = 2)
        "-" in text -> text.split("-", limit = 2)
        else -> listOf(text, text)
    }
    if (parts.size != 2) return null
    val first = parts[0].trim().toIntOrNull() ?: return null
    val last = parts[1].trim().toIntOrNull() ?: return null
    if (first !in 100..599 || last !in 100..599 || first > last) return null
    return first..last
}

fun formatStatusRange(range: IntRange): String =
    if (range.first == range.last) range.first.toString() else "${range.first}-${range.last}"

fun parseTimeout(value: String): Duration? {
    val seconds = value.trim().toDoubleOrNull() ?: return null
    if (!seconds.isFinite() || seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) return null
    return (seconds * 1000).roundToLong().milliseconds
}

private const val MIN_TIMEOUT_SECONDS = 0.1
private const val MAX_TIMEOUT_SECONDS = 120.0

fun formatSeconds(duration: Duration): String {
    val millis = duration.inWholeMilliseconds
    return if (millis % 1000L == 0L) {
        (millis / 1000L).toString()
    } else {
        String.format(Locale.US, "%.3f", millis / 1000.0).trimEnd('0').trimEnd('.')
    }
}

fun isValidHost(value: String): Boolean {
    val host = value.trim().removeSuffix(".")
    if (host.isEmpty()) return false
    if (host.startsWith("[") && host.endsWith("]")) {
        return isValidIpv6(host.substring(1, host.length - 1))
    }
    if (host.contains(':')) return isValidIpv6(host)
    if (IPV4.matches(host)) return true
    if (host.length > 253) return false
    return host.split('.').all { label -> label.isNotEmpty() && HOST_LABEL.matches(label) }
}

fun isValidServerName(value: String): Boolean =
    isValidHost(value) && !isIpLiteral(value)

fun isValidDnsName(value: String): Boolean {
    val name = value.trim().removeSuffix(".")
    if (name.isEmpty() || name.length > 253) return false
    return name.split('.').all { label -> label.isNotEmpty() && DNS_LABEL.matches(label) }
}

fun isAddressFamilyMismatch(host: String, family: IpFamily?): Boolean {
    if (family == null) return false
    val value = host.trim().removeSuffix(".")
    val literal = if (value.startsWith("[") && value.endsWith("]")) {
        value.substring(1, value.length - 1)
    } else {
        value
    }
    return when {
        IPV4.matches(literal) -> family != IpFamily.IPV4
        literal.contains(':') -> family != IpFamily.IPV6
        else -> false
    }
}

private fun isIpLiteral(value: String): Boolean {
    val host = value.trim().removeSuffix(".")
    return IPV4.matches(host) || host.contains(':')
}

private fun isValidIpv6(value: String): Boolean {
    val address = value.substringBefore('%')
    if (address.isEmpty() || address.count { it == ':' } < 2) return false
    val firstDoubleColon = address.indexOf("::")
    if (firstDoubleColon >= 0 && address.indexOf("::", firstDoubleColon + 1) >= 0) return false
    val head: List<String>
    val tail: List<String>
    if (firstDoubleColon >= 0) {
        head = address.substring(0, firstDoubleColon).split(':').filter(String::isNotEmpty)
        tail = address.substring(firstDoubleColon + 2).split(':').filter(String::isNotEmpty)
    } else {
        head = address.split(':')
        tail = emptyList()
    }
    var groups = 0
    for (part in head + tail) {
        if (part.contains('.')) {
            if (!IPV4.matches(part)) return false
            groups += 2
        } else {
            if (!IPV6_GROUP.matches(part)) return false
            groups += 1
        }
    }
    return if (firstDoubleColon >= 0) groups <= 8 else groups == 8
}

private fun parseUrl(value: String, expectedScheme: String): URI? {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    if (!expectedScheme.equals(uri.scheme, ignoreCase = true)) return null
    val host = uri.host ?: return null
    if (host.isBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return null
    if (uri.port != -1 && uri.port !in 1..65535) return null
    if (!isValidHost(host)) return null
    return uri
}

private val IPV4 = Regex("""^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)$""")
private val IPV6_GROUP = Regex("^[0-9A-Fa-f]{1,4}$")
private val HOST_LABEL = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
private val DNS_LABEL = Regex("^[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$")

val ProbeEditorStateSaver: Saver<ProbeEditorState, Any> = mapSaver(
    save = { state ->
        mapOf(
            "probeId" to state.probeId,
            "name" to state.name,
            "groupId" to state.groupId,
            "groupName" to state.groupName,
            "protocol" to state.protocol.name,
            "host" to state.host,
            "port" to state.port,
            "url" to state.url,
            "method" to state.method.name,
            "expectedStatus" to state.expectedStatus,
            "bodyMarker" to state.bodyMarker,
            "serverName" to state.serverName,
            "minimumTls" to state.minimumTls?.name.orEmpty(),
            "dnsName" to state.dnsName,
            "recordType" to state.recordType,
            "expectedRcode" to state.expectedRcode,
            "requiresAnswer" to state.requiresAnswer,
            "ipFamily" to state.ipFamily?.name.orEmpty(),
            "timeoutSeconds" to state.timeoutSeconds,
            "packetCount" to state.packetCount,
            "minimumReplies" to state.minimumReplies,
        )
    },
    restore = { values ->
        ProbeEditorState(
            probeId = values["probeId"] as? String ?: "probe",
            name = (values["name"] as? String).orEmpty(),
            groupId = values["groupId"] as? String ?: DEFAULT_GROUP_ID,
            groupName = values["groupName"] as? String ?: DEFAULT_GROUP_NAME,
            protocol = (values["protocol"] as? String)
                ?.let { name -> ProbeProtocol.entries.firstOrNull { it.name == name } }
                ?: ProbeProtocol.HTTPS,
            host = (values["host"] as? String).orEmpty(),
            port = (values["port"] as? String).orEmpty(),
            url = (values["url"] as? String).orEmpty(),
            method = (values["method"] as? String)
                ?.let { name -> HttpMethod.entries.firstOrNull { it.name == name } }
                ?: HttpMethod.GET,
            expectedStatus = values["expectedStatus"] as? String ?: DEFAULT_STATUS_RANGE,
            bodyMarker = (values["bodyMarker"] as? String).orEmpty(),
            serverName = (values["serverName"] as? String).orEmpty(),
            minimumTls = (values["minimumTls"] as? String)
                ?.takeIf(String::isNotBlank)
                ?.let { name -> TlsVersion.entries.firstOrNull { it.name == name } },
            dnsName = (values["dnsName"] as? String).orEmpty(),
            recordType = values["recordType"] as? String ?: "1",
            expectedRcode = values["expectedRcode"] as? String ?: "0",
            requiresAnswer = values["requiresAnswer"] as? Boolean ?: true,
            ipFamily = (values["ipFamily"] as? String)
                ?.takeIf(String::isNotBlank)
                ?.let { name -> IpFamily.entries.firstOrNull { it.name == name } },
            timeoutSeconds = values["timeoutSeconds"] as? String ?: "5",
            packetCount = values["packetCount"] as? String ?: "4",
            minimumReplies = values["minimumReplies"] as? String ?: "3",
        )
    },
)
