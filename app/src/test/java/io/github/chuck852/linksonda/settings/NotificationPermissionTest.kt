package io.github.chuck852.linksonda.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun grantedPreferenceIsEffectiveWithoutARationale() {
        val state = notificationPermissionUiState(
            granted = true,
            requested = true,
            preferenceEnabled = true,
        )

        assertTrue(state.effectiveEnabled)
        assertFalse(state.showRationale)
    }

    @Test
    fun firstDenialExplainsWhyTheSwitchStayedOff() {
        val state = notificationPermissionUiState(
            granted = false,
            requested = true,
            preferenceEnabled = false,
        )

        assertFalse(state.effectiveEnabled)
        assertTrue(state.showRationale)
    }

    @Test
    fun revokedPermissionHidesTheEffectiveSwitchAndKeepsTheRationale() {
        val state = notificationPermissionUiState(
            granted = false,
            requested = true,
            preferenceEnabled = true,
        )

        assertFalse(state.effectiveEnabled)
        assertTrue(state.showRationale)
    }

    @Test
    fun denialBeforeAnyRequestDoesNotShowAMessage() {
        val state = notificationPermissionUiState(
            granted = false,
            requested = false,
            preferenceEnabled = false,
        )

        assertFalse(state.effectiveEnabled)
        assertFalse(state.showRationale)
    }

    @Test
    fun platformWithoutRuntimePermissionCanEnableImmediately() {
        val state = notificationPermissionUiState(
            granted = true,
            requested = false,
            preferenceEnabled = true,
        )

        assertTrue(state.effectiveEnabled)
        assertEquals(false, state.showRationale)
    }
}
