package io.github.chuck852.linksonda.ui.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

enum class AppTab { OVERVIEW, CHECKS, PRESETS, HISTORY }

data class CheckGroupFilter(val groupId: String) {
    init { require(groupId.isNotBlank()) }
}

sealed interface AppRoute {
    data class Tab(val tab: AppTab) : AppRoute
    data class FilteredChecks(val filter: CheckGroupFilter) : AppRoute
    data class ProbeDetails(val probeId: String) : AppRoute {
        init { require(probeId.isNotBlank()) }
    }
    data class ProbeEditor(
        val probeId: String? = null,
        val initialProtocol: String? = null,
    ) : AppRoute {
        init {
            require(probeId == null || probeId.isNotBlank())
            require(initialProtocol == null || initialProtocol.isNotBlank())
        }
    }
    data object Settings : AppRoute
}

/**
 * A small, saveable back stack for the four top-level tabs and nested screens.
 *
 * Selecting a tab always returns to that tab's root. Opening a group filter or settings pushes one
 * entry, so system back and the top-bar back action return to the previous screen.
 */
data class AppNavigationState(
    val stack: List<AppRoute> = listOf(AppRoute.Tab(AppTab.OVERVIEW)),
) {
    init { require(stack.isNotEmpty()) }

    val current: AppRoute get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    val selectedTab: AppTab
        get() = when (val route = current) {
            is AppRoute.Tab -> route.tab
            is AppRoute.FilteredChecks, is AppRoute.ProbeDetails, is AppRoute.ProbeEditor -> AppTab.CHECKS
            AppRoute.Settings -> stack.asReversed().drop(1).firstNotNullOfOrNull(AppRoute::tabOrNull)
                ?: AppTab.OVERVIEW
        }

    val checksFilter: CheckGroupFilter?
        get() = (current as? AppRoute.FilteredChecks)?.filter

    fun selectTab(tab: AppTab): AppNavigationState =
        AppNavigationState(listOf(AppRoute.Tab(tab)))

    fun openChecks(filter: CheckGroupFilter): AppNavigationState =
        copy(stack = stack + AppRoute.FilteredChecks(filter))

    fun openProbeDetails(probeId: String): AppNavigationState =
        copy(stack = stack + AppRoute.ProbeDetails(probeId))

    fun openProbeEditor(probeId: String? = null, initialProtocol: String? = null): AppNavigationState =
        copy(stack = stack + AppRoute.ProbeEditor(probeId, initialProtocol))

    fun openSettings(): AppNavigationState =
        copy(stack = stack + AppRoute.Settings)

    fun clearFilter(): AppNavigationState = selectTab(AppTab.CHECKS)

    fun back(): AppNavigationState? =
        if (canGoBack) copy(stack = stack.dropLast(1)) else null
}

fun AppRoute.tabOrNull(): AppTab? = when (this) {
    is AppRoute.Tab -> tab
    is AppRoute.FilteredChecks, is AppRoute.ProbeDetails, is AppRoute.ProbeEditor -> AppTab.CHECKS
    AppRoute.Settings -> null
}

val AppNavigationStateSaver: Saver<AppNavigationState, Any> = listSaver(
    save = { state -> state.stack.map(AppRoute::toSaveableKey) },
    restore = { keys ->
        AppNavigationState(
            keys.mapNotNull(::routeFromSaveableKey)
                .ifEmpty { listOf(AppRoute.Tab(AppTab.OVERVIEW)) },
        )
    },
)

private fun AppRoute.toSaveableKey(): String = when (this) {
    is AppRoute.Tab -> "tab:${tab.name}"
    is AppRoute.FilteredChecks -> "checks:${filter.groupId}"
    is AppRoute.ProbeDetails -> "details:${probeId}"
    is AppRoute.ProbeEditor -> "editor:${probeId.orEmpty()}|${initialProtocol.orEmpty()}"
    AppRoute.Settings -> "settings"
}

private fun routeFromSaveableKey(key: String): AppRoute? = when {
    key.startsWith(TAB_PREFIX) -> AppTab.entries
        .firstOrNull { it.name == key.removePrefix(TAB_PREFIX) }
        ?.let(AppRoute::Tab)

    key.startsWith(CHECKS_PREFIX) -> key.removePrefix(CHECKS_PREFIX)
        .takeIf(String::isNotBlank)
        ?.let { AppRoute.FilteredChecks(CheckGroupFilter(it)) }

    key.startsWith(DETAILS_PREFIX) -> key.removePrefix(DETAILS_PREFIX)
        .takeIf(String::isNotBlank)
        ?.let(AppRoute::ProbeDetails)

    key.startsWith(EDITOR_PREFIX) -> {
        val values = key.removePrefix(EDITOR_PREFIX).split('|', limit = 2)
        AppRoute.ProbeEditor(
            probeId = values.getOrNull(0)?.takeIf(String::isNotBlank),
            initialProtocol = values.getOrNull(1)?.takeIf(String::isNotBlank),
        )
    }

    key == SETTINGS_KEY -> AppRoute.Settings
    else -> null
}

private const val TAB_PREFIX = "tab:"
private const val CHECKS_PREFIX = "checks:"
private const val DETAILS_PREFIX = "details:"
private const val EDITOR_PREFIX = "editor:"
private const val SETTINGS_KEY = "settings"
