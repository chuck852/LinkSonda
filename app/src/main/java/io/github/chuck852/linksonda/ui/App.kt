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
import io.github.chuck852.linksonda.ui.screens.SettingsScreen
import io.github.chuck852.linksonda.ui.theme.ConfirmedAggregateStatus
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.ui.theme.LinkSondaTheme
import kotlinx.coroutines.launch

@Composable
fun LinkSondaApp(
    overviewState: OverviewUiState = OverviewUiState.Empty,
    initialNavigation: AppNavigationState = AppNavigationState(),
) {
    LinkSondaTheme(confirmedAggregateStatus = ConfirmedAggregateStatus(overviewState.aggregateStatus)) {
        var navigation by rememberSaveable(stateSaver = AppNavigationStateSaver) {
            mutableStateOf(initialNavigation)
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val demoManualMessage = stringResource(R.string.overview_manual_demo)
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
                    onSelectTab = { navigation = navigation.selectTab(it) },
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
                                navigation = navigation.openChecks(CheckGroupFilter(group.id))
                            },
                            onRunManualCheck = onRunManualCheck,
                        )

                        AppTab.CHECKS -> ChecksScreen(
                            state = overviewState,
                            filter = null,
                            onClearFilter = null,
                        )

                        AppTab.PRESETS -> PresetsScreen()
                        AppTab.HISTORY -> HistoryScreen()
                    }

                    is AppRoute.FilteredChecks -> ChecksScreen(
                        state = overviewState,
                        filter = route.filter,
                        onClearFilter = { navigation = navigation.clearFilter() },
                    )

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
