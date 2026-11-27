package io.github.chuck852.linksonda.debug

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeAttempt
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunner
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.ProbeTiming
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Debug-only [ProbeRunner] used by the editor demo.
 *
 * It accepts and returns the real domain contracts but performs no network operation. The returned
 * result is marked as demo by the debug activity, never by production code.
 */
class DemoProbeRunner : ProbeRunner {
    override suspend fun run(request: ProbeRunRequest, admission: ProbeAdmission): ProbeResult {
        val decision = admission.authorize(
            ProbeAttempt(
                endpointKey = "demo:${request.config.id.value}",
                infrastructureKey = "demo",
            ),
        )
        if (decision is AdmissionDecision.Denied) {
            return ProbeResult.Skipped(
                context = request.context,
                recordedAt = Instant.now(),
                reason = decision.reason,
                nextAllowedAt = decision.nextAllowedAt,
            )
        }
        return ProbeResult.Completed(
            context = request.context,
            recordedAt = Instant.now(),
            timing = ProbeTiming(elapsed = 35.milliseconds),
            outcome = ProbeOutcome.Matched,
            observations = ProbeObservations(),
        )
    }
}
