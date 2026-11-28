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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.ui.checks.ProbeProtocol
import io.github.chuck852.linksonda.ui.checks.toProtocol
import io.github.chuck852.linksonda.ui.checks.label
import io.github.chuck852.linksonda.ui.components.LabeledValue
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(
    profile: MonitoringProfile,
    isBusy: Boolean,
    canSave: Boolean,
    onSave: (MonitoringProfile) -> Unit,
    onRenameGroup: (oldName: String, newName: String) -> Unit,
    onEditCheck: (ProbeConfig) -> Unit,
    onDeleteCheck: (ProbeConfig) -> Unit,
    onAddCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by rememberSaveable(profile.id.value, stateSaver = ProfileEditorStateSaver) {
        mutableStateOf(ProfileEditorState.from(profile))
    }
    var showValidation by rememberSaveable(profile.id.value) { mutableStateOf(false) }
    var groupToRename by rememberSaveable { mutableStateOf<String?>(null) }
    var checkToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val validation = state.validate(profile)
    val errors = if (showValidation) validation.errors else emptyMap()
    val pendingCheck = profile.preset.probes.firstOrNull { it.id.value == checkToDelete }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
    ) {
        item {
            SettingsCard(
                state = state,
                errors = errors,
                onStateChange = { state = it },
            )
        }
        item { EffectiveCard(profile, state) }
        item {
            GroupsCard(
                profile = profile,
                onRenameGroup = { groupToRename = it },
            )
        }
        item {
            ChecksCard(
                profile = profile,
                onEdit = onEditCheck,
                onDelete = { checkToDelete = it.id.value },
                onAddCheck = onAddCheck,
            )
        }
        item {
            Button(
                onClick = {
                    showValidation = true
                    validation.profile?.let(onSave)
                },
                enabled = canSave && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (isBusy) R.string.profile_saving else R.string.profile_save,
                    ),
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.profile_no_traffic_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val renameTarget = groupToRename
    if (renameTarget != null) {
        RenameGroupDialog(
            profile = profile,
            oldName = renameTarget,
            onDismiss = { groupToRename = null },
            onConfirm = { newName ->
                groupToRename = null
                onRenameGroup(renameTarget, newName)
            },
        )
    }
    if (pendingCheck != null) {
        AlertDialog(
            onDismissRequest = { checkToDelete = null },
            title = { Text(stringResource(R.string.profile_delete_check_title)) },
            text = { Text(stringResource(R.string.profile_delete_check_body, pendingCheck.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        checkToDelete = null
                        onDeleteCheck(pendingCheck)
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { checkToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    state: ProfileEditorState,
    errors: Map<ProfileSettingsField, ProfileSettingsError>,
    onStateChange: (ProfileEditorState) -> Unit,
) {
    EditorCard {
        SectionHeader(stringResource(R.string.profile_settings_title))
        OutlinedTextField(
            value = state.name,
            onValueChange = { onStateChange(state.copy(name = it)) },
            label = { Text(stringResource(R.string.profile_name_label)) },
            isError = errors[ProfileSettingsField.NAME] != null,
            supportingText = errors[ProfileSettingsField.NAME]?.let { error ->
                { Text(stringResource(error.label())) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.profile_interval_label),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
        ) {
            for (seconds in PROFILE_INTERVAL_OPTIONS) {
                FilterChip(
                    selected = state.intervalSeconds == seconds,
                    onClick = { onStateChange(state.copy(intervalSeconds = seconds)) },
                    enabled = seconds in PROFILE_ALLOWED_INTERVALS,
                    label = { Text(stringResource(R.string.profile_interval_option, seconds)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.profile_interval_10_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        errors[ProfileSettingsField.INTERVAL]?.let { error ->
            Text(
                text = stringResource(error.label()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        ) {
            NumberField(
                value = state.maxConcurrentProbes,
                onValueChange = { onStateChange(state.copy(maxConcurrentProbes = it)) },
                label = stringResource(R.string.profile_concurrency_label),
                error = errors[ProfileSettingsField.MAX_CONCURRENCY],
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        ) {
            NumberField(
                value = state.consecutiveFailures,
                onValueChange = { onStateChange(state.copy(consecutiveFailures = it)) },
                label = stringResource(R.string.profile_failures_label),
                error = errors[ProfileSettingsField.FAILURES],
                modifier = Modifier.weight(1f),
            )
            NumberField(
                value = state.consecutiveSuccesses,
                onValueChange = { onStateChange(state.copy(consecutiveSuccesses = it)) },
                label = stringResource(R.string.profile_successes_label),
                error = errors[ProfileSettingsField.SUCCESSES],
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profile_notifications_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = { onStateChange(state.copy(notificationsEnabled = it)) },
            )
        }
        if (state.notificationsEnabled) {
            NumberField(
                value = state.repeatMinutes,
                onValueChange = { onStateChange(state.copy(repeatMinutes = it)) },
                label = stringResource(R.string.profile_repeat_label),
                error = errors[ProfileSettingsField.REPEAT],
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EffectiveCard(profile: MonitoringProfile, state: ProfileEditorState) {
    EditorCard {
        SectionHeader(stringResource(R.string.profile_effective_title))
        LabeledValue(
            label = stringResource(R.string.profile_configured_interval),
            value = stringResource(R.string.profile_interval_short, state.intervalSeconds),
        )
        LabeledValue(
            label = stringResource(R.string.profile_concurrency_value),
            value = stringResource(
                R.string.profile_concurrency_configured,
                state.maxConcurrentProbes.toIntOrNull() ?: 0,
            ),
        )
        Text(
            text = stringResource(R.string.profile_effective_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (profile.groups.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_no_groups_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupsCard(
    profile: MonitoringProfile,
    onRenameGroup: (String) -> Unit,
) {
    EditorCard {
        SectionHeader(stringResource(R.string.profile_groups_title))
        if (profile.groups.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_groups_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (group in profile.groups) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = pluralStringResource(
                                R.plurals.profile_check_count,
                                group.probeIds.size,
                                group.probeIds.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRenameGroup(group.name) }) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.profile_rename_group),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecksCard(
    profile: MonitoringProfile,
    onEdit: (ProbeConfig) -> Unit,
    onDelete: (ProbeConfig) -> Unit,
    onAddCheck: () -> Unit,
) {
    EditorCard {
        SectionHeader(stringResource(R.string.profile_checks_title))
        if (profile.preset.probes.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_checks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (probe in profile.preset.probes) {
                val groupName = profile.groups.firstOrNull { probe.id in it.probeIds }?.name
                    ?: stringResource(R.string.profile_group_ungrouped)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = probe.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.profile_check_detail,
                                stringResource(probe.request.toProtocol().label()),
                                groupName,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onEdit(probe) }) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                    IconButton(onClick = { onDelete(probe) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onAddCheck,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.action_add_check),
                modifier = Modifier.padding(start = LinkSondaSpacing.small),
            )
        }
    }
}

@Composable
private fun RenameGroupDialog(
    profile: MonitoringProfile,
    oldName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(oldName) { mutableStateOf(oldName) }
    val name = value.trim()
    val duplicate = profile.groups.any { it.name == name && it.name != oldName }
    val invalid = name.isBlank() || duplicate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_rename_group_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.profile_group_name_label)) },
                    isError = invalid,
                    singleLine = true,
                )
                if (duplicate) {
                    Text(
                        text = stringResource(R.string.profile_group_duplicate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = !invalid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: ProfileSettingsError?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.label())) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

@Composable
private fun EditorCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
            content()
        }
    }
}

private fun ProfileSettingsError.label(): Int = when (this) {
    ProfileSettingsError.REQUIRED -> R.string.profile_error_required
    ProfileSettingsError.INVALID_INTERVAL -> R.string.profile_error_interval
    ProfileSettingsError.INVALID_CONCURRENCY -> R.string.profile_error_concurrency
    ProfileSettingsError.INVALID_THRESHOLD -> R.string.profile_error_threshold
    ProfileSettingsError.INVALID_REPEAT -> R.string.profile_error_repeat
}
