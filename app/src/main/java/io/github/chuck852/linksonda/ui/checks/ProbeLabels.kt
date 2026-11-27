package io.github.chuck852.linksonda.ui.checks

import androidx.annotation.StringRes
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.DnsTransport
import io.github.chuck852.linksonda.domain.FailureReason
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.InconclusiveReason
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.RestrictionReason
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.TlsVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@StringRes
fun ProbeProtocol.label(): Int = when (this) {
    ProbeProtocol.TCP -> R.string.protocol_tcp_connect
    ProbeProtocol.TLS -> R.string.protocol_tls_handshake
    ProbeProtocol.HTTP -> R.string.protocol_http
    ProbeProtocol.HTTPS -> R.string.protocol_https
    ProbeProtocol.SYSTEM_DNS -> R.string.protocol_system_dns
    ProbeProtocol.DNS_UDP -> R.string.protocol_dns_udp
    ProbeProtocol.DNS_TCP -> R.string.protocol_dns_tcp
    ProbeProtocol.DOH -> R.string.protocol_doh
    ProbeProtocol.DOT -> R.string.protocol_dot
    ProbeProtocol.ICMP -> R.string.protocol_icmp
}

@StringRes
fun ProbeFreshness.label(): Int = when (this) {
    ProbeFreshness.FRESH -> R.string.freshness_fresh
    ProbeFreshness.STALE -> R.string.freshness_stale
    ProbeFreshness.MISSING -> R.string.freshness_missing
}

@StringRes
fun FailureReason.label(): Int = when (this) {
    FailureReason.DNS -> R.string.probe_failure_dns
    FailureReason.TCP -> R.string.probe_failure_tcp
    FailureReason.TLS -> R.string.probe_failure_tls
    FailureReason.HTTP -> R.string.probe_failure_http
    FailureReason.ICMP -> R.string.probe_failure_icmp
    FailureReason.TIMEOUT -> R.string.probe_failure_timeout
    FailureReason.REFUSED -> R.string.probe_failure_refused
    FailureReason.UNREACHABLE -> R.string.probe_failure_unreachable
    FailureReason.UNEXPECTED_RESPONSE -> R.string.probe_failure_unexpected_response
    FailureReason.UNEXPECTED_HTTP_STATUS -> R.string.probe_failure_http_status
    FailureReason.UNEXPECTED_DNS_RESPONSE_CODE -> R.string.probe_failure_unexpected_dns_response
}

@StringRes
fun InconclusiveReason.label(): Int = when (this) {
    InconclusiveReason.OS_LIMITATION -> R.string.inconclusive_os_limitation
    InconclusiveReason.DOZE -> R.string.inconclusive_doze
    InconclusiveReason.NETWORK_CHANGED -> R.string.inconclusive_network_changed
    InconclusiveReason.PERMISSION_DENIED -> R.string.inconclusive_permission_denied
}

@StringRes
fun RestrictionReason.label(): Int = when (this) {
    RestrictionReason.RATE_LIMITED -> R.string.restricted_rate_limited
    RestrictionReason.ACCESS_DENIED -> R.string.restricted_access_denied
    RestrictionReason.CHALLENGE -> R.string.restricted_challenge
}

@StringRes
fun SkipReason.label(): Int = when (this) {
    SkipReason.SLEEP -> R.string.skip_sleep
    SkipReason.BUDGET -> R.string.skip_budget
    SkipReason.COOLDOWN -> R.string.skip_cooldown
    SkipReason.NOT_ALLOWED -> R.string.skip_not_allowed
    SkipReason.NO_NETWORK -> R.string.skip_no_network
    SkipReason.UNSUPPORTED -> R.string.skip_unsupported
    SkipReason.CANCELLED -> R.string.skip_cancelled
    SkipReason.PERMISSION_DENIED -> R.string.skip_permission_denied
}

@StringRes
fun ProbePhase.label(): Int = when (this) {
    ProbePhase.DNS -> R.string.phase_dns
    ProbePhase.TCP_CONNECT -> R.string.phase_tcp
    ProbePhase.TLS_HANDSHAKE -> R.string.phase_tls
    ProbePhase.HTTP_FIRST_BYTE -> R.string.phase_http
}

@StringRes
fun IpFamily.label(): Int = when (this) {
    IpFamily.IPV4 -> R.string.value_ipv4
    IpFamily.IPV6 -> R.string.value_ipv6
}

@StringRes
fun TlsVersion.label(): Int = when (this) {
    TlsVersion.TLS_1_2 -> R.string.value_tls_12
    TlsVersion.TLS_1_3 -> R.string.value_tls_13
}

@StringRes
fun HttpMethod.label(): Int = when (this) {
    HttpMethod.GET -> R.string.value_get
    HttpMethod.HEAD -> R.string.value_head
}

@StringRes
fun DnsTransport.label(): Int = when (this) {
    DnsTransport.UDP -> R.string.value_udp
    DnsTransport.TCP -> R.string.value_tcp
}

fun Duration.display(): String =
    if (this < 1.seconds) {
        "${inWholeMilliseconds} ms"
    } else {
        String.format(Locale.US, "%.2f s", inWholeMilliseconds / 1000.0)
    }

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())

fun Instant.display(): String = dateTimeFormatter.format(this)
