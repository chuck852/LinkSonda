package io.github.chuck852.linksonda.ui.checks

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.ui.theme.StatusBadge
import io.github.chuck852.linksonda.ui.theme.StatusTone

@Composable
fun FreshnessBadge(freshness: ProbeFreshness, modifier: Modifier = Modifier) {
    val (label, tone, icon) = when (freshness) {
        ProbeFreshness.FRESH -> Triple(R.string.freshness_fresh, StatusTone.POSITIVE, Icons.Rounded.CheckCircle)
        ProbeFreshness.STALE -> Triple(R.string.freshness_stale, StatusTone.WARNING, Icons.Rounded.Warning)
        ProbeFreshness.MISSING -> Triple(R.string.freshness_missing, StatusTone.NEUTRAL, Icons.Rounded.Info)
    }
    StatusBadge(
        label = stringResource(label),
        tone = tone,
        icon = icon,
        modifier = modifier,
    )
}

@Composable
fun OutcomeBadge(item: ProbeListItem, modifier: Modifier = Modifier) {
    if (item.lastSkipped != null) {
        StatusBadge(
            label = stringResource(R.string.outcome_skipped),
            tone = StatusTone.NEUTRAL,
            icon = Icons.Rounded.Info,
            modifier = modifier,
        )
        return
    }
    when (item.lastCompleted?.outcome) {
        is ProbeOutcome.Inconclusive -> StatusBadge(
            label = stringResource(R.string.outcome_inconclusive),
            tone = StatusTone.WARNING,
            icon = Icons.Rounded.Warning,
            modifier = modifier,
        )

        is ProbeOutcome.Restricted -> StatusBadge(
            label = stringResource(R.string.outcome_restricted),
            tone = StatusTone.WARNING,
            icon = Icons.Rounded.Warning,
            modifier = modifier,
        )

        else -> Unit
    }
}
