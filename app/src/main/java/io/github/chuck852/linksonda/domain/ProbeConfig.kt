package io.github.chuck852.linksonda.domain

import java.net.URI
import kotlin.time.Duration

@JvmInline
value class ProbeId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class IpFamily { IPV4, IPV6 }
enum class HttpMethod { GET, HEAD }
enum class DnsTransport { UDP, TCP }
enum class TlsVersion { TLS_1_2, TLS_1_3 }

data class SocketEndpoint(val host: String, val port: Int, val family: IpFamily? = null) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
    }
}

data class HttpExpectation(val status: IntRange, val bodyMarker: String? = null) {
    init {
        require(!status.isEmpty() && status.first >= 100 && status.last <= 599)
        require(bodyMarker == null || bodyMarker.isNotEmpty())
    }
}

data class DnsQuestion(val name: String, val recordType: Int) {
    init {
        require(name.isNotBlank())
        require(recordType in 1..65535)
    }
}

data class DnsExpectation(val responseCode: Int, val requiresAnswer: Boolean) {
    init {
        require(responseCode in 0..4095)
        require(responseCode == 0 || !requiresAnswer)
    }
}

sealed interface ProbeRequest {
    data class TcpConnect(val endpoint: SocketEndpoint) : ProbeRequest

    data class TlsHandshake(
        val endpoint: SocketEndpoint,
        val serverName: String? = null,
        val minimumVersion: TlsVersion? = null,
    ) : ProbeRequest {
        init { require(serverName == null || serverName.isNotBlank()) }
    }

    data class Http(
        val url: URI,
        val method: HttpMethod,
        val expected: HttpExpectation,
        val family: IpFamily? = null,
    ) : ProbeRequest {
        init {
            requireWebEndpoint(url, "http", "https")
            require(method != HttpMethod.HEAD || expected.bodyMarker == null)
        }
    }

    data class SystemDns(
        val question: DnsQuestion,
        val expected: DnsExpectation,
    ) : ProbeRequest

    data class DirectDns(
        val resolver: SocketEndpoint,
        val transport: DnsTransport,
        val question: DnsQuestion,
        val expected: DnsExpectation,
    ) : ProbeRequest

    data class DnsOverHttps(
        val resolver: URI,
        val question: DnsQuestion,
        val expected: DnsExpectation,
        val family: IpFamily? = null,
    ) : ProbeRequest {
        init { requireWebEndpoint(resolver, "https") }
    }

    data class DnsOverTls(
        val resolver: SocketEndpoint,
        val serverName: String,
        val question: DnsQuestion,
        val expected: DnsExpectation,
    ) : ProbeRequest {
        init { require(serverName.isNotBlank()) }
    }

    data class IcmpEcho(
        val host: String,
        val packetCount: Int,
        val minimumReplies: Int,
        val family: IpFamily? = null,
    ) : ProbeRequest {
        init {
            require(host.isNotBlank())
            require(minimumReplies in 1..packetCount)
        }
    }
}

data class ProbeConfig(val id: ProbeId, val name: String, val request: ProbeRequest, val timeout: Duration) {
    init {
        require(name.isNotBlank())
        require(timeout.isFinite() && timeout.isPositive())
    }
}

private fun requireWebEndpoint(url: URI, vararg schemes: String) {
    require(schemes.any { it.equals(url.scheme, ignoreCase = true) })
    require(!url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawFragment == null)
    require(url.port == -1 || url.port in 1..65535)
}
