package io.github.chuck852.linksonda.ui.theme

import io.github.chuck852.linksonda.domain.AggregateStatus

/** How the app derives the root Material color scheme from the confirmed aggregate status. */
enum class ThemeMode {
    /** Backgrounds, surfaces, buttons and accents follow the confirmed aggregate status. */
    ADAPTIVE,

    /** Backgrounds and surfaces stay green-neutral; primary, secondary and tertiary accents follow status. */
    ACCENTS,

    /** The green LinkSonda scheme is used regardless of aggregate status. */
    STATIC,
}

/** User-selected brightness. [SYSTEM] follows the Android light/dark setting. */
enum class ThemeBrightness { SYSTEM, LIGHT, DARK }

/**
 * An aggregate status that has already been confirmed by the monitoring state reducer.
 *
 * The theme must not react to an individual probe failure or an in-flight manual check. Callers keep
 * the last confirmed value while a check is running and update this wrapper only after confirmation.
 */
@JvmInline
value class ConfirmedAggregateStatus(val value: AggregateStatus) {
    companion object {
        val Unknown = ConfirmedAggregateStatus(AggregateStatus.UNKNOWN)
    }
}
