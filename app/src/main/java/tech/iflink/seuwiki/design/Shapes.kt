package tech.iflink.seuwiki.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale.
 *
 * Material 3 requires [Shapes.extraLarge] to be a `CornerBasedShape`, so the
 * squircle lives on the explicit [ContinuousRoundedShape] used by
 * `Modifier.cardStyle()` and the tool tiles rather than in this scale. These
 * entries only back components that reach for `MaterialTheme.shapes`.
 */
val SeuShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(CardCornerRadius),
)
