package io.github.chuck852.linksonda.ui.profiles

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.ui.components.EmptyState
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileListScreen(
    profiles: List<MonitoringProfile>,
    activeProfileId: String?,
    onCreate: () -> Unit,
    onActivate: (MonitoringProfile) -> Unit,
    onEdit: (MonitoringProfile) -> Unit,
    onCopy: (MonitoringProfile) -> Unit,
    onDelete: (MonitoringProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = profiles.firstOrNull { it.id.value == pendingDeleteId }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
    ) {
        item { SectionHeader(stringResource(R.string.profile_builtin_title)) }
        item {
            EmptyState(
                title = R.string.profile_builtin_empty_title,
                body = R.string.profile_builtin_empty_body,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(stringResource(R.string.profile_user_title))
                Text(
                    text = pluralStringResource(R.plurals.profile_count, profiles.size, profiles.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.profile_create),
                    modifier = Modifier.padding(start = LinkSondaSpacing.small),
                )
            }
        }
        if (profiles.isEmpty()) {
            item {
                EmptyState(
                    title = R.string.profile_list_empty_title,
                    body = R.string.profile_list_empty_body,
                )
            }
        } else {
            items(profiles, key = { it.id.value }) { profile ->
                ProfileCard(
                    profile = profile,
                    isActive = profile.id.value == activeProfileId,
                    onActivate = { onActivate(profile) },
                    onEdit = { onEdit(profile) },
                    onCopy = { onCopy(profile) },
                    onDelete = { pendingDeleteId = profile.id.value },
                )
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(
                    text = stringResource(R.string.profile_no_auto_start_note),
                    modifier = Modifier.padding(LinkSondaSpacing.large),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = { Text(stringResource(R.string.profile_delete_body, pendingDelete.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        onDelete(pendingDelete)
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(
    profile: MonitoringProfile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = LinkSondaSpacing.small,
                                vertical = LinkSondaSpacing.extraSmall,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.profile_active),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.profile_check_count,
                        profile.preset.probes.size,
                        profile.preset.probes.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.profile_group_count,
                        profile.groups.size,
                        profile.groups.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.profile_interval_short,
                        profile.schedule.interval.inWholeSeconds,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
            ) {
                if (!isActive) {
                    FilledTonalButton(onClick = onActivate) {
                        Text(stringResource(R.string.profile_activate))
                    }
                }
                OutlinedButton(onClick = onEdit) {
                    Text(stringResource(R.string.profile_edit))
                }
                OutlinedButton(onClick = onCopy) {
                    Text(stringResource(R.string.action_copy))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}
