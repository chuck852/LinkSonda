package io.github.chuck852.linksonda.ui.checks

import io.github.chuck852.linksonda.domain.ProbeKey

/**
 * UI boundary for actions that need the runner and the check editor.
 *
 * `onEdit` opens the editor, which owns validation and saving. `onRetry` is still null until the
 * network runner is connected and must be shown disabled with an explanation. The details screen
 * never starts a probe or persists an edit by itself.
 */
data class ProbeActions(
    val onRetry: ((ProbeKey) -> Unit)? = null,
    val onEdit: ((ProbeKey) -> Unit)? = null,
)
