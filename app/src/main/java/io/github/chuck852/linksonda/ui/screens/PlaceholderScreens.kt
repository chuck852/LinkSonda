package io.github.chuck852.linksonda.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.ui.components.EmptyState
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = R.string.history_empty_title,
        body = R.string.history_empty_body,
        modifier = modifier,
        icon = Icons.Rounded.DateRange,
    )
}

@Composable
private fun PlaceholderScreen(
    title: Int,
    body: Int,
    modifier: Modifier = Modifier,
    icon: ImageVector,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(LinkSondaSpacing.screen),
    ) {
        EmptyState(
            title = title,
            body = body,
            icon = icon,
        )
    }
}
