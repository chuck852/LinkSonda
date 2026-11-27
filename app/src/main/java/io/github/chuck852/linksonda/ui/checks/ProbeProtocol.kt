package io.github.chuck852.linksonda.ui.checks

import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeContext
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeKey
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeState
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.DnsTransport

/** All P0 request families represented by the current domain model. */
enum class ProbeProtocol {
    TCP,
    TLS,
    HTTP,
    HTTPS,
    SYSTEM_DNS,
    DNS_UDP,
    DNS_TCP,
    DOH,
    DOT,
    ICMP,
}

fun ProbeRequest.toProtocol(): ProbeProtocol = when (this) {
    is ProbeRequest.TcpConnect -> ProbeProtocol.TCP
    is ProbeRequest.TlsHandshake -> ProbeProtocol.TLS
    is ProbeRequest.Http -> if (url.scheme.equals("https", ignoreCase = true)) {
        ProbeProtocol.HTTPS
    } else {
        ProbeProtocol.HTTP
    }

    is ProbeRequest.SystemDns -> ProbeProtocol.SYSTEM_DNS
    is ProbeRequest.DirectDns -> when (transport) {
        DnsTransport.UDP -> ProbeProtocol.DNS_UDP
        DnsTransport.TCP -> ProbeProtocol.DNS_TCP
    }

    is ProbeRequest.DnsOverHttps -> ProbeProtocol.DOH
    is ProbeRequest.DnsOverTls -> ProbeProtocol.DOT
    is ProbeRequest.IcmpEcho -> ProbeProtocol.ICMP
}

fun ProbeRequest.targetText(): String = when (this) {
    is ProbeRequest.TcpConnect -> formatEndpoint(endpoint.host, endpoint.port)
    is ProbeRequest.TlsHandshake -> formatEndpoint(endpoint.host, endpoint.port)
    is ProbeRequest.Http -> url.toString()
    is ProbeRequest.SystemDns -> "${question.name} · type ${question.recordType}"
    is ProbeRequest.DirectDns -> "${formatEndpoint(resolver.host, resolver.port)} · ${question.name}"
    is ProbeRequest.DnsOverHttps -> "$resolver · ${question.name}"
    is ProbeRequest.DnsOverTls -> "${formatEndpoint(resolver.host, resolver.port)} · ${question.name}"
    is ProbeRequest.IcmpEcho -> "$host · $packetCount packets"
}

/** IPv6 literals need brackets before a port; already bracketed hosts are kept as they are. */
fun formatEndpoint(host: String, port: Int): String {
    val value = host.trim()
    val formattedHost = if (value.contains(':') && !(value.startsWith("[") && value.endsWith("]"))) {
        "[$value]"
    } else {
        value
    }
    return "$formattedHost:$port"
}

/**
 * Domain-backed row shared by the checks list and the details screen.
 *
 * Keeping [ProbeConfig] and [ProbeState] intact preserves the configuration revision, the observed
 * phases and every optional fact. UI code must render missing facts as missing, not as zero.
 */
data class ProbeListItem(
    val config: ProbeConfig,
    val state: ProbeState,
    val groupId: String,
    val groupName: String,
) {
    init {
        require(groupId.isNotBlank() && groupName.isNotBlank())
        require(config.key == state.context.probe) {
            "ProbeState ${state.context.probe} does not belong to config ${config.key}"
        }
    }

    val id: ProbeId get() = config.id
    val key: ProbeKey get() = config.key
    val context: ProbeContext get() = state.context
    val protocol: ProbeProtocol get() = config.request.toProtocol()
    val target: String get() = config.request.targetText()
    val status: ProbeStatus get() = state.status
    val freshness: ProbeFreshness get() = state.freshness
    val lastCompleted: ProbeResult.Completed? get() = state.lastCompleted
    val lastSkipped: ProbeResult.Skipped? get() = state.lastSkipped

    /**
     * The skipped slot only describes the latest state when it is newer than the retained completed
     * result. An older skipped record stays in history and must not replace a newer result.
     */
    val latestSkipped: ProbeResult.Skipped?
        get() {
            val skipped = lastSkipped ?: return null
            val completed = lastCompleted ?: return skipped
            return skipped.takeIf { it.recordedAt.isAfter(completed.recordedAt) }
        }
}
