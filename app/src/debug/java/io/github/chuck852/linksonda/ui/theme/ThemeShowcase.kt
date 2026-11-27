package io.github.chuck852.linksonda.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeStatus

/**
 * Stable layout used by Compose previews and the debug screenshot harness. Only colors change when
 * the confirmed aggregate status changes; spacing, sizes and component order stay fixed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeShowcase(
    aggregateStatus: AggregateStatus,
    themeMode: ThemeMode,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(LinkSondaSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall)) {
                Text(
                    text = stringResource(R.string.theme_preview_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.theme_preview_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                AggregateStatusBadge(status = aggregateStatus)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            ) {
                Button(onClick = {}) {
                    Text(stringResource(R.string.theme_preview_primary))
                }
                FilledTonalButton(onClick = {}) {
                    Text(stringResource(R.string.theme_preview_secondary))
                }
                OutlinedButton(onClick = {}) {
                    Text(stringResource(R.string.theme_preview_outline))
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(LinkSondaSpacing.screen),
                    verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                ) {
                    Text(
                        text = stringResource(R.string.theme_preview_surface_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.theme_preview_surface_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.theme_preview_metric),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Text(
                text = stringResource(R.string.theme_preview_semantics),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            ) {
                StatusBadge(
                    label = stringResource(R.string.status_up),
                    tone = StatusTone.POSITIVE,
                    icon = ProbeStatus.UP.visual().icon,
                )
                StatusBadge(
                    label = stringResource(R.string.status_pending),
                    tone = StatusTone.WARNING,
                    icon = ProbeStatus.PENDING.visual().icon,
                )
                StatusBadge(
                    label = stringResource(R.string.status_down),
                    tone = StatusTone.NEGATIVE,
                    icon = ProbeStatus.DOWN.visual().icon,
                )
                StatusBadge(
                    label = stringResource(R.string.status_unknown),
                    tone = StatusTone.NEUTRAL,
                    icon = ProbeStatus.UNKNOWN.visual().icon,
                )
                ProbeLifecycleBadge(lifecycle = ProbeLifecycle.RUNNING)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            ) {
                Text(
                    text = stringResource(R.string.theme_preview_manual_check),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProbeLifecycleBadge(lifecycle = ProbeLifecycle.RUNNING)
            }

            Text(
                text = "${themeMode.label()} • ${if (dark) stringResource(R.string.theme_preview_dark) else stringResource(R.string.theme_preview_light)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.ADAPTIVE -> R.string.theme_preview_mode_adaptive
        ThemeMode.ACCENTS -> R.string.theme_preview_mode_accents
        ThemeMode.STATIC -> R.string.theme_preview_mode_static
    },
)
