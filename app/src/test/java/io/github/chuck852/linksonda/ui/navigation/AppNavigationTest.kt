package io.github.chuck852.linksonda.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {
    @Test
    fun startsOnOverviewWithoutBack() {
        val state = AppNavigationState()
        assertEquals(AppRoute.Tab(AppTab.OVERVIEW), state.current)
        assertEquals(AppTab.OVERVIEW, state.selectedTab)
        assertFalse(state.canGoBack)
        assertNull(state.checksFilter)
        assertNull(state.back())
    }

    @Test
    fun selectingTabResetsNestedStack() {
        val state = AppNavigationState()
            .openChecks(CheckGroupFilter("global"))
            .selectTab(AppTab.PRESETS)

        assertEquals(listOf(AppRoute.Tab(AppTab.PRESETS)), state.stack)
        assertEquals(AppTab.PRESETS, state.selectedTab)
        assertFalse(state.canGoBack)
    }

    @Test
    fun groupCardOpensFilteredChecksAndBackReturns() {
        val overview = AppNavigationState()
        val filtered = overview.openChecks(CheckGroupFilter("regional"))

        assertEquals(AppTab.CHECKS, filtered.selectedTab)
        assertEquals(CheckGroupFilter("regional"), filtered.checksFilter)
        assertTrue(filtered.canGoBack)

        val returned = filtered.back()
        assertEquals(overview, returned)
        assertNull(returned?.checksFilter)
    }

    @Test
    fun clearFilterStaysOnChecksRoot() {
        val state = AppNavigationState()
            .openChecks(CheckGroupFilter("dns"))
            .clearFilter()

        assertEquals(listOf(AppRoute.Tab(AppTab.CHECKS)), state.stack)
        assertNull(state.checksFilter)
    }

    @Test
    fun probeDetailsReturnsToChecks() {
        val checks = AppNavigationState().selectTab(AppTab.CHECKS)
        val details = checks.openProbeDetails("probe:regional")

        assertEquals(AppRoute.ProbeDetails("probe:regional"), details.current)
        assertEquals(AppTab.CHECKS, details.selectedTab)
        assertTrue(details.canGoBack)
        assertEquals(checks, details.back())
    }

    @Test
    fun probeEditorReturnsToChecks() {
        val checks = AppNavigationState().selectTab(AppTab.CHECKS)
        val editor = checks.openProbeEditor(initialProtocol = "TLS")

        assertEquals(AppRoute.ProbeEditor(initialProtocol = "TLS"), editor.current)
        assertEquals(AppTab.CHECKS, editor.selectedTab)
        assertTrue(editor.canGoBack)
        assertEquals(checks, editor.back())
    }

    @Test
    fun settingsReturnsToPreviousTab() {
        val checks = AppNavigationState().selectTab(AppTab.CHECKS)
        val settings = checks.openSettings()

        assertEquals(AppRoute.Settings, settings.current)
        assertEquals(AppTab.CHECKS, settings.selectedTab)
        assertTrue(settings.canGoBack)
        assertEquals(checks, settings.back())
    }

    @Test
    fun saverRestoresNestedRoutesAndGroupIds() {
        val state = AppNavigationState()
            .openChecks(CheckGroupFilter("group:regional"))
            .openProbeDetails("probe:regional")
            .openProbeEditor("probe:regional", "HTTPS")
        val scope = SaverScope { true }

        val saved = AppNavigationStateSaver.run { scope.save(state) }
        val restored = AppNavigationStateSaver.restore(requireNotNull(saved))

        assertEquals(state, restored)
    }
}
