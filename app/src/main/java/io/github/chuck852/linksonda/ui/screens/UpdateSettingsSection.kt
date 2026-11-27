package io.github.chuck852.linksonda.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.BuildConfig
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.settings.AppSettings
import io.github.chuck852.linksonda.settings.UpdateChannel
import io.github.chuck852.linksonda.settings.UpdateCheckStatus
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.updater.DownloadProgress
import io.github.chuck852.linksonda.updater.InstallState
import io.github.chuck852.linksonda.updater.OfferedUpdate
import io.github.chuck852.linksonda.updater.UpdaterError
import io.github.chuck852.linksonda.updater.UpdaterUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class UpdaterActions(
    val checkNow: () -> Unit = {},
    val download: () -> Unit = {},
    val cancelDownload: () -> Unit = {},
    val install: () -> Unit = {},
    val openInstallPermission: () -> Unit = {},
)

@Composable
internal fun UpdatesSection(
    settings: AppSettings,
    state: UpdaterUiState,
    actions: UpdaterActions,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    SettingsCard {
        SectionHeader(stringResource(R.string.settings_updates_title))
        Text(
            text = stringResource(
                R.string.settings_updates_current,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.settings_updates_auto),
            supportingText = stringResource(R.string.settings_updates_auto_body),
            checked = settings.updateChecksEnabled,
            onCheckedChange = { enabled ->
                onSettingsChange { it.copy(updateChecksEnabled = enabled) }
            },
        )

        SettingsSubheading(stringResource(R.string.settings_updates_channel_title))
        Column(modifier = Modifier.selectableGroup()) {
            for (channel in UpdateChannel.entries) {
                SettingsRadioRow(
                    title = stringResource(channel.titleRes()),
                    body = stringResource(channel.bodyRes()),
                    selected = settings.updateChannel == channel,
                    onClick = {
                        if (settings.updateChannel != channel) {
                            onSettingsChange { it.changeChannel(channel) }
                        }
                    },
                )
            }
        }

        Text(
            text = lastCheckText(settings.lastUpdateCheckAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.checking) {
            Text(
                text = stringResource(R.string.settings_updates_checking),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Button(
                onClick = actions.checkNow,
                enabled = !state.checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LinkSondaSpacing.minTouchTarget),
            ) {
                Text(stringResource(R.string.settings_updates_check))
            }
        }

        statusText(settings, state)?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { error ->
            SettingsNote(errorText(error))
        }

        state.available?.let { update ->
            UpdateAvailableCard(
                update = update,
                state = state,
                actions = actions,
            )
        }
        SettingsNote(stringResource(R.string.settings_updates_source))
    }
}

@Composable
private fun UpdateAvailableCard(
    update: OfferedUpdate,
    state: UpdaterUiState,
    actions: UpdaterActions,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LinkSondaSpacing.small),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
    ) {
        Text(
            text = stringResource(R.string.settings_updates_available, update.versionName),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = update.notes.ifBlank { stringResource(R.string.settings_updates_no_notes) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val download = state.download
        when {
            download != null -> {
                LinearProgressIndicator(
                    progress = { download.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_updates_downloading, ((download.fraction ?: 0f) * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = actions.cancelDownload) {
                    Text(stringResource(R.string.settings_updates_cancel_download))
                }
            }

            state.readyToInstall -> {
                Text(
                    text = stringResource(R.string.settings_updates_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.installPermissionRequired) {
                    Text(
                        text = stringResource(R.string.settings_updates_install_permission),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = actions.openInstallPermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LinkSondaSpacing.minTouchTarget),
                    ) {
                        Text(stringResource(R.string.settings_updates_allow_install))
                    }
                } else {
                    Button(
                        onClick = actions.install,
                        enabled = state.installState != InstallState.INSTALLING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LinkSondaSpacing.minTouchTarget),
                    ) {
                        Text(stringResource(R.string.settings_updates_install))
                    }
                }
            }

            else -> Button(
                onClick = actions.download,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LinkSondaSpacing.minTouchTarget),
            ) {
                Text(stringResource(R.string.settings_updates_download))
            }
        }
    }
}

private fun AppSettings.changeChannel(channel: UpdateChannel): AppSettings = copy(
    updateChannel = channel,
    updateEtag = null,
    lastUpdateStatus = UpdateCheckStatus.NEVER,
    lastUpdateRetryAt = null,
    lastOfferedVersionCode = null,
    lastOfferedVersionName = null,
    lastOfferedReleaseNotes = null,
    lastOfferedChannel = null,
    lastOfferedUpdateJson = null,
)

@Composable
private fun lastCheckText(instant: Instant?): String =
    if (instant == null) {
        stringResource(R.string.settings_updates_never)
    } else {
        stringResource(
            R.string.settings_updates_last_check,
            FORMATTER.withZone(ZoneId.systemDefault()).format(instant),
        )
    }

@Composable
private fun statusText(settings: AppSettings, state: UpdaterUiState): String? =
    when (settings.lastUpdateStatus) {
        UpdateCheckStatus.UPDATE_AVAILABLE -> stringResource(R.string.settings_updates_status_update_available)
        UpdateCheckStatus.UP_TO_DATE -> stringResource(R.string.settings_updates_status_up_to_date)
        UpdateCheckStatus.NOT_MODIFIED -> stringResource(R.string.settings_updates_status_not_modified)
        UpdateCheckStatus.RATE_LIMITED -> {
            val retry = settings.lastUpdateRetryAt
            if (retry != null) {
                stringResource(
                    R.string.settings_updates_status_rate_limited,
                    FORMATTER.withZone(ZoneId.systemDefault()).format(retry),
                )
            } else {
                stringResource(R.string.settings_updates_status_network)
            }
        }
        UpdateCheckStatus.NETWORK_ERROR -> stringResource(R.string.settings_updates_status_network)
        UpdateCheckStatus.INVALID_RELEASE -> stringResource(R.string.settings_updates_status_invalid)
        UpdateCheckStatus.NO_RELEASE -> stringResource(R.string.settings_updates_status_no_release)
        UpdateCheckStatus.NEVER -> null
    }

@Composable
private fun errorText(error: UpdaterError): String = when (error) {
    UpdaterError.NETWORK, UpdaterError.RATE_LIMITED -> stringResource(R.string.settings_updates_status_network)
    UpdaterError.VERIFY_FAILED -> stringResource(R.string.settings_updates_error_verify)
    UpdaterError.DOWNLOAD_FAILED -> stringResource(R.string.settings_updates_error_download)
    UpdaterError.INSTALL_PERMISSION -> stringResource(R.string.settings_updates_install_permission)
    UpdaterError.INSTALL_FAILED -> stringResource(R.string.settings_updates_error_install)
    UpdaterError.INVALID_RELEASE, UpdaterError.NO_RELEASE -> stringResource(R.string.settings_updates_status_invalid)
    UpdaterError.BUSY -> stringResource(R.string.settings_updates_error_busy)
}

private fun UpdateChannel.titleRes(): Int = when (this) {
    UpdateChannel.STABLE -> R.string.settings_updates_channel_stable
    UpdateChannel.PREVIEW -> R.string.settings_updates_channel_preview
}

private fun UpdateChannel.bodyRes(): Int = when (this) {
    UpdateChannel.STABLE -> R.string.settings_updates_channel_stable_body
    UpdateChannel.PREVIEW -> R.string.settings_updates_channel_preview_body
}

private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
