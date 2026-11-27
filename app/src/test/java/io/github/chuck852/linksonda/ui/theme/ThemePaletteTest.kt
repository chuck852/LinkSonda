package io.github.chuck852.linksonda.ui.theme

import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.AggregateStatus
import io.github.chuck852.linksonda.domain.ProbeLifecycle
import io.github.chuck852.linksonda.domain.ProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ThemePaletteTest {
    @Test
    fun adaptiveModeUsesDistinctSurfaceRamps() {
        for (dark in listOf(false, true)) {
            val backgrounds = AggregateStatus.entries.map {
                LinkSondaPalettes.scheme(it, ThemeMode.ADAPTIVE, dark).background
            }
            val surfaces = AggregateStatus.entries.map {
                LinkSondaPalettes.scheme(it, ThemeMode.ADAPTIVE, dark).surface
            }
            assertEquals(4, backgrounds.distinct().size)
            assertEquals(4, surfaces.distinct().size)
        }
    }

    @Test
    fun accentsModeKeepsGreenSurfaceRampAndChangesAccents() {
        for (dark in listOf(false, true)) {
            val base = LinkSondaPalettes.scheme(AggregateStatus.AVAILABLE, ThemeMode.ADAPTIVE, dark)
            for (status in AggregateStatus.entries) {
                val accents = LinkSondaPalettes.scheme(status, ThemeMode.ACCENTS, dark)
                val adaptive = LinkSondaPalettes.scheme(status, ThemeMode.ADAPTIVE, dark)
                assertEquals(base.background, accents.background)
                assertEquals(base.surface, accents.surface)
                assertEquals(base.surfaceContainer, accents.surfaceContainer)
                assertEquals(base.outline, accents.outline)
                assertEquals(adaptive.primary, accents.primary)
                assertEquals(adaptive.primaryContainer, accents.primaryContainer)
                assertEquals(adaptive.secondary, accents.secondary)
                assertEquals(adaptive.tertiary, accents.tertiary)
            }
        }
    }

    @Test
    fun staticModeKeepsGreenSchemeForEveryStatus() {
        for (dark in listOf(false, true)) {
            val green = LinkSondaPalettes.scheme(AggregateStatus.AVAILABLE, ThemeMode.STATIC, dark)
            for (status in AggregateStatus.entries) {
                val static = LinkSondaPalettes.scheme(status, ThemeMode.STATIC, dark)
                assertEquals(green.primary, static.primary)
                assertEquals(green.background, static.background)
                assertEquals(green.surface, static.surface)
                assertEquals(green.surfaceContainerHigh, static.surfaceContainerHigh)
            }
        }
    }

    @Test
    fun unknownAggregateAlwaysUsesNeutralPrimary() {
        for (dark in listOf(false, true)) {
            val unknown = LinkSondaPalettes.scheme(AggregateStatus.UNKNOWN, ThemeMode.ADAPTIVE, dark)
            for (status in AggregateStatus.entries - AggregateStatus.UNKNOWN) {
                val other = LinkSondaPalettes.scheme(status, ThemeMode.ADAPTIVE, dark)
                assertNotEquals(other.primary, unknown.primary)
            }
        }
    }

    @Test
    fun semanticColorsDependOnlyOnBrightness() {
        assertSame(LinkSondaSemanticColors.Light, LinkSondaSemanticColors.forDark(false))
        assertSame(LinkSondaSemanticColors.Dark, LinkSondaSemanticColors.forDark(true))
        assertNotEquals(
            LinkSondaSemanticColors.Light.running,
            LinkSondaSemanticColors.Dark.running,
        )
    }

    @Test
    fun resultStatusAndLifecycleUseSeparateTones() {
        val down = ProbeStatus.DOWN.visual()
        assertEquals(StatusTone.NEGATIVE, down.tone)
        assertEquals(R.string.status_down, down.label)

        val running = ProbeLifecycle.RUNNING.visual()
        assertEquals(StatusTone.RUNNING, running?.tone)
        assertEquals(R.string.status_running, running?.label)

        val paused = ProbeLifecycle.PAUSED.visual()
        assertEquals(StatusTone.NEUTRAL, paused?.tone)
        assertEquals(R.string.status_paused, paused?.label)

        assertNull(ProbeLifecycle.IDLE.visual())
    }

    @Test
    fun aggregateStatusesMapToSemanticTones() {
        assertEquals(StatusTone.POSITIVE, AggregateStatus.AVAILABLE.visual().tone)
        assertEquals(StatusTone.WARNING, AggregateStatus.PARTIAL.visual().tone)
        assertEquals(StatusTone.NEGATIVE, AggregateStatus.UNAVAILABLE.visual().tone)
        assertEquals(StatusTone.NEUTRAL, AggregateStatus.UNKNOWN.visual().tone)
    }

    @Test
    fun approvedThemeMotionIs650Milliseconds() {
        assertEquals(650, ThemeMotion.DurationMillis)
    }
}
