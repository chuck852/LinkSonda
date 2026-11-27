package io.github.chuck852.linksonda.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.settings.FirstRunProfile
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing

/**
 * First-run setup: explains the local-only model and lets the user pick the initial
 * monitoring profile. Passing `null` to [onComplete] keeps manual checks selected.
 */
@Composable
fun FirstRunScreen(
    profiles: List<FirstRunProfile>,
    onComplete: (profileId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var savedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    // A restored id may no longer be offered; the visible selection stays the source of truth.
    val availableProfileIds = profiles.mapTo(HashSet()) { it.id }
    val selectedProfileId = savedProfileId?.takeIf { it in availableProfileIds }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
    ) {
        item { WelcomeCard() }
        item { LocalDataCard() }
        item {
            ProfileChoiceCard(
                profiles = profiles,
                selectedProfileId = selectedProfileId,
                onSelect = { savedProfileId = it },
            )
        }
        item { NotificationNoteCard() }
        item {
            Button(
                onClick = {
                    // Complete at most once so a double tap cannot run onboarding twice.
                    if (!submitted) {
                        submitted = true
                        onComplete(selectedProfileId)
                    }
                },
                enabled = !submitted,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LinkSondaSpacing.minTouchTarget),
            ) {
                Text(stringResource(R.string.first_run_continue))
            }
        }
    }
}

@Composable
private fun WelcomeCard() {
    FirstRunCard {
        Text(
            text = stringResource(R.string.first_run_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.first_run_privacy_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalDataCard() {
    FirstRunCard {
        SectionHeader(stringResource(R.string.first_run_local_title))
        Text(
            text = stringResource(R.string.first_run_local_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileChoiceCard(
    profiles: List<FirstRunProfile>,
    selectedProfileId: String?,
    onSelect: (String?) -> Unit,
) {
    FirstRunCard {
        SectionHeader(stringResource(R.string.first_run_profile_title))
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
        ) {
            RadioOptionRow(
                title = stringResource(R.string.first_run_profile_manual),
                body = stringResource(R.string.first_run_profile_manual_body),
                selected = selectedProfileId == null,
                onClick = { onSelect(null) },
            )
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.first_run_profiles_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (profile in profiles) {
                    RadioOptionRow(
                        title = profile.name,
                        body = profile.description,
                        selected = selectedProfileId == profile.id,
                        onClick = { onSelect(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationNoteCard() {
    FirstRunCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.first_run_notifications_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RadioOptionRow(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedState = stringResource(R.string.settings_state_selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LinkSondaSpacing.minTouchTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                if (selected) {
                    stateDescription = selectedState
                }
            }
            .padding(vertical = LinkSondaSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FirstRunCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
            content = content,
        )
    }
}
