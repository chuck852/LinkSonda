package io.github.chuck852.linksonda.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.chuck852.linksonda.domain.AggregateStatus

object ThemeMotion {
    /**
     * Root theme roles animate synchronously with ease-in-out.
     *
     * Compose animation-core applies the system MotionDurationScale. When Android animations are
     * disabled (scale 0), the transition completes on its first frame instead of waiting here.
     */
    const val DurationMillis = 650
}

/**
 * Applies the LinkSonda design system.
 *
 * Only a [ConfirmedAggregateStatus] can change the adaptive scheme. Keep the previous value during a
 * manual check and while a new result is not yet confirmed. Probe lifecycle is rendered separately
 * through [ProbeLifecycleBadge], so a running check does not replace availability.
 */
@Composable
fun LinkSondaTheme(
    confirmedAggregateStatus: ConfirmedAggregateStatus = ConfirmedAggregateStatus.Unknown,
    themeMode: ThemeMode = ThemeMode.ADAPTIVE,
    themeBrightness: ThemeBrightness = ThemeBrightness.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeBrightness) {
        ThemeBrightness.SYSTEM -> isSystemInDarkTheme()
        ThemeBrightness.LIGHT -> false
        ThemeBrightness.DARK -> true
    }
    val colorScheme = animatedScheme(
        status = confirmedAggregateStatus.value,
        mode = themeMode,
        dark = darkTheme,
    )
    val semanticColors = LinkSondaSemanticColors.forDark(darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(darkTheme) {
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalLinkSondaSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = LinkSondaShapes,
            content = content,
        )
    }
}

@Composable
private fun animatedScheme(
    status: AggregateStatus,
    mode: ThemeMode,
    dark: Boolean,
): ColorScheme {
    if (mode == ThemeMode.STATIC) {
        return LinkSondaPalettes.scheme(status, mode, dark)
    }

    // Recreate the transition when brightness or mode changes so those switches snap to their new
    // palette instead of interpolating through low-contrast mixed light/dark colors. A confirmed
    // aggregate-status change inside the same mode and brightness remains animated.
    return key(mode, dark) {
        val schemes = remember(mode, dark) {
            AggregateStatus.entries.associateWith { aggregate ->
                LinkSondaPalettes.scheme(aggregate, mode, dark)
            }
        }
        val transition = updateTransition(targetState = status, label = "aggregate-status-theme")
        transition.animateScheme(schemes)
    }
}

@Composable
private fun Transition<AggregateStatus>.animateScheme(
    schemes: Map<AggregateStatus, ColorScheme>,
): ColorScheme {
    val primary by animateSchemeColor(schemes) { it.primary }
    val onPrimary by animateSchemeColor(schemes) { it.onPrimary }
    val primaryContainer by animateSchemeColor(schemes) { it.primaryContainer }
    val onPrimaryContainer by animateSchemeColor(schemes) { it.onPrimaryContainer }
    val inversePrimary by animateSchemeColor(schemes) { it.inversePrimary }
    val secondary by animateSchemeColor(schemes) { it.secondary }
    val onSecondary by animateSchemeColor(schemes) { it.onSecondary }
    val secondaryContainer by animateSchemeColor(schemes) { it.secondaryContainer }
    val onSecondaryContainer by animateSchemeColor(schemes) { it.onSecondaryContainer }
    val tertiary by animateSchemeColor(schemes) { it.tertiary }
    val onTertiary by animateSchemeColor(schemes) { it.onTertiary }
    val tertiaryContainer by animateSchemeColor(schemes) { it.tertiaryContainer }
    val onTertiaryContainer by animateSchemeColor(schemes) { it.onTertiaryContainer }
    val background by animateSchemeColor(schemes) { it.background }
    val onBackground by animateSchemeColor(schemes) { it.onBackground }
    val surface by animateSchemeColor(schemes) { it.surface }
    val onSurface by animateSchemeColor(schemes) { it.onSurface }
    val surfaceVariant by animateSchemeColor(schemes) { it.surfaceVariant }
    val onSurfaceVariant by animateSchemeColor(schemes) { it.onSurfaceVariant }
    val surfaceTint by animateSchemeColor(schemes) { it.surfaceTint }
    val inverseSurface by animateSchemeColor(schemes) { it.inverseSurface }
    val inverseOnSurface by animateSchemeColor(schemes) { it.inverseOnSurface }
    val error by animateSchemeColor(schemes) { it.error }
    val onError by animateSchemeColor(schemes) { it.onError }
    val errorContainer by animateSchemeColor(schemes) { it.errorContainer }
    val onErrorContainer by animateSchemeColor(schemes) { it.onErrorContainer }
    val outline by animateSchemeColor(schemes) { it.outline }
    val outlineVariant by animateSchemeColor(schemes) { it.outlineVariant }
    val scrim by animateSchemeColor(schemes) { it.scrim }
    val surfaceBright by animateSchemeColor(schemes) { it.surfaceBright }
    val surfaceDim by animateSchemeColor(schemes) { it.surfaceDim }
    val surfaceContainer by animateSchemeColor(schemes) { it.surfaceContainer }
    val surfaceContainerHigh by animateSchemeColor(schemes) { it.surfaceContainerHigh }
    val surfaceContainerHighest by animateSchemeColor(schemes) { it.surfaceContainerHighest }
    val surfaceContainerLow by animateSchemeColor(schemes) { it.surfaceContainerLow }
    val surfaceContainerLowest by animateSchemeColor(schemes) { it.surfaceContainerLowest }
    val primaryFixed by animateSchemeColor(schemes) { it.primaryFixed }
    val primaryFixedDim by animateSchemeColor(schemes) { it.primaryFixedDim }
    val onPrimaryFixed by animateSchemeColor(schemes) { it.onPrimaryFixed }
    val onPrimaryFixedVariant by animateSchemeColor(schemes) { it.onPrimaryFixedVariant }
    val secondaryFixed by animateSchemeColor(schemes) { it.secondaryFixed }
    val secondaryFixedDim by animateSchemeColor(schemes) { it.secondaryFixedDim }
    val onSecondaryFixed by animateSchemeColor(schemes) { it.onSecondaryFixed }
    val onSecondaryFixedVariant by animateSchemeColor(schemes) { it.onSecondaryFixedVariant }
    val tertiaryFixed by animateSchemeColor(schemes) { it.tertiaryFixed }
    val tertiaryFixedDim by animateSchemeColor(schemes) { it.tertiaryFixedDim }
    val onTertiaryFixed by animateSchemeColor(schemes) { it.onTertiaryFixed }
    val onTertiaryFixedVariant by animateSchemeColor(schemes) { it.onTertiaryFixedVariant }

    return ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        primaryFixed = primaryFixed,
        primaryFixedDim = primaryFixedDim,
        onPrimaryFixed = onPrimaryFixed,
        onPrimaryFixedVariant = onPrimaryFixedVariant,
        secondaryFixed = secondaryFixed,
        secondaryFixedDim = secondaryFixedDim,
        onSecondaryFixed = onSecondaryFixed,
        onSecondaryFixedVariant = onSecondaryFixedVariant,
        tertiaryFixed = tertiaryFixed,
        tertiaryFixedDim = tertiaryFixedDim,
        onTertiaryFixed = onTertiaryFixed,
        onTertiaryFixedVariant = onTertiaryFixedVariant,
    )
}

@Composable
private fun Transition<AggregateStatus>.animateSchemeColor(
    schemes: Map<AggregateStatus, ColorScheme>,
    selector: (ColorScheme) -> Color,
): State<Color> = animateColor(
    transitionSpec = {
        tween(durationMillis = ThemeMotion.DurationMillis, easing = FastOutSlowInEasing)
    },
) { status -> selector(schemes.getValue(status)) }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
