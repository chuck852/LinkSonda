package io.github.chuck852.linksonda.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic result tones. These stay independent from the adaptive aggregate theme. */
enum class StatusTone { POSITIVE, WARNING, NEGATIVE, NEUTRAL, RUNNING }

@Immutable
data class SemanticToneColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
)

@Immutable
data class LinkSondaSemanticColors(
    val positive: SemanticToneColors,
    val warning: SemanticToneColors,
    val negative: SemanticToneColors,
    val neutral: SemanticToneColors,
    val running: SemanticToneColors,
) {
    fun forTone(tone: StatusTone): SemanticToneColors = when (tone) {
        StatusTone.POSITIVE -> positive
        StatusTone.WARNING -> warning
        StatusTone.NEGATIVE -> negative
        StatusTone.NEUTRAL -> neutral
        StatusTone.RUNNING -> running
    }

    companion object {
        val Light = LinkSondaSemanticColors(
            positive = SemanticToneColors(
                accent = Color(0xFF176B50),
                onAccent = Color.White,
                container = Color(0xFFC2E7D3),
                onContainer = Color(0xFF002116),
            ),
            warning = SemanticToneColors(
                accent = Color(0xFF825500),
                onAccent = Color.White,
                container = Color(0xFFF9DF9E),
                onContainer = Color(0xFF2A1700),
            ),
            negative = SemanticToneColors(
                accent = Color(0xFFB3261E),
                onAccent = Color.White,
                container = Color(0xFFFFD8D3),
                onContainer = Color(0xFF410E0B),
            ),
            neutral = SemanticToneColors(
                accent = Color(0xFF5A5F5A),
                onAccent = Color.White,
                container = Color(0xFFDFE4DE),
                onContainer = Color(0xFF171D19),
            ),
            running = SemanticToneColors(
                accent = Color(0xFF00639B),
                onAccent = Color.White,
                container = Color(0xFFD1E4FF),
                onContainer = Color(0xFF001D33),
            ),
        )

        val Dark = LinkSondaSemanticColors(
            positive = SemanticToneColors(
                accent = Color(0xFF79DDB1),
                onAccent = Color(0xFF003829),
                container = Color(0xFF0B513D),
                onContainer = Color(0xFFC2E7D3),
            ),
            warning = SemanticToneColors(
                accent = Color(0xFFF0C477),
                onAccent = Color(0xFF422C00),
                container = Color(0xFF5E4200),
                onContainer = Color(0xFFFFDEA6),
            ),
            negative = SemanticToneColors(
                accent = Color(0xFFFFB4AB),
                onAccent = Color(0xFF690005),
                container = Color(0xFF93000A),
                onContainer = Color(0xFFFFDAD6),
            ),
            neutral = SemanticToneColors(
                accent = Color(0xFFC5C7C2),
                onAccent = Color(0xFF2F312E),
                container = Color(0xFF454843),
                onContainer = Color(0xFFE1E3DE),
            ),
            running = SemanticToneColors(
                accent = Color(0xFF8FD0FF),
                onAccent = Color(0xFF003354),
                container = Color(0xFF00497A),
                onContainer = Color(0xFFC9E6FF),
            ),
        )

        fun forDark(dark: Boolean): LinkSondaSemanticColors = if (dark) Dark else Light
    }
}

val LocalLinkSondaSemanticColors = staticCompositionLocalOf { LinkSondaSemanticColors.Light }

object LinkSondaTheme {
    val semanticColors: LinkSondaSemanticColors
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalLinkSondaSemanticColors.current
}
