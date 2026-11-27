package io.github.chuck852.linksonda.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.ui.components.DemoBanner
import io.github.chuck852.linksonda.ui.components.EmptyState
import io.github.chuck852.linksonda.ui.components.LabeledValue
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.AggregateStatusBadge
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.ui.theme.LinkSondaTheme
import io.github.chuck852.linksonda.ui.theme.ProbeLifecycleBadge
import io.github.chuck852.linksonda.ui.theme.semanticTone

@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onGroupClick: (GroupUiSummary) -> Unit,
    onRunManualCheck: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
    ) {
        if (state.isDemo) {
            item { DemoBanner() }
        }

        item { AvailabilityCard(state) }
        item { ProfileCard(state.activeProfile) }

        item { SectionHeader(stringResource(R.string.overview_groups_title)) }
        if (state.groups.isEmpty()) {
            item {
                EmptyState(
                    title = R.string.overview_groups_empty_title,
                    body = R.string.overview_groups_empty_body,
                )
            }
        } else {
            items(state.groups, key = { it.id }) { group ->
                GroupCard(
                    group = group,
                    probes = state.probesFor(group.id),
                    onClick = { onGroupClick(group) },
                )
            }
        }

        item {
            ManualCheckCard(
                state = state.manualCheck,
                isDemo = state.isDemo,
                onRun = onRunManualCheck,
            )
        }
    }
}

@Composable
private fun AvailabilityCard(state: OverviewUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.overview_availability_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AggregateStatusBadge(status = state.aggregateStatus)
            Text(
                text = freshnessText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LabeledValue(
                label = stringResource(R.string.overview_monitoring_label),
                value = stringResource(state.monitoring.label()),
            )
            LabeledValue(
                label = stringResource(R.string.overview_network_label),
                value = networkText(state.network),
            )
        }
    }
}

@Composable
private fun ProfileCard(profile: ProfileUiSummary?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
        ) {
            Text(
                text = stringResource(R.string.overview_profile_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = profile?.name ?: stringResource(R.string.overview_profile_none),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (profile != null) {
                Text(
                    text = stringResource(R.string.overview_profile_schedule, profile.intervalSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    group: GroupUiSummary,
    probes: List<ProbeUiSummary>,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LinkSondaSpacing.screen),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
            ) {
                AggregateStatusBadge(status = group.status)
                Text(
                    text = pluralStringResource(
                        R.plurals.overview_group_probes,
                        group.totalProbes,
                        group.availableProbes,
                        group.totalProbes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AvailabilityBar(
                statuses = probes.map { it.status },
                totalProbes = group.totalProbes,
            )
        }
    }
}

@Composable
private fun AvailabilityBar(statuses: List<ProbeStatus>, totalProbes: Int) {
    val segments = when {
        statuses.isNotEmpty() -> statuses
        totalProbes > 0 -> List(totalProbes) { ProbeStatus.UNKNOWN }
        else -> listOf(ProbeStatus.UNKNOWN)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (status in segments) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(LinkSondaTheme.semanticColors.forTone(status.semanticTone()).accent),
            )
        }
    }
}

@Composable
private fun ManualCheckCard(
    state: ManualCheckState,
    isDemo: Boolean,
    onRun: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.overview_manual_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.overview_manual_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state == ManualCheckState.RUNNING) {
                ProbeLifecycleBadge(lifecycle = ProbeLifecycle.RUNNING)
                Text(
                    text = stringResource(R.string.overview_manual_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state == ManualCheckState.UNAVAILABLE) {
                Text(
                    text = stringResource(R.string.overview_manual_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isDemo && state == ManualCheckState.IDLE) {
                Text(
                    text = stringResource(R.string.overview_manual_demo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { onRun?.invoke() },
                enabled = onRun != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(LinkSondaSpacing.small))
                Text(stringResource(R.string.action_run_check))
            }
        }
    }
}

@Composable
private fun freshnessText(state: OverviewUiState): String {
    val seconds = state.lastUpdatedSecondsAgo
    return when {
        state.freshness == OverviewFreshness.MISSING -> stringResource(R.string.overview_freshness_missing)
        seconds == null && state.freshness == OverviewFreshness.FRESH ->
            stringResource(R.string.overview_freshness_fresh)

        seconds == null -> stringResource(R.string.overview_freshness_stale)
        state.freshness == OverviewFreshness.FRESH ->
            stringResource(R.string.overview_freshness_updated, seconds)

        else -> stringResource(R.string.overview_freshness_stale_updated, seconds)
    }
}

@Composable
private fun networkText(network: NetworkUiSummary): String {
    if (!network.contextKnown) {
        return stringResource(R.string.overview_network_unknown)
    }
    if (network.transports.isEmpty()) {
        return stringResource(R.string.overview_network_none)
    }
    val parts = mutableListOf<String>()
    for (transport in network.transports.sortedBy { it.ordinal }) {
        parts += stringResource(transport.label())
    }
    parts += stringResource(network.validation.label())
    return parts.joinToString(" · ")
}

private fun OverviewMonitoring.label(): Int = when (this) {
    OverviewMonitoring.NOT_CONFIGURED -> R.string.overview_monitoring_not_configured
    OverviewMonitoring.RUNNING -> R.string.overview_monitoring_running
    OverviewMonitoring.PAUSED -> R.string.overview_monitoring_paused
}

private fun NetworkTransport.label(): Int = when (this) {
    NetworkTransport.WIFI -> R.string.transport_wifi
    NetworkTransport.CELLULAR -> R.string.transport_cellular
    NetworkTransport.ETHERNET -> R.string.transport_ethernet
    NetworkTransport.VPN -> R.string.transport_vpn
    NetworkTransport.OTHER -> R.string.transport_other
}

private fun NetworkValidation.label(): Int = when (this) {
    NetworkValidation.VALIDATED -> R.string.network_validation_validated
    NetworkValidation.CAPTIVE_PORTAL -> R.string.network_validation_captive_portal
    NetworkValidation.UNVALIDATED -> R.string.network_validation_unvalidated
    NetworkValidation.UNKNOWN -> R.string.network_validation_unknown
}
