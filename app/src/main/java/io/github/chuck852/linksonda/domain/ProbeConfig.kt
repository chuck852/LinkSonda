package io.github.chuck852.linksonda.domain

import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ProbeId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class IpFamily { IPV4, IPV6 }
enum class HttpMethod { GET, HEAD }
enum class DnsTransport { UDP, TCP }
enum class TlsVersion { TLS_1_2, TLS_1_3 }

@Serializable
data class SocketEndpoint(val host: String, val port: Int, val family: IpFamily? = null) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
    }
}

@Serializable
data class HttpExpectation(@Contextual val status: IntRange, val bodyMarker: String? = null) {
    init {
        require(!status.isEmpty() && status.first >= 100 && status.last <= 599)
        require(bodyMarker == null || bodyMarker.isNotEmpty())
    }
}

@Serializable
data class DnsQuestion(val name: String, val recordType: Int) {
    init {
        require(name.isNotBlank())
        require(recordType in 1..65535)
    }
}

@Serializable
data class DnsExpectation(val responseCode: Int, val requiresAnswer: Boolean) {
    init {
        require(responseCode in 0..4095)
        require(responseCode == 0 || !requiresAnswer)
    }
}

@Serializable
sealed interface ProbeRequest {
    @Serializable
    @SerialName("tcp")
    data class TcpConnect(val endpoint: SocketEndpoint) : ProbeRequest

    @Serializable
    @SerialName("tls")
    data class TlsHandshake(
        val endpoint: SocketEndpoint,
        val serverName: String? = null,
        val minimumVersion: TlsVersion? = null,
    ) : ProbeRequest {
        init { require(serverName == null || serverName.isNotBlank()) }
    }

    @Serializable
    @SerialName("http")
    data class Http(
        @Contextual val url: URI,
        val method: HttpMethod,
        val expected: HttpExpectation,
        val family: IpFamily? = null,
    ) : ProbeRequest {
        init {
            requireWebEndpoint(url, "http", "https")
            require(method != HttpMethod.HEAD || expected.bodyMarker == null)
        }
    }

    @Serializable
    @SerialName("system_dns")
    data class SystemDns(
        val question: DnsQuestion,
        val expected: DnsExpectation,
    ) : ProbeRequest

    @Serializable
    @SerialName("direct_dns")
    data class DirectDns(
        val resolver: SocketEndpoint,
        val transport: DnsTransport,
        val question: DnsQuestion,
        val expected: DnsExpectation,
    ) : ProbeRequest

    @Serializable
    @SerialName("dns_over_https")
    data class DnsOverHttps(
        @Contextual val resolver: URI,
        val question: DnsQuestion,
        val expected: DnsExpectation,
        val family: IpFamily? = null,
    ) : ProbeRequest {
        init { requireWebEndpoint(resolver, "https") }
    }

    @Serializable
    @SerialName("dns_over_tls")
    data class DnsOverTls(
        val resolver: SocketEndpoint,
        val serverName: String,
        val question: DnsQuestion,
        val expected: DnsExpectation,
    ) : ProbeRequest {
        init { require(serverName.isNotBlank()) }
    }

    @Serializable
    @SerialName("icmp")
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

@Serializable
@JvmInline
value class ProbeRevision(val value: Long) {
    init { require(value > 0) }
}

@Serializable
data class ProbeKey(val id: ProbeId, val revision: ProbeRevision)

@ConsistentCopyVisibility
data class ProbeConfig private constructor(
    val id: ProbeId,
    val name: String,
    val request: ProbeRequest,
    val timeout: Duration,
    val revision: ProbeRevision,
) {
    constructor(id: ProbeId, name: String, request: ProbeRequest, timeout: Duration) :
        this(id, name, request, timeout, ProbeRevision(1))

    init {
        require(name.isNotBlank())
        require(timeout.isFinite() && timeout.isPositive())
        // Room stores the timeout in milliseconds; sub-millisecond values would change execution on restore.
        require(timeout == timeout.inWholeMilliseconds.milliseconds) {
            "Probe timeout must be a whole number of milliseconds"
        }
    }

    val key: ProbeKey get() = ProbeKey(id, revision)

    /** Edit the current persisted revision. Every change to execution semantics starts a new history. */
    fun update(
        name: String = this.name,
        request: ProbeRequest = this.request,
        timeout: Duration = this.timeout,
    ): ProbeConfig {
        val updatedRevision = if (request != this.request || timeout != this.timeout) {
            ProbeRevision(Math.incrementExact(revision.value))
        } else {
            revision
        }
        return copy(name = name, request = request, timeout = timeout, revision = updatedRevision)
    }

    companion object {
        /** Restore the definition already stored under this revision. */
        fun restore(key: ProbeKey, name: String, request: ProbeRequest, timeout: Duration): ProbeConfig =
            ProbeConfig(key.id, name, request, timeout, key.revision)
    }
}

private val CREDENTIAL_QUERY_NAMES = setOf(
    "token", "access_token", "api_key", "apikey", "password", "passwd",
    "secret", "signature", "sig", "auth", "authorization",
)

private fun requireWebEndpoint(url: URI, vararg schemes: String) {
    require(schemes.any { it.equals(url.scheme, ignoreCase = true) })
    require(!url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawFragment == null)
    require(url.port == -1 || url.port in 1..65535)
    val query = url.rawQuery
    require(query == null || query.split('&').none { part ->
        part.substringBefore('=').lowercase() in CREDENTIAL_QUERY_NAMES
    }) { "URL query must not contain credential parameters" }
}
