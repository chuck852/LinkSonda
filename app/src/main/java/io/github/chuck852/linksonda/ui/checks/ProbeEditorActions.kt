package io.github.chuck852.linksonda.ui.checks

import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import kotlin.time.Duration

data class ProbeDraft(
    val config: ProbeConfig,
    val groupId: String,
    val groupName: String,
) {
    init {
        require(groupId.isNotBlank() && groupName.isNotBlank())
    }
}

data class ProbeTestOutcome(
    val outcome: ProbeOutcome,
    val elapsed: Duration,
    val isDemo: Boolean,
)

/**
 * Execution parameters captured when a test starts.
 *
 * A revision alone is not enough: an unsaved new probe keeps revision 1, and different unsaved
 * edits of a saved probe share the same incremented revision. Comparing the whole snapshot makes
 * sure the result disappears as soon as the request, timeout, or probe identity changes.
 */
data class ProbeExecutionSnapshot(
    val probeId: ProbeId,
    val request: ProbeRequest,
    val timeout: Duration,
) {
    companion object {
        fun of(config: ProbeConfig): ProbeExecutionSnapshot = ProbeExecutionSnapshot(
            probeId = config.id,
            request = config.request,
            timeout = config.timeout,
        )
    }
}

sealed interface ProbeEditorStatus {
    data object Running : ProbeEditorStatus
    data class TestCompleted(
        val snapshot: ProbeExecutionSnapshot,
        val outcome: ProbeOutcome,
        val elapsed: Duration,
        val isDemo: Boolean,
    ) : ProbeEditorStatus
    data object TestFailed : ProbeEditorStatus
    data object SaveFailed : ProbeEditorStatus
}

/**
 * UI boundary for the future profile repository and network runner.
 *
 * A null callback means the capability is not connected in this build and the editor must show the
 * action as unavailable. The editor never writes storage or starts a request by itself.
 * Implementations may use [io.github.chuck852.linksonda.domain.ProfileRepository] and
 * [io.github.chuck852.linksonda.domain.ProbeRunner]; the debug harness does exactly that.
 */
data class ProbeEditorActions(
    val onSave: (suspend (ProbeDraft) -> ProbeConfig?)? = null,
    val onTest: (suspend (ProbeConfig) -> ProbeTestOutcome)? = null,
)

/** Hide a completed test when the draft no longer has the tested execution parameters. */
fun ProbeEditorStatus.forConfiguration(config: ProbeConfig?): ProbeEditorStatus? = when (this) {
    is ProbeEditorStatus.TestCompleted ->
        takeIf { config != null && ProbeExecutionSnapshot.of(config) == snapshot }
    else -> this
}
