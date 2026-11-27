package io.github.chuck852.linksonda.ui.checks

import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeOutcome
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

sealed interface ProbeEditorStatus {
    data object Running : ProbeEditorStatus
    data class TestCompleted(
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
