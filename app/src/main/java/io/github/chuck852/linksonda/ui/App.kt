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
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.ui.checks.ChecksFilterState
import io.github.chuck852.linksonda.ui.checks.ChecksFilterStateSaver
import io.github.chuck852.linksonda.ui.checks.DEFAULT_GROUP_ID
import io.github.chuck852.linksonda.ui.checks.DEFAULT_GROUP_NAME
import io.github.chuck852.linksonda.ui.checks.ProbeActions
import io.github.chuck852.linksonda.ui.checks.ProbeDraft
import io.github.chuck852.linksonda.ui.checks.ProbeEditorActions
import io.github.chuck852.linksonda.ui.checks.ProbeEditorState
import io.github.chuck852.linksonda.ui.checks.ProbeEditorStatus
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
import io.github.chuck852.linksonda.ui.screens.ChecksScreen
import io.github.chuck852.linksonda.ui.screens.HistoryScreen
import io.github.chuck852.linksonda.ui.screens.PresetsScreen
import io.github.chuck852.linksonda.ui.screens.ProbeDetailsScreen
import io.github.chuck852.linksonda.ui.screens.ProbeEditorScreen
import io.github.chuck852.linksonda.ui.screens.SettingsScreen
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
    initialNavigation: AppNavigationState = AppNavigationState(),
    editorActions: ProbeEditorActions = ProbeEditorActions(),
) {
    LinkSondaTheme(confirmedAggregateStatus = ConfirmedAggregateStatus(overviewState.aggregateStatus)) {
        var navigation by rememberSaveable(stateSaver = AppNavigationStateSaver) {
            mutableStateOf(initialNavigation)
        }
        var checksFilters by rememberSaveable(stateSaver = ChecksFilterStateSaver) {
            mutableStateOf(initialChecksFilters(initialNavigation.checksFilter?.groupId))
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var editorBusy by remember { mutableStateOf(false) }
        var editorStatus by remember { mutableStateOf<ProbeEditorStatus?>(null) }
        val demoManualMessage = stringResource(R.string.overview_manual_demo)
        val demoActionMessage = stringResource(R.string.action_demo)
        val editorSavedTemplate = stringResource(R.string.editor_saved)
        val onRunManualCheck: (() -> Unit)? =
            if (overviewState.isDemo && overviewState.manualCheck == ManualCheckState.IDLE) {
                {
                    scope.launch {
                        snackbarHostState.showSnackbar(demoManualMessage)
                    }
                }
            } else {
                null
            }
        val probeActions = ProbeActions(
            onRetry = if (overviewState.isDemo) {
                {
                    scope.launch { snackbarHostState.showSnackbar(demoActionMessage) }
                }
            } else {
                null
            },
            onEdit = { key ->
                editorStatus = null
                navigation = navigation.openProbeEditor(key.id.value)
            },
        )
        val openNewEditor: () -> Unit = {
            editorStatus = null
            navigation = navigation.openProbeEditor()
        }
        val onEditorSave: (ProbeDraft) -> Unit = save@{ draft ->
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
                    ProbeEditorStatus.TestCompleted(result.outcome, result.elapsed, result.isDemo)
                }
            }
        }

        BackHandler(enabled = navigation.canGoBack) {
            navigation.back()?.let { navigation = it }
        }

        val route = navigation.current
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
                            state = overviewState,
                            onGroupClick = { group ->
                                checksFilters = ChecksFilterState(groupId = group.id)
                                navigation = navigation.openChecks(CheckGroupFilter(group.id))
                            },
                            onRunManualCheck = onRunManualCheck,
                        )

                        AppTab.CHECKS -> ChecksScreen(
                            state = overviewState,
                            filters = checksFilters,
                            onFiltersChange = { checksFilters = it },
                            onProbeClick = { navigation = navigation.openProbeDetails(it.id.value) },
                            onAddCheck = openNewEditor,
                        )

                        AppTab.PRESETS -> PresetsScreen()
                        AppTab.HISTORY -> HistoryScreen()
                    }

                    is AppRoute.FilteredChecks -> ChecksScreen(
                        state = overviewState,
                        filters = checksFilters,
                        onFiltersChange = { checksFilters = it },
                        onProbeClick = { navigation = navigation.openProbeDetails(it.id.value) },
                        onAddCheck = openNewEditor,
                    )

                    is AppRoute.ProbeDetails -> ProbeDetailsScreen(
                        item = overviewState.probe(ProbeId(route.probeId)),
                        actions = probeActions,
                        isDemo = overviewState.isDemo,
                    )

                    is AppRoute.ProbeEditor -> {
                        val item = route.probeId?.let { overviewState.probe(ProbeId(it)) }
                        if (route.probeId != null && item == null) {
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
                            val defaultGroup = overviewState.groups.firstOrNull {
                                it.id == checksFilters.groupId
                            } ?: overviewState.groups.firstOrNull()
                            val newProbeId = remember(route.probeId, route.initialProtocol) {
                                "probe-${UUID.randomUUID()}"
                            }
                            val initialState = remember(
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
                                        groupName = defaultGroup?.name ?: DEFAULT_GROUP_NAME,
                                        protocol = route.initialProtocol
                                            ?.let { name -> ProbeProtocol.entries.firstOrNull { it.name == name } }
                                            ?: ProbeProtocol.HTTPS,
                                    )
                                }
                            }
                            ProbeEditorScreen(
                                initialState = initialState,
                                baseConfig = item?.config,
                                isDemo = overviewState.isDemo,
                                isBusy = editorBusy,
                                canSave = editorActions.onSave != null,
                                canTest = editorActions.onTest != null,
                                status = editorStatus,
                                onSave = onEditorSave,
                                onTest = onEditorTest,
                            )
                        }
                    }

                    AppRoute.Settings -> SettingsScreen()
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
