package io.github.chuck852.linksonda.domain

import java.time.Instant
import kotlin.time.Duration
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class NetworkId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class NetworkTransport { WIFI, CELLULAR, ETHERNET, VPN, OTHER }
enum class NetworkValidation { UNKNOWN, VALIDATED, CAPTIVE_PORTAL, UNVALIDATED }

data class NetworkSnapshot(
    val id: NetworkId,
    val capturedAt: Instant,
    val transports: Set<NetworkTransport>,
    val ipFamilies: Set<IpFamily>,
    val validation: NetworkValidation,
)

/** A missing network ID represents a slot with no active network. */
@Serializable
data class ProbeContext(val probe: ProbeKey, val networkId: NetworkId?)

enum class FailureReason {
    DNS, TCP, TLS, HTTP, ICMP, TIMEOUT, REFUSED, UNREACHABLE, UNEXPECTED_RESPONSE,
    UNEXPECTED_HTTP_STATUS, UNEXPECTED_DNS_RESPONSE_CODE,
}

enum class RestrictionReason { RATE_LIMITED, ACCESS_DENIED, CHALLENGE }
enum class InconclusiveReason { OS_LIMITATION, DOZE, NETWORK_CHANGED, PERMISSION_DENIED }
enum class SkipReason { SLEEP, BUDGET, COOLDOWN, NOT_ALLOWED, NO_NETWORK, UNSUPPORTED, CANCELLED, PERMISSION_DENIED }
enum class ProbePhase { DNS, TCP_CONNECT, TLS_HANDSHAKE, HTTP_FIRST_BYTE }

@Serializable
sealed interface ProbeOutcome {
    @Serializable
    @SerialName("matched")
    data object Matched : ProbeOutcome

    @Serializable
    @SerialName("failed")
    data class Failed(val reason: FailureReason) : ProbeOutcome

    @Serializable
    @SerialName("inconclusive")
    data class Inconclusive(val reason: InconclusiveReason) : ProbeOutcome

    @Serializable
    @SerialName("restricted")
    data class Restricted(
        val reason: RestrictionReason,
        @Contextual val retryAt: Instant? = null,
    ) : ProbeOutcome
}

@Serializable
data class ProbeTiming(
    @Contextual val elapsed: Duration,
    val phases: Map<ProbePhase, @Contextual Duration> = emptyMap(),
) {
    init {
        require(elapsed.isFinite() && !elapsed.isNegative())
        require(phases.values.all { it.isFinite() && it in Duration.ZERO..elapsed })
    }
}

@Serializable
sealed interface ProbeResult {
    val context: ProbeContext
    @Contextual val recordedAt: Instant

    @Serializable
    @SerialName("completed")
    data class Completed(
        override val context: ProbeContext,
        @Contextual override val recordedAt: Instant,
        val timing: ProbeTiming,
        val outcome: ProbeOutcome,
        val observations: ProbeObservations = ProbeObservations(),
    ) : ProbeResult {
        init { require(context.networkId != null) }
    }

    @Serializable
    @SerialName("skipped")
    data class Skipped(
        override val context: ProbeContext,
        @Contextual override val recordedAt: Instant,
        val reason: SkipReason,
        @Contextual val nextAllowedAt: Instant? = null,
    ) : ProbeResult
}

enum class ProbeStatus { UP, PENDING, DOWN, UNKNOWN }
enum class ProbeLifecycle { IDLE, RUNNING, PAUSED }
enum class ProbeFreshness { MISSING, FRESH, STALE }
enum class AggregateStatus { AVAILABLE, PARTIAL, UNAVAILABLE, UNKNOWN }

data class ProbeState(
    val context: ProbeContext,
    val status: ProbeStatus,
    val lifecycle: ProbeLifecycle,
    val freshness: ProbeFreshness,
    val lastCompleted: ProbeResult.Completed? = null,
    val lastSkipped: ProbeResult.Skipped? = null,
) {
    init {
        require(lastCompleted == null || lastCompleted.context == context)
        require(lastSkipped == null || lastSkipped.context == context)
        require((lastCompleted == null) == (freshness == ProbeFreshness.MISSING))
        require(lastCompleted != null || status == ProbeStatus.UNKNOWN)
    }
}
