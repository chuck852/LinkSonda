package io.github.chuck852.linksonda.domain

import java.time.Instant
import kotlin.time.Duration

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
data class ProbeContext(val probeId: ProbeId, val networkId: NetworkId?)

enum class FailureReason { DNS, TCP, TLS, HTTP, ICMP, TIMEOUT, REFUSED, UNREACHABLE, UNEXPECTED_RESPONSE }
sealed interface ProbeFailure {
    data class Execution(val reason: FailureReason) : ProbeFailure
    data class UnexpectedHttpStatus(val code: Int) : ProbeFailure {
        init { require(code in 100..599) }
    }
    data class UnexpectedDnsResponseCode(val code: Int) : ProbeFailure {
        init { require(code in 0..4095) }
    }
}
enum class RestrictionReason { RATE_LIMITED, ACCESS_DENIED, CHALLENGE }
enum class SkipReason { SLEEP, BUDGET, COOLDOWN, NOT_ALLOWED, NO_NETWORK, UNSUPPORTED, CANCELLED }
enum class ProbePhase { DNS, TCP_CONNECT, TLS_HANDSHAKE, HTTP_FIRST_BYTE }

sealed interface ProbeOutcome {
    data object Matched : ProbeOutcome
    data class Failed(val failure: ProbeFailure) : ProbeOutcome
    data class Restricted(val reason: RestrictionReason, val retryAt: Instant? = null) : ProbeOutcome
}

data class ProbeTiming(
    val elapsed: Duration,
    val phases: Map<ProbePhase, Duration> = emptyMap(),
) {
    init {
        require(elapsed.isFinite() && !elapsed.isNegative())
        require(phases.values.all { it.isFinite() && it in Duration.ZERO..elapsed })
    }
}

sealed interface ProbeResult {
    val context: ProbeContext
    val recordedAt: Instant

    data class Completed(
        override val context: ProbeContext,
        override val recordedAt: Instant,
        val timing: ProbeTiming,
        val outcome: ProbeOutcome,
    ) : ProbeResult {
        init { require(context.networkId != null) }
    }

    data class Skipped(
        override val context: ProbeContext,
        override val recordedAt: Instant,
        val reason: SkipReason,
        val nextAllowedAt: Instant? = null,
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
