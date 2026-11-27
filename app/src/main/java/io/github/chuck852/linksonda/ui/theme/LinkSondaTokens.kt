package io.github.chuck852.linksonda.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Layout tokens shared by screens and previews. Theme changes never modify these values. */
object LinkSondaSpacing {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val screen = 16.dp
    val large = 24.dp
    val minTouchTarget = 48.dp
}

val LinkSondaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
