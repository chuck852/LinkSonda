package io.github.chuck852.linksonda.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.chuck852.linksonda.BuildConfig
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.settings.AppLanguage
import io.github.chuck852.linksonda.settings.AppSettings
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.ui.theme.ThemeBrightness
import io.github.chuck852.linksonda.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    settings: AppSettings,
    monitoringAvailable: Boolean,
    onSettingsChange: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
    ) {
        item {
            AppearanceSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
        }
        item {
            MonitoringSection(
                settings = settings,
                monitoringAvailable = monitoringAvailable,
                onSettingsChange = onSettingsChange,
            )
        }
        item {
            NotificationsSection(
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
        }
        item {
            HistorySection(
                settings = settings,
                onSettingsChange = onSettingsChange,
            )
        }
        item {
            LocalDataSection()
        }
    }
}

@Composable
private fun AppearanceSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    SettingsCard {
        SectionHeader(stringResource(R.string.settings_appearance_title))

        SettingsSubheading(stringResource(R.string.settings_theme_mode_title))
        Column(modifier = Modifier.selectableGroup()) {
            for (mode in ThemeMode.entries) {
                SettingsRadioRow(
                    title = stringResource(mode.titleRes()),
                    body = stringResource(mode.bodyRes()),
                    selected = settings.themeMode == mode,
                    onClick = {
                        if (settings.themeMode != mode) {
                            onSettingsChange(settings.copy(themeMode = mode))
                        }
                    },
                )
            }
        }

        SettingsSubheading(stringResource(R.string.settings_brightness_title))
        Column(modifier = Modifier.selectableGroup()) {
            for (brightness in ThemeBrightness.entries) {
                SettingsRadioRow(
                    title = stringResource(brightness.titleRes()),
                    selected = settings.themeBrightness == brightness,
                    onClick = {
                        if (settings.themeBrightness != brightness) {
                            onSettingsChange(settings.copy(themeBrightness = brightness))
                        }
                    },
                )
            }
        }

        SettingsSubheading(stringResource(R.string.settings_language_title))
        Column(modifier = Modifier.selectableGroup()) {
            for (language in AppLanguage.entries) {
                SettingsRadioRow(
                    title = stringResource(language.titleRes()),
                    selected = settings.language == language,
                    onClick = {
                        if (settings.language != language) {
                            onSettingsChange(settings.copy(language = language))
                        }
                    },
                )
            }
        }
        SettingsNote(stringResource(R.string.settings_language_restart))
    }
}

@Composable
private fun MonitoringSection(
    settings: AppSettings,
    monitoringAvailable: Boolean,
    onSettingsChange: (AppSettings) -> Unit,
) {
    SettingsCard {
        SectionHeader(stringResource(R.string.settings_monitoring_title))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_monitoring_switch),
            checked = settings.monitoringEnabled,
            enabled = monitoringAvailable,
            onCheckedChange = { enabled ->
                onSettingsChange(settings.copy(monitoringEnabled = enabled))
            },
        )
        if (!monitoringAvailable) {
            SettingsNote(stringResource(R.string.settings_monitoring_unavailable))
        }
    }
}

@Composable
private fun NotificationsSection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val context = LocalContext.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val latestSettings by rememberUpdatedState(settings)
    val latestOnSettingsChange by rememberUpdatedState(onSettingsChange)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            latestOnSettingsChange(latestSettings.copy(notificationsEnabled = true))
        } else {
            permissionDenied = true
        }
    }

    val openNotificationSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    }

    SettingsCard {
        SectionHeader(stringResource(R.string.settings_notifications_title))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_notifications_switch),
            supportingText = stringResource(R.string.settings_notifications_body),
            checked = settings.notificationsEnabled,
            onCheckedChange = { enabled ->
                when {
                    !enabled -> {
                        permissionDenied = false
                        onSettingsChange(settings.copy(notificationsEnabled = false))
                    }

                    hasNotificationPermission(context) -> {
                        permissionDenied = false
                        onSettingsChange(settings.copy(notificationsEnabled = true))
                    }

                    else -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
        if (permissionDenied && !settings.notificationsEnabled) {
            Text(
                text = stringResource(R.string.settings_notifications_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = openNotificationSettings,
                modifier = Modifier.heightIn(min = LinkSondaSpacing.minTouchTarget),
            ) {
                Text(stringResource(R.string.settings_notifications_open_settings))
            }
        }
    }
}

@Composable
private fun HistorySection(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    SettingsCard {
        SectionHeader(stringResource(R.string.settings_history_title))
        SettingsSubheading(stringResource(R.string.settings_history_retention))
        Column(modifier = Modifier.selectableGroup()) {
            for (days in AppSettings.HISTORY_RETENTION_DAYS) {
                SettingsRadioRow(
                    title = pluralStringResource(R.plurals.settings_history_days, days, days),
                    selected = settings.historyRetentionDays == days,
                    onClick = {
                        if (settings.historyRetentionDays != days) {
                            onSettingsChange(settings.copy(historyRetentionDays = days))
                        }
                    },
                )
            }
        }
        SettingsNote(stringResource(R.string.settings_history_storage_unavailable))
    }
}

@Composable
private fun LocalDataSection() {
    SettingsCard {
        SectionHeader(stringResource(R.string.settings_local_data_title))
        Text(
            text = stringResource(R.string.settings_local_data_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    val selectedDescription = stringResource(R.string.settings_state_selected)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LinkSondaSpacing.minTouchTarget)
            .minimumInteractiveComponentSize()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                if (selected) {
                    stateDescription = selectedDescription
                }
            }
            .padding(vertical = LinkSondaSpacing.extraSmall),
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
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val state = stringResource(
        if (checked) R.string.settings_state_on else R.string.settings_state_off,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LinkSondaSpacing.minTouchTarget)
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { stateDescription = state }
            .padding(vertical = LinkSondaSpacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingsSubheading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
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

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@StringRes
private fun ThemeMode.titleRes(): Int = when (this) {
    ThemeMode.ADAPTIVE -> R.string.settings_theme_mode_adaptive
    ThemeMode.ACCENTS -> R.string.settings_theme_mode_accents
    ThemeMode.STATIC -> R.string.settings_theme_mode_static
}

@StringRes
private fun ThemeMode.bodyRes(): Int = when (this) {
    ThemeMode.ADAPTIVE -> R.string.settings_theme_mode_adaptive_body
    ThemeMode.ACCENTS -> R.string.settings_theme_mode_accents_body
    ThemeMode.STATIC -> R.string.settings_theme_mode_static_body
}

@StringRes
private fun ThemeBrightness.titleRes(): Int = when (this) {
    ThemeBrightness.SYSTEM -> R.string.settings_brightness_system
    ThemeBrightness.LIGHT -> R.string.settings_brightness_light
    ThemeBrightness.DARK -> R.string.settings_brightness_dark
}

@StringRes
private fun AppLanguage.titleRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.RUSSIAN -> R.string.settings_language_russian
    AppLanguage.ENGLISH -> R.string.settings_language_english
}
