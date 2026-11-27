package io.github.chuck852.linksonda.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.ui.components.DemoBanner
import io.github.chuck852.linksonda.ui.components.EmptyState
import io.github.chuck852.linksonda.ui.navigation.CheckGroupFilter
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import io.github.chuck852.linksonda.ui.overview.ProbeDetail
import io.github.chuck852.linksonda.ui.overview.ProbeFailure
import io.github.chuck852.linksonda.ui.overview.ProbeKind
import io.github.chuck852.linksonda.ui.overview.ProbeUiSummary
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.ui.theme.ProbeStatusBadge

@Composable
fun ChecksScreen(
    state: OverviewUiState,
    filter: CheckGroupFilter?,
    onClearFilter: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val probes = if (filter == null) {
        state.probes
    } else {
        state.probes.filter { it.groupId == filter.groupId }
    }
    val groupName = state.groups.firstOrNull { it.id == filter?.groupId }?.name ?: filter?.groupId.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
    ) {
        if (state.isDemo) {
            item { DemoBanner() }
        }
        if (filter != null) {
            item {
                AssistChip(
                    onClick = { onClearFilter?.invoke() },
                    enabled = onClearFilter != null,
                    label = { Text(stringResource(R.string.checks_filter_label, groupName)) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_clear_filter),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        if (probes.isEmpty()) {
            item {
                EmptyState(
                    title = if (filter == null) R.string.checks_empty_title else R.string.checks_filter_empty_title,
                    body = if (filter == null) R.string.checks_empty_body else R.string.checks_filter_empty_body,
                )
            }
        } else {
            item {
                Text(
                    text = pluralStringResource(R.plurals.checks_count, probes.size, probes.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(probes, key = { it.id }) { probe ->
                ProbeRow(probe)
            }
        }
    }
}

@Composable
private fun ProbeRow(probe: ProbeUiSummary) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LinkSondaSpacing.screen),
            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
            ) {
                Text(
                    text = probe.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(probe.kind.label()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = probe.target,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ProbeDetailText(probe.detail)
            }
            ProbeStatusBadge(status = probe.status)
        }
    }
}

@Composable
private fun ProbeDetailText(detail: ProbeDetail) {
    when (detail) {
        is ProbeDetail.Latency -> Text(
            text = stringResource(R.string.probe_latency, detail.milliseconds),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is ProbeDetail.Failure -> Text(
            text = stringResource(detail.reason.label()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        ProbeDetail.None -> Unit
    }
}

private fun ProbeKind.label(): Int = when (this) {
    ProbeKind.TCP -> R.string.probe_kind_tcp
    ProbeKind.HTTPS -> R.string.probe_kind_https
    ProbeKind.DNS -> R.string.probe_kind_dns
    ProbeKind.TLS -> R.string.probe_kind_tls
    ProbeKind.ICMP -> R.string.probe_kind_icmp
}

private fun ProbeFailure.label(): Int = when (this) {
    ProbeFailure.TIMEOUT -> R.string.probe_failure_timeout
    ProbeFailure.DNS -> R.string.probe_failure_dns
    ProbeFailure.TLS -> R.string.probe_failure_tls
    ProbeFailure.HTTP_STATUS -> R.string.probe_failure_http_status
    ProbeFailure.REFUSED -> R.string.probe_failure_refused
    ProbeFailure.UNREACHABLE -> R.string.probe_failure_unreachable
}
