package io.github.chuck852.linksonda.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.chuck852.linksonda.domain.AggregateStatus

@Preview(name = "Available • light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun AvailableLightPreview() {
    ThemePreview(AggregateStatus.AVAILABLE, ThemeMode.ADAPTIVE, ThemeBrightness.LIGHT)
}

@Preview(name = "Available • dark", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun AvailableDarkPreview() {
    ThemePreview(AggregateStatus.AVAILABLE, ThemeMode.ADAPTIVE, ThemeBrightness.DARK)
}

@Preview(name = "Partially available • light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun PartialLightPreview() {
    ThemePreview(AggregateStatus.PARTIAL, ThemeMode.ADAPTIVE, ThemeBrightness.LIGHT)
}

@Preview(name = "Partially available • dark", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun PartialDarkPreview() {
    ThemePreview(AggregateStatus.PARTIAL, ThemeMode.ADAPTIVE, ThemeBrightness.DARK)
}

@Preview(name = "Unavailable • light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun UnavailableLightPreview() {
    ThemePreview(AggregateStatus.UNAVAILABLE, ThemeMode.ADAPTIVE, ThemeBrightness.LIGHT)
}

@Preview(name = "Unavailable • dark", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun UnavailableDarkPreview() {
    ThemePreview(AggregateStatus.UNAVAILABLE, ThemeMode.ADAPTIVE, ThemeBrightness.DARK)
}

@Preview(name = "Unknown • light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun UnknownLightPreview() {
    ThemePreview(AggregateStatus.UNKNOWN, ThemeMode.ADAPTIVE, ThemeBrightness.LIGHT)
}

@Preview(name = "Unknown • dark", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun UnknownDarkPreview() {
    ThemePreview(AggregateStatus.UNKNOWN, ThemeMode.ADAPTIVE, ThemeBrightness.DARK)
}

@Preview(name = "Accents • partially available • light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun AccentsPartialLightPreview() {
    ThemePreview(AggregateStatus.PARTIAL, ThemeMode.ACCENTS, ThemeBrightness.LIGHT)
}

@Preview(name = "Accents • partially available • dark", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun AccentsPartialDarkPreview() {
    ThemePreview(AggregateStatus.PARTIAL, ThemeMode.ACCENTS, ThemeBrightness.DARK)
}

@Preview(name = "Static • unavailable • light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun StaticUnavailableLightPreview() {
    ThemePreview(AggregateStatus.UNAVAILABLE, ThemeMode.STATIC, ThemeBrightness.LIGHT)
}

@Preview(name = "Static • unavailable • dark", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
private fun StaticUnavailableDarkPreview() {
    ThemePreview(AggregateStatus.UNAVAILABLE, ThemeMode.STATIC, ThemeBrightness.DARK)
}

@Preview(
    name = "System dark • unknown",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SystemDarkPreview() {
    ThemePreview(AggregateStatus.UNKNOWN, ThemeMode.ADAPTIVE, ThemeBrightness.SYSTEM)
}

@Composable
private fun ThemePreview(
    status: AggregateStatus,
    mode: ThemeMode,
    brightness: ThemeBrightness,
) {
    val dark = when (brightness) {
        ThemeBrightness.SYSTEM -> isSystemInDarkTheme()
        ThemeBrightness.LIGHT -> false
        ThemeBrightness.DARK -> true
    }
    LinkSondaTheme(
        confirmedAggregateStatus = ConfirmedAggregateStatus(status),
        themeMode = mode,
        themeBrightness = brightness,
    ) {
        ThemeShowcase(aggregateStatus = status, themeMode = mode, dark = dark)
    }
}
