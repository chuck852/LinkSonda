package io.github.chuck852.linksonda.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeStatus

internal data class StatusVisual(
    @StringRes val label: Int,
    val tone: StatusTone,
    val icon: ImageVector,
)

internal fun AggregateStatus.visual(): StatusVisual = when (this) {
    AggregateStatus.AVAILABLE -> StatusVisual(R.string.status_available, StatusTone.POSITIVE, Icons.Rounded.CheckCircle)
    AggregateStatus.PARTIAL -> StatusVisual(R.string.status_partial, StatusTone.WARNING, Icons.Rounded.Warning)
    AggregateStatus.UNAVAILABLE -> StatusVisual(R.string.status_unavailable, StatusTone.NEGATIVE, Icons.Rounded.Close)
    AggregateStatus.UNKNOWN -> StatusVisual(R.string.status_unknown, StatusTone.NEUTRAL, Icons.Rounded.Info)
}

internal fun ProbeStatus.visual(): StatusVisual = when (this) {
    ProbeStatus.UP -> StatusVisual(R.string.status_up, StatusTone.POSITIVE, Icons.Rounded.CheckCircle)
    ProbeStatus.PENDING -> StatusVisual(R.string.status_pending, StatusTone.WARNING, Icons.Rounded.Warning)
    ProbeStatus.DOWN -> StatusVisual(R.string.status_down, StatusTone.NEGATIVE, Icons.Rounded.Close)
    ProbeStatus.UNKNOWN -> StatusVisual(R.string.status_unknown, StatusTone.NEUTRAL, Icons.Rounded.Info)
}

/** Semantic color for compact indicators that cannot use [ProbeStatusBadge]. */
fun ProbeStatus.semanticTone(): StatusTone = visual().tone

internal fun ProbeLifecycle.visual(): StatusVisual? = when (this) {
    ProbeLifecycle.IDLE -> null
    ProbeLifecycle.RUNNING -> StatusVisual(R.string.status_running, StatusTone.RUNNING, Icons.Rounded.Refresh)
    ProbeLifecycle.PAUSED -> StatusVisual(R.string.status_paused, StatusTone.NEUTRAL, Icons.Rounded.Info)
}

@Composable
fun StatusBadge(
    label: String,
    tone: StatusTone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = LinkSondaTheme.semanticColors.forTone(tone)
    Surface(
        modifier = modifier.heightIn(min = 32.dp),
        color = colors.container,
        contentColor = colors.onContainer,
        shape = RoundedCornerShape(percent = 50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AggregateStatusBadge(
    status: AggregateStatus,
    modifier: Modifier = Modifier,
) {
    val visual = status.visual()
    StatusBadge(
        label = stringResource(visual.label),
        tone = visual.tone,
        icon = visual.icon,
        modifier = modifier,
    )
}

@Composable
fun ProbeStatusBadge(
    status: ProbeStatus,
    modifier: Modifier = Modifier,
) {
    val visual = status.visual()
    StatusBadge(
        label = stringResource(visual.label),
        tone = visual.tone,
        icon = visual.icon,
        modifier = modifier,
    )
}

/** Renders execution state separately from the last result. */
@Composable
fun ProbeLifecycleBadge(
    lifecycle: ProbeLifecycle,
    modifier: Modifier = Modifier,
) {
    val visual = lifecycle.visual() ?: return
    StatusBadge(
        label = stringResource(visual.label),
        tone = visual.tone,
        icon = visual.icon,
        modifier = modifier,
    )
}
