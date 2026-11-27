package io.github.chuck852.linksonda.domain

import kotlin.time.Duration

/** Persistable protocol facts. A missing field means the runner did not observe it. */
data class ProbeObservations(
    val connectedIpFamily: IpFamily? = null,
    val http: HttpObservation? = null,
    val dns: DnsObservation? = null,
    val tls: TlsObservation? = null,
    val icmp: IcmpObservation? = null,
)

data class HttpObservation(val statusCode: Int, val bodyMarkerMatched: Boolean? = null) {
    init { require(statusCode in 100..599) }
}

data class DnsObservation(
    val responseCode: Int? = null,
    val answerCount: Int? = null,
    val truncated: Boolean? = null,
) {
    init {
        require(responseCode == null || responseCode in 0..4095)
        require(answerCount == null || answerCount >= 0)
    }
}

data class TlsObservation(val negotiatedVersion: TlsVersion)

data class IcmpObservation(
    val sentPackets: Int,
    val receivedReplies: Int,
    val rtt: RttSummary? = null,
) {
    init {
        require(sentPackets > 0 && receivedReplies in 0..sentPackets)
        require(receivedReplies > 0 || rtt == null)
    }

    val lostPackets: Int get() = sentPackets - receivedReplies
    val lossFraction: Double get() = lostPackets.toDouble() / sentPackets
}

data class RttSummary(val minimum: Duration, val average: Duration, val maximum: Duration) {
    init {
        require(minimum >= Duration.ZERO && minimum <= average && average <= maximum && maximum.isFinite())
    }
}
