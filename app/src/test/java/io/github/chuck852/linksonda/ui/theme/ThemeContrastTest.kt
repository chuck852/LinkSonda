package io.github.chuck852.linksonda.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import io.github.chuck852.linksonda.domain.AggregateStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeContrastTest {
    private data class RolePair(
        val name: String,
        val foreground: (ColorScheme) -> Color,
        val background: (ColorScheme) -> Color,
        val minimumRatio: Double,
    )

    private val textPairs = listOf(
        RolePair("onPrimary / primary", { it.onPrimary }, { it.primary }, 4.5),
        RolePair("onPrimaryContainer / primaryContainer", { it.onPrimaryContainer }, { it.primaryContainer }, 4.5),
        RolePair("onSecondary / secondary", { it.onSecondary }, { it.secondary }, 4.5),
        RolePair("onSecondaryContainer / secondaryContainer", { it.onSecondaryContainer }, { it.secondaryContainer }, 4.5),
        RolePair("onTertiary / tertiary", { it.onTertiary }, { it.tertiary }, 4.5),
        RolePair("onTertiaryContainer / tertiaryContainer", { it.onTertiaryContainer }, { it.tertiaryContainer }, 4.5),
        RolePair("onBackground / background", { it.onBackground }, { it.background }, 4.5),
        RolePair("onSurface / surface", { it.onSurface }, { it.surface }, 4.5),
        RolePair("onSurfaceVariant / surface", { it.onSurfaceVariant }, { it.surface }, 4.5),
        RolePair("onSurfaceVariant / surfaceVariant", { it.onSurfaceVariant }, { it.surfaceVariant }, 4.5),
        RolePair("onSurface / surfaceContainerLowest", { it.onSurface }, { it.surfaceContainerLowest }, 4.5),
        RolePair("onSurface / surfaceContainerLow", { it.onSurface }, { it.surfaceContainerLow }, 4.5),
        RolePair("onSurface / surfaceContainer", { it.onSurface }, { it.surfaceContainer }, 4.5),
        RolePair("onSurface / surfaceContainerHigh", { it.onSurface }, { it.surfaceContainerHigh }, 4.5),
        RolePair("onSurface / surfaceContainerHighest", { it.onSurface }, { it.surfaceContainerHighest }, 4.5),
        RolePair("inverseOnSurface / inverseSurface", { it.inverseOnSurface }, { it.inverseSurface }, 4.5),
        RolePair("inversePrimary / inverseSurface", { it.inversePrimary }, { it.inverseSurface }, 4.5),
        RolePair("onError / error", { it.onError }, { it.error }, 4.5),
        RolePair("onErrorContainer / errorContainer", { it.onErrorContainer }, { it.errorContainer }, 4.5),
        RolePair("onPrimaryFixed / primaryFixed", { it.onPrimaryFixed }, { it.primaryFixed }, 4.5),
        RolePair("onPrimaryFixedVariant / primaryFixed", { it.onPrimaryFixedVariant }, { it.primaryFixed }, 4.5),
        RolePair("onPrimaryFixed / primaryFixedDim", { it.onPrimaryFixed }, { it.primaryFixedDim }, 4.5),
        RolePair("onPrimaryFixedVariant / primaryFixedDim", { it.onPrimaryFixedVariant }, { it.primaryFixedDim }, 3.0),
        RolePair("onSecondaryFixed / secondaryFixed", { it.onSecondaryFixed }, { it.secondaryFixed }, 4.5),
        RolePair("onSecondaryFixedVariant / secondaryFixed", { it.onSecondaryFixedVariant }, { it.secondaryFixed }, 4.5),
        RolePair("onTertiaryFixed / tertiaryFixed", { it.onTertiaryFixed }, { it.tertiaryFixed }, 4.5),
        RolePair("onTertiaryFixedVariant / tertiaryFixed", { it.onTertiaryFixedVariant }, { it.tertiaryFixed }, 4.5),
        RolePair("outline / background", { it.outline }, { it.background }, 3.0),
        RolePair("outlineVariant / background", { it.outlineVariant }, { it.background }, 1.5),
        RolePair("outlineVariant / surface", { it.outlineVariant }, { it.surface }, 1.5),
    )

    @Test
    fun allStaticSchemesMeetContrast() {
        for (dark in listOf(false, true)) {
            for (mode in ThemeMode.entries) {
                for (status in AggregateStatus.entries) {
                    assertPairs(
                        name = "mode=$mode status=$status dark=$dark",
                        scheme = LinkSondaPalettes.scheme(status, mode, dark),
                    )
                }
            }
        }
    }

    @Test
    fun adaptiveAndAccentTransitionsKeepContrast() {
        for (dark in listOf(false, true)) {
            for (mode in listOf(ThemeMode.ADAPTIVE, ThemeMode.ACCENTS)) {
                for (from in AggregateStatus.entries) {
                    for (to in AggregateStatus.entries) {
                        if (from == to) continue
                        val start = LinkSondaPalettes.scheme(from, mode, dark)
                        val end = LinkSondaPalettes.scheme(to, mode, dark)
                        for (step in 0..100) {
                            val fraction = step / 100f
                            for (pair in textPairs) {
                                val foreground = lerp(pair.foreground(start), pair.foreground(end), fraction)
                                val background = lerp(pair.background(start), pair.background(end), fraction)
                                assertContrast(
                                    name = "$mode $from->$to step=$step ${pair.name} dark=$dark",
                                    foreground = foreground,
                                    background = background,
                                    minimum = pair.minimumRatio,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun semanticTonesAreReadableOnEveryAdaptiveSurface() {
        for (dark in listOf(false, true)) {
            val semantic = LinkSondaSemanticColors.forDark(dark)
            val surfaces = buildList {
                for (status in AggregateStatus.entries) {
                    val scheme = LinkSondaPalettes.adaptive(status, dark)
                    add("background-$status" to scheme.background)
                    add("surface-$status" to scheme.surface)
                    add("container-low-$status" to scheme.surfaceContainerLow)
                    add("container-high-$status" to scheme.surfaceContainerHigh)
                }
            }
            for (tone in StatusTone.entries) {
                val colors = semantic.forTone(tone)
                assertContrast("$tone onAccent/accent dark=$dark", colors.onAccent, colors.accent, 4.5)
                assertContrast("$tone onContainer/container dark=$dark", colors.onContainer, colors.container, 4.5)
                for ((surfaceName, surface) in surfaces) {
                    assertContrast("$tone accent/$surfaceName dark=$dark", colors.accent, surface, 4.5)
                }
            }
        }
    }

    private fun assertPairs(name: String, scheme: ColorScheme) {
        for (pair in textPairs) {
            assertContrast(
                name = "$name ${pair.name}",
                foreground = pair.foreground(scheme),
                background = pair.background(scheme),
                minimum = pair.minimumRatio,
            )
        }
    }

    private fun assertContrast(
        name: String,
        foreground: Color,
        background: Color,
        minimum: Double,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$name: contrast %.2f is below %.1f".format(ratio, minimum),
            ratio >= minimum,
        )
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        val argb = color.toArgb()
        val red = linearChannel((argb shr 16) and 0xFF)
        val green = linearChannel((argb shr 8) and 0xFF)
        val blue = linearChannel(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun linearChannel(value: Int): Double {
        val channel = value / 255.0
        return if (channel <= 0.04045) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }
    }
}
