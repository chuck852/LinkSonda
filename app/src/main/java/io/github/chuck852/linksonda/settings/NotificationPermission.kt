package io.github.chuck852.linksonda.settings

/**
 * Pure decision state for the notification switch and its explanatory text.
 *
 * Android is the source of truth for [granted]. [requested] records that the app has already shown a
 * permission request, so a denial can still be explained after the dialog or a configuration change.
 */
data class NotificationPermissionUiState(
    val effectiveEnabled: Boolean,
    val showRationale: Boolean,
)

fun notificationPermissionUiState(
    granted: Boolean,
    requested: Boolean,
    preferenceEnabled: Boolean,
): NotificationPermissionUiState = NotificationPermissionUiState(
    effectiveEnabled = preferenceEnabled && granted,
    showRationale = !granted && (requested || preferenceEnabled),
)
