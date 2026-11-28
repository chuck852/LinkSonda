package io.github.chuck852.linksonda.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.MonitoringProfile
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProfileId
import io.github.chuck852.linksonda.domain.addOrReplaceProbe
import io.github.chuck852.linksonda.domain.copyMonitoringProfile
import io.github.chuck852.linksonda.domain.newMonitoringProfile
import io.github.chuck852.linksonda.domain.removeProbe
import io.github.chuck852.linksonda.domain.renameGroup
import io.github.chuck852.linksonda.settings.AppSettings
import io.github.chuck852.linksonda.settings.FirstRunProfile
import io.github.chuck852.linksonda.ui.checks.ChecksFilterState
import io.github.chuck852.linksonda.ui.checks.ChecksFilterStateSaver
import io.github.chuck852.linksonda.ui.checks.DEFAULT_GROUP_ID
import io.github.chuck852.linksonda.ui.checks.DEFAULT_GROUP_NAME
import io.github.chuck852.linksonda.ui.checks.ProbeActions
import io.github.chuck852.linksonda.ui.checks.ProbeDraft
import io.github.chuck852.linksonda.ui.checks.ProbeEditorActions
import io.github.chuck852.linksonda.ui.checks.ProbeEditorState
import io.github.chuck852.linksonda.ui.checks.ProbeEditorStatus
import io.github.chuck852.linksonda.ui.checks.ProbeExecutionSnapshot
import io.github.chuck852.linksonda.ui.checks.ProbeProtocol
import io.github.chuck852.linksonda.ui.checks.initialChecksFilters
import io.github.chuck852.linksonda.ui.components.EmptyState
import io.github.chuck852.linksonda.ui.navigation.AppNavigationState
import io.github.chuck852.linksonda.ui.navigation.AppNavigationStateSaver
import io.github.chuck852.linksonda.ui.navigation.AppRoute
import io.github.chuck852.linksonda.ui.navigation.AppTab
import io.github.chuck852.linksonda.ui.navigation.CheckGroupFilter
import io.github.chuck852.linksonda.ui.overview.ManualCheckState
import io.github.chuck852.linksonda.ui.overview.OverviewScreen
import io.github.chuck852.linksonda.ui.overview.OverviewUiState
import io.github.chuck852.linksonda.ui.profiles.ProfileEditorScreen
import io.github.chuck852.linksonda.ui.profiles.ProfileListScreen
import io.github.chuck852.linksonda.ui.profiles.toOverviewUiState
import io.github.chuck852.linksonda.ui.screens.ChecksScreen
import io.github.chuck852.linksonda.ui.screens.FirstRunScreen
import io.github.chuck852.linksonda.ui.screens.HistoryScreen
import io.github.chuck852.linksonda.ui.screens.ProbeDetailsScreen
import io.github.chuck852.linksonda.ui.screens.ProbeEditorScreen
import io.github.chuck852.linksonda.ui.screens.SettingsScreen
import io.github.chuck852.linksonda.ui.screens.UpdaterActions
import io.github.chuck852.linksonda.updater.UpdaterUiState
import io.github.chuck852.linksonda.ui.theme.ConfirmedAggregateStatus
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.ui.theme.LinkSondaTheme
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LinkSondaApp(
    overviewState: OverviewUiState = OverviewUiState.Empty,
    profiles: List<MonitoringProfile> = emptyList(),
    onSaveProfile: (suspend (MonitoringProfile) -> Boolean)? = null,
    onDeleteProfile: (suspend (ProfileId) -> Boolean)? = null,
    initialNavigation: AppNavigationState = AppNavigationState(),
    editorActions: ProbeEditorActions = ProbeEditorActions(),
    settings: AppSettings = AppSettings.Default,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit = {},
    firstRunProfiles: List<FirstRunProfile> = emptyList(),
    updaterState: UpdaterUiState = UpdaterUiState(),
    updaterActions: UpdaterActions = UpdaterActions(),
) {
    val ungroupedGroupName = stringResource(R.string.profile_group_ungrouped)
    val activeStoredProfile = profiles.firstOrNull { it.id.value == settings.selectedProfileId }
    val currentOverview = remember(overviewState, activeStoredProfile, ungroupedGroupName) {
        if (overviewState.isDemo) {
            overviewState
        } else {
            activeStoredProfile?.toOverviewUiState(ungroupedGroupName) ?: OverviewUiState.Empty
        }
    }
    LinkSondaTheme(
        confirmedAggregateStatus = ConfirmedAggregateStatus(currentOverview.aggregateStatus),
        themeMode = settings.themeMode,
        themeBrightness = settings.themeBrightness,
    ) {
        val activeProfile = currentOverview.activeProfile
        val profileDescription = stringResource(R.string.first_run_profile_details)
        val fallbackProfileDescription = stringResource(R.string.first_run_profile_available_body)
        val availableProfiles = remember(
            firstRunProfiles,
            profiles,
            activeProfile,
            profileDescription,
            fallbackProfileDescription,
        ) {
            when {
                profiles.isNotEmpty() -> profiles.map { profile ->
                    FirstRunProfile(
                        id = profile.id.value,
                        name = profile.name,
                        description = String.format(
                            Locale.US,
                            profileDescription,
                            profile.preset.probes.size,
                            profile.schedule.interval.inWholeSeconds,
                        ),
                    )
                }

                firstRunProfiles.isNotEmpty() -> firstRunProfiles
                else -> listOfNotNull(
                    activeProfile?.let { profile ->
                        FirstRunProfile(
                            id = profile.id,
                            name = profile.name,
                            description = fallbackProfileDescription,
                        )
                    },
                )
            }
        }
        if (!settings.onboardingCompleted) {
            FirstRunScreen(
                profiles = availableProfiles,
                onComplete = { profileId, updateChecksEnabled ->
                    onSettingsChange {
                        it.copy(
                            onboardingCompleted = true,
                            selectedProfileId = profileId,
                            updateChecksEnabled = updateChecksEnabled,
                        )
                    }
                },
            )
            return@LinkSondaTheme
        }
        var navigation by rememberSaveable(stateSaver = AppNavigationStateSaver) {
            mutableStateOf(initialNavigation)
        }
        var checksFilters by rememberSaveable(stateSaver = ChecksFilterStateSaver) {
            mutableStateOf(initialChecksFilters(initialNavigation.checksFilter?.groupId))
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var editorBusy by remember { mutableStateOf(false) }
        var profileEditorBusy by remember { mutableStateOf(false) }
        var editorStatus by remember { mutableStateOf<ProbeEditorStatus?>(null) }
        var editorDraft by remember { mutableStateOf<ProbeEditorState?>(null) }
        val demoManualMessage = stringResource(R.string.overview_manual_demo)
        val defaultGroupName = stringResource(R.string.profile_default_group_name)
        val profileDefaultNameTemplate = stringResource(R.string.profile_default_name)
        val profileCopyNameTemplate = stringResource(R.string.profile_copy_name)
        val profileCreatedMessage = stringResource(R.string.profile_created)
        val profileCreateFailedMessage = stringResource(R.string.profile_create_failed)
        val profileCopiedMessage = stringResource(R.string.profile_copied)
        val profileCopyFailedMessage = stringResource(R.string.profile_copy_failed)
        val profileActivatedMessage = stringResource(R.string.profile_activated)
        val profileDeletedMessage = stringResource(R.string.profile_deleted)
        val profileDeleteFailedMessage = stringResource(R.string.profile_delete_failed)
        val profileSavedMessage = stringResource(R.string.profile_saved)
        val profileSaveFailedMessage = stringResource(R.string.profile_save_failed)
        val groupRenamedMessage = stringResource(R.string.profile_group_renamed)
        val groupRenameFailedMessage = stringResource(R.string.profile_group_rename_failed)
        val checkDeletedMessage = stringResource(R.string.profile_check_deleted)
        val checkDeleteFailedMessage = stringResource(R.string.profile_check_delete_failed)
        val demoActionMessage = stringResource(R.string.action_demo)
        val editorSavedTemplate = stringResource(R.string.editor_saved)
        val onRunManualCheck: (() -> Unit)? =
            if (currentOverview.isDemo && currentOverview.manualCheck == ManualCheckState.IDLE) {
                {
                    scope.launch {
                        snackbarHostState.showSnackbar(demoManualMessage)
                    }
                }
            } else {
                null
            }
        LaunchedEffect(navigation.stack) {
            if (navigation.stack.none { it is AppRoute.ProbeEditor }) {
                editorDraft = null
            }
        }
        val probeActions = ProbeActions(
            onRetry = if (currentOverview.isDemo) {
                {
                    scope.launch { snackbarHostState.showSnackbar(demoActionMessage) }
                }
            } else {
                null
            },
            onEdit = { key ->
                editorStatus = null
                editorDraft = null
                navigation = navigation.openProbeEditor(key.id.value)
            },
        )
        val openNewEditor: (String?) -> Unit = { profileId ->
            editorStatus = null
            editorDraft = null
            navigation = navigation.openProbeEditor(profileId = profileId)
        }
        val editorTargetProfile = (navigation.current as? AppRoute.ProbeEditor)?.let { editorRoute ->
            editorRoute.profileId?.let { id -> profiles.firstOrNull { it.id.value == id } }
                ?: activeStoredProfile
        }
        val persistProfile: suspend (MonitoringProfile) -> Boolean = { profile ->
            val save = onSaveProfile
            if (save == null) {
                false
            } else {
                try {
                    save(profile)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }
        val onCreateProfile: () -> Unit = create@{
            if (onSaveProfile == null) return@create
            val profile = newMonitoringProfile(
                id = ProfileId("profile-${UUID.randomUUID()}"),
                name = String.format(
                    Locale.US,
                    profileDefaultNameTemplate,
                    profiles.size + 1,
                ),
            )
            scope.launch {
                val created = persistProfile(profile)
                snackbarHostState.showSnackbar(
                    if (created) profileCreatedMessage else profileCreateFailedMessage,
                )
            }
        }
        val onCopyProfile: (MonitoringProfile) -> Unit = copy@{ source ->
            if (onSaveProfile == null) return@copy
            val copy = copyMonitoringProfile(
                source = source,
                newId = ProfileId("profile-${UUID.randomUUID()}"),
                newName = String.format(Locale.US, profileCopyNameTemplate, source.name),
            )
            scope.launch {
                val copied = persistProfile(copy)
                snackbarHostState.showSnackbar(
                    if (copied) profileCopiedMessage else profileCopyFailedMessage,
                )
            }
        }
        val onActivateProfile: (MonitoringProfile) -> Unit = { profile ->
            onSettingsChange { it.copy(selectedProfileId = profile.id.value) }
            scope.launch {
                snackbarHostState.showSnackbar(profileActivatedMessage)
            }
        }
        val onDeleteProfileAction: (MonitoringProfile) -> Unit = delete@{ profile ->
            val delete = onDeleteProfile ?: return@delete
            scope.launch {
                val deleted = try {
                    delete(profile.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (deleted && settings.selectedProfileId == profile.id.value) {
                    onSettingsChange { it.copy(selectedProfileId = null) }
                }
                snackbarHostState.showSnackbar(
                    if (deleted) profileDeletedMessage else profileDeleteFailedMessage,
                )
            }
        }
        val onEditorSave: (ProbeDraft) -> Unit = save@{ draft ->
            val targetProfile = editorTargetProfile
            val profileSaver = onSaveProfile
            if (targetProfile != null && profileSaver != null) {
                scope.launch {
                    editorBusy = true
                    editorStatus = null
                    val saved = try {
                        val updated = targetProfile.addOrReplaceProbe(draft.config, draft.groupName)
                        if (profileSaver(updated)) draft.config else null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    editorBusy = false
                    if (saved == null) {
                        editorStatus = ProbeEditorStatus.SaveFailed
                    } else {
                        editorDraft = null
                        navigation = navigation.back() ?: navigation
                        snackbarHostState.showSnackbar(
                            String.format(Locale.US, editorSavedTemplate, saved.revision.value),
                        )
                    }
                }
                return@save
            }
            val save = editorActions.onSave ?: return@save
            scope.launch {
                editorBusy = true
                editorStatus = null
                val saved = try {
                    save(draft)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                editorBusy = false
                if (saved == null) {
                    editorStatus = ProbeEditorStatus.SaveFailed
                } else {
                    editorDraft = null
                    navigation = navigation.back() ?: navigation
                    snackbarHostState.showSnackbar(
                        String.format(Locale.US, editorSavedTemplate, saved.revision.value),
                    )
                }
            }
        }
        val onEditorTest: (ProbeConfig) -> Unit = test@{ probe ->
            val test = editorActions.onTest ?: return@test
            scope.launch {
                editorBusy = true
                editorStatus = ProbeEditorStatus.Running
                val result = try {
                    test(probe)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                editorBusy = false
                editorStatus = if (result == null) {
                    ProbeEditorStatus.TestFailed
                } else {
                    ProbeEditorStatus.TestCompleted(
                        ProbeExecutionSnapshot.of(probe),
                        result.outcome,
                        result.elapsed,
                        result.isDemo,
                    )
                }
            }
        }

        BackHandler(enabled = navigation.canGoBack) {
            navigation.back()?.let { navigation = it }
        }

        val route = navigation.current
        val openProfiles: () -> Unit = { navigation = navigation.selectTab(AppTab.PRESETS) }
        val addActiveCheck: (() -> Unit)? = activeStoredProfile?.let { profile ->
            { openNewEditor(profile.id.value) }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AppTopBar(
                    title = route.title(),
                    canNavigateBack = navigation.canGoBack,
                    showSettings = route != AppRoute.Settings,
                    onBack = { navigation.back()?.let { navigation = it } },
                    onSettings = { navigation = navigation.openSettings() },
                )
            },
            bottomBar = {
                AppBottomBar(
                    selectedTab = navigation.selectedTab,
                    onSelectTab = { tab ->
                        navigation = navigation.selectTab(tab)
                        if (tab == AppTab.CHECKS) {
                            checksFilters = checksFilters.copy(groupId = null)
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (route) {
                    is AppRoute.Tab -> when (route.tab) {
                        AppTab.OVERVIEW -> OverviewScreen(
                            state = currentOverview,
                            onGroupClick = { group ->
                                checksFilters = ChecksFilterState(groupId = group.id)
                                navigation = navigation.openChecks(CheckGroupFilter(group.id))
                            },
                            onRunManualCheck = onRunManualCheck,
                            onOpenProfiles = openProfiles,
                        )

                        AppTab.CHECKS -> ChecksScreen(
                            state = currentOverview,
                            filters = checksFilters,
                            onFiltersChange = { checksFilters = it },
                            onProbeClick = { navigation = navigation.openProbeDetails(it.id.value) },
                            onAddCheck = addActiveCheck,
                            onOpenProfiles = openProfiles,
                        )

                        AppTab.PRESETS -> ProfileListScreen(
                            profiles = profiles,
                            activeProfileId = settings.selectedProfileId,
                            onCreate = onCreateProfile,
                            onActivate = onActivateProfile,
                            onEdit = { profile -> navigation = navigation.openProfileEditor(profile.id.value) },
                            onCopy = onCopyProfile,
                            onDelete = onDeleteProfileAction,
                        )

                        AppTab.HISTORY -> HistoryScreen()
                    }

                    is AppRoute.FilteredChecks -> ChecksScreen(
                        state = currentOverview,
                        filters = checksFilters,
                        onFiltersChange = { checksFilters = it },
                        onProbeClick = { navigation = navigation.openProbeDetails(it.id.value) },
                        onAddCheck = addActiveCheck,
                        onOpenProfiles = openProfiles,
                    )

                    is AppRoute.ProbeDetails -> ProbeDetailsScreen(
                        item = currentOverview.probe(ProbeId(route.probeId)),
                        actions = probeActions,
                        isDemo = currentOverview.isDemo,
                    )

                    is AppRoute.ProbeEditor -> {
                        val targetProfile = route.profileId?.let { id ->
                            profiles.firstOrNull { it.id.value == id }
                        } ?: if (route.profileId == null) activeStoredProfile else null
                        val editorOverview = targetProfile?.toOverviewUiState(ungroupedGroupName)
                            ?: currentOverview
                        val item = route.probeId?.let { editorOverview.probe(ProbeId(it)) }
                        if (route.profileId != null && targetProfile == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(LinkSondaSpacing.screen),
                            ) {
                                EmptyState(
                                    title = R.string.profile_not_found_title,
                                    body = R.string.profile_not_found_body,
                                )
                            }
                        } else if (route.probeId != null && item == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(LinkSondaSpacing.screen),
                            ) {
                                EmptyState(
                                    title = R.string.editor_not_found_title,
                                    body = R.string.editor_not_found_body,
                                )
                            }
                        } else {
                            val defaultGroup = editorOverview.groups.firstOrNull {
                                it.id == checksFilters.groupId
                            } ?: editorOverview.groups.firstOrNull()
                            val newProbeId = remember(route.probeId, route.initialProtocol) {
                                "probe-${UUID.randomUUID()}"
                            }
                            val builtState = remember(
                                route.probeId,
                                route.initialProtocol,
                                item?.key,
                                defaultGroup?.id,
                            ) {
                                if (item != null) {
                                    ProbeEditorState.from(item.config, item.groupId, item.groupName)
                                } else {
                                    ProbeEditorState.forNew(
                                        probeId = newProbeId,
                                        groupId = defaultGroup?.id ?: DEFAULT_GROUP_ID,
                                        groupName = defaultGroup?.name ?: defaultGroupName,
                                        protocol = route.initialProtocol
                                            ?.let { name -> ProbeProtocol.entries.firstOrNull { it.name == name } }
                                            ?: ProbeProtocol.HTTPS,
                                    )
                                }
                            }
                            ProbeEditorScreen(
                                initialState = editorDraft ?: builtState,
                                baseConfig = item?.config,
                                isDemo = currentOverview.isDemo,
                                isBusy = editorBusy,
                                canSave = editorActions.onSave != null ||
                                    (onSaveProfile != null && targetProfile != null),
                                canTest = editorActions.onTest != null,
                                status = editorStatus,
                                onSave = onEditorSave,
                                onTest = onEditorTest,
                                onDraftChange = { editorDraft = it },
                            )
                        }
                    }

                    is AppRoute.ProfileEditor -> {
                        val profile = profiles.firstOrNull { it.id.value == route.profileId }
                        if (profile == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(LinkSondaSpacing.screen),
                            ) {
                                EmptyState(
                                    title = R.string.profile_not_found_title,
                                    body = R.string.profile_not_found_body,
                                )
                            }
                        } else {
                            ProfileEditorScreen(
                                profile = profile,
                                isBusy = profileEditorBusy,
                                canSave = onSaveProfile != null,
                                onSave = { updated ->
                                    scope.launch {
                                        profileEditorBusy = true
                                        val saved = persistProfile(updated)
                                        profileEditorBusy = false
                                        if (saved) {
                                            navigation = navigation.back() ?: navigation
                                            snackbarHostState.showSnackbar(profileSavedMessage)
                                        } else {
                                            snackbarHostState.showSnackbar(profileSaveFailedMessage)
                                        }
                                    }
                                },
                                onRenameGroup = { oldName, newName ->
                                    val updated = try {
                                        profile.renameGroup(oldName, newName)
                                    } catch (_: IllegalArgumentException) {
                                        null
                                    }
                                    scope.launch {
                                        val saved = updated != null && persistProfile(updated)
                                        snackbarHostState.showSnackbar(
                                            if (saved) groupRenamedMessage else groupRenameFailedMessage,
                                        )
                                    }
                                },
                                onEditCheck = { probe ->
                                    editorStatus = null
                                    editorDraft = null
                                    navigation = navigation.openProbeEditor(
                                        probeId = probe.id.value,
                                        profileId = profile.id.value,
                                    )
                                },
                                onDeleteCheck = { probe ->
                                    scope.launch {
                                        val saved = persistProfile(profile.removeProbe(probe.id))
                                        snackbarHostState.showSnackbar(
                                            if (saved) checkDeletedMessage else checkDeleteFailedMessage,
                                        )
                                    }
                                },
                                onAddCheck = { openNewEditor(profile.id.value) },
                            )
                        }
                    }

                    AppRoute.Settings -> SettingsScreen(
                        settings = settings,
                        monitoringAvailable = false,
                        onSettingsChange = onSettingsChange,
                        updaterState = updaterState,
                        updaterActions = updaterActions,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTopBar(
    title: String,
    canNavigateBack: Boolean,
    showSettings: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 64.dp)
                .padding(horizontal = LinkSondaSpacing.extraSmall, vertical = LinkSondaSpacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canNavigateBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = LinkSondaSpacing.medium)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSettings) {
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.top_bar_settings),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
) {
    val showLabels = LocalDensity.current.fontScale < 1.5f
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        for (tab in AppTab.entries) {
            val label = stringResource(tab.label())
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                modifier = if (showLabels) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = label }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = null,
                    )
                },
                label = if (showLabels) {
                    { Text(label) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun AppRoute.title(): String = when (this) {
    is AppRoute.Tab -> stringResource(tab.label())
    is AppRoute.FilteredChecks -> stringResource(R.string.nav_checks)
    is AppRoute.ProbeDetails -> stringResource(R.string.probe_details_title)
    is AppRoute.ProbeEditor -> if (probeId == null) {
        stringResource(R.string.editor_title_new)
    } else {
        stringResource(R.string.editor_title_edit)
    }

    is AppRoute.ProfileEditor -> stringResource(R.string.profile_editor_title)
    AppRoute.Settings -> stringResource(R.string.top_bar_settings)
}

private fun AppTab.label(): Int = when (this) {
    AppTab.OVERVIEW -> R.string.nav_overview
    AppTab.CHECKS -> R.string.nav_checks
    AppTab.PRESETS -> R.string.nav_presets
    AppTab.HISTORY -> R.string.nav_history
}

private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.OVERVIEW -> Icons.Rounded.Home
    AppTab.CHECKS -> Icons.AutoMirrored.Rounded.List
    AppTab.PRESETS -> Icons.Rounded.Star
    AppTab.HISTORY -> Icons.Rounded.DateRange
}
