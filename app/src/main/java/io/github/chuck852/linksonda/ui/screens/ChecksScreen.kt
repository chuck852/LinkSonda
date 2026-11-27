package io.github.chuck852.linksonda.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.ui.checks.ChecksFilterState
import io.github.chuck852.linksonda.ui.checks.ChecksStatusFilter
import io.github.chuck852.linksonda.ui.checks.FreshnessBadge
import io.github.chuck852.linksonda.ui.checks.OutcomeBadge
import io.github.chuck852.linksonda.ui.checks.ProbeListItem
import io.github.chuck852.linksonda.ui.checks.ProbeProtocol
import io.github.chuck852.linksonda.ui.checks.applyFilters
import io.github.chuck852.linksonda.ui.checks.display
import io.github.chuck852.linksonda.ui.checks.label
import io.github.chuck852.linksonda.ui.components.DemoBanner
import io.github.chuck852.linksonda.ui.components.EmptyState
import io.github.chuck852.linksonda.ui.overview.GroupUiSummary
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.ui.theme.ProbeLifecycleBadge
import io.github.chuck852.linksonda.ui.theme.ProbeStatusBadge

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChecksScreen(
    state: OverviewUiState,
    filters: ChecksFilterState,
    onFiltersChange: (ChecksFilterState) -> Unit,
    onProbeClick: (ProbeListItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(state.probes, filters) { state.probes.applyFilters(filters) }
    val groups = state.groups

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
    ) {
        if (state.isDemo) {
            item { DemoBanner() }
        }

        if (state.probes.isNotEmpty()) {
            item {
                SearchField(
                    query = filters.query,
                    onQueryChange = { onFiltersChange(filters.copy(query = it)) },
                )
            }
            item {
                ChecksFilterRow(
                    filters = filters,
                    groups = groups,
                    onFiltersChange = onFiltersChange,
                )
            }
            if (filters.hasActiveFilters) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { onFiltersChange(ChecksFilterState()) }) {
                            Text(stringResource(R.string.checks_filters_reset))
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall)) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.action_add_check),
                            modifier = Modifier.padding(start = LinkSondaSpacing.small),
                        )
                    }
                    Text(
                        text = stringResource(R.string.action_add_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when {
            state.probes.isEmpty() -> item {
                EmptyState(
                    title = R.string.checks_empty_title,
                    body = R.string.checks_empty_body,
                )
            }

            filtered.isEmpty() -> item {
                EmptyState(
                    title = R.string.checks_no_matches_title,
                    body = R.string.checks_no_matches_body,
                )
            }

            else -> {
                item {
                    Text(
                        text = if (filters.hasActiveFilters) {
                            pluralStringResource(
                                R.plurals.checks_filtered_count,
                                state.probes.size,
                                filtered.size,
                                state.probes.size,
                            )
                        } else {
                            pluralStringResource(R.plurals.checks_count, filtered.size, filtered.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(filtered, key = { it.id.value }) { probe ->
                    ProbeRow(probe = probe, onClick = { onProbeClick(probe) })
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.checks_search_label)) },
        leadingIcon = {
            Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.checks_search_clear),
                    )
                }
            }
        },
        singleLine = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChecksFilterRow(
    filters: ChecksFilterState,
    groups: List<GroupUiSummary>,
    onFiltersChange: (ChecksFilterState) -> Unit,
) {
    val all = stringResource(R.string.checks_filter_all)
    val selectedGroup = groups.firstOrNull { it.id == filters.groupId }?.name
    val groupOptions = buildList {
        add(FilterMenuOption(all, filters.groupId == null) { onFiltersChange(filters.copy(groupId = null)) })
        for (group in groups) {
            add(
                FilterMenuOption(group.name, filters.groupId == group.id) {
                    onFiltersChange(filters.copy(groupId = group.id))
                },
            )
        }
    }
    val statusOptions = ChecksStatusFilter.entries.map { status ->
        val label = stringResource(status.label())
        FilterMenuOption(label, filters.status == status) {
            onFiltersChange(filters.copy(status = status))
        }
    }
    val protocolOptions = buildList {
        add(FilterMenuOption(all, filters.protocol == null) { onFiltersChange(filters.copy(protocol = null)) })
        for (protocol in ProbeProtocol.entries) {
            val label = stringResource(protocol.label())
            add(
                FilterMenuOption(label, filters.protocol == protocol) {
                    onFiltersChange(filters.copy(protocol = protocol))
                },
            )
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
    ) {
        FilterMenuChip(
            text = stringResource(R.string.checks_filter_group, selectedGroup ?: all),
            selected = filters.groupId != null,
            options = groupOptions,
        )
        FilterMenuChip(
            text = stringResource(R.string.checks_filter_status, stringResource(filters.status.label())),
            selected = filters.status != ChecksStatusFilter.ALL,
            options = statusOptions,
        )
        FilterMenuChip(
            text = stringResource(
                R.string.checks_filter_protocol,
                filters.protocol?.let { stringResource(it.label()) } ?: all,
            ),
            selected = filters.protocol != null,
            options = protocolOptions,
        )
    }
}

private data class FilterMenuOption(
    val label: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun FilterMenuChip(
    text: String,
    selected: Boolean,
    options: List<FilterMenuOption>,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { expanded = true },
            label = {
                Text(
                    text = text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        option.onClick()
                        expanded = false
                    },
                    leadingIcon = if (option.active) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ProbeRow(
    probe: ProbeListItem,
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
                text = probe.config.name,
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
            ) {
                ProbeStatusBadge(status = probe.status)
                if (probe.freshness != io.github.chuck852.linksonda.domain.ProbeFreshness.FRESH) {
                    FreshnessBadge(freshness = probe.freshness)
                }
                OutcomeBadge(item = probe)
                if (probe.state.lifecycle != io.github.chuck852.linksonda.domain.ProbeLifecycle.IDLE) {
                    ProbeLifecycleBadge(lifecycle = probe.state.lifecycle)
                }
            }
            Text(
                text = "${probe.groupName} · ${stringResource(probe.protocol.label())}",
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
            ProbeDetailLine(probe)
        }
    }
}

@Composable
private fun ProbeDetailLine(probe: ProbeListItem) {
    val completed = probe.lastCompleted
    val skipped = probe.lastSkipped
    if (completed != null) {
        when (val outcome = completed.outcome) {
            ProbeOutcome.Matched -> DetailText(
                text = stringResource(R.string.result_elapsed, completed.timing.elapsed.display()),
                icon = null,
                monospace = true,
            )

            is ProbeOutcome.Failed -> DetailText(
                text = stringResource(R.string.result_failed, stringResource(outcome.reason.label())),
                icon = Icons.Rounded.Warning,
                error = true,
            )

            is ProbeOutcome.Inconclusive -> DetailText(
                text = stringResource(R.string.result_inconclusive, stringResource(outcome.reason.label())),
                icon = Icons.Rounded.Warning,
            )

            is ProbeOutcome.Restricted -> DetailText(
                text = stringResource(R.string.result_restricted, stringResource(outcome.reason.label())),
                icon = Icons.Rounded.Warning,
            )
        }
    }
    if (skipped != null) {
        DetailText(
            text = stringResource(R.string.result_skipped, stringResource(skipped.reason.label())),
            icon = Icons.Rounded.Info,
        )
    }
    if (completed == null && skipped == null) {
        DetailText(text = stringResource(R.string.result_no_result), icon = Icons.Rounded.Info)
    }
}

@Composable
private fun DetailText(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    monospace: Boolean = false,
    error: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChecksStatusFilter.label(): Int = when (this) {
    ChecksStatusFilter.ALL -> R.string.checks_filter_all
    ChecksStatusFilter.UP -> R.string.status_up
    ChecksStatusFilter.PENDING -> R.string.status_pending
    ChecksStatusFilter.DOWN -> R.string.status_down
    ChecksStatusFilter.UNKNOWN -> R.string.status_unknown
    ChecksStatusFilter.STALE -> R.string.freshness_stale
}
