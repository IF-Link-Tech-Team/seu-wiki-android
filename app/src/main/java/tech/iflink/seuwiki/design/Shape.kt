package tech.iflink.seuwiki.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A rounded rectangle whose corners are superelliptical rather than circular —
 * the Android equivalent of SwiftUI's `RoundedRectangleStyle.continuous`.
 *
 * Compose's built-in `RoundedCornerShape` draws circular corner arcs, which reads
 * noticeably "sharper" than iOS placed beside it. This traces the same curve
 * Apple uses: straight edges through the middle of each side, with the corner
 * quadrants following `|cos t|^(2/n)` and `|sin t|^(2/n)`.
 *
 * @param radius corner size, matching the SwiftUI `cornerRadius` it replaces.
 * @param n superellipse exponent. Higher is squarer; 4 approximates Apple's
 *   continuous corner well at the 20dp radii used across this app.
 */
class ContinuousRoundedShape(
    private val radius: Dp,
    private val n: Float = 4f,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }
            .coerceAtMost(min(size.width, size.height) / 2f)

        if (r <= 0f) {
            return Outline.Generic(
                Path().apply {
                    addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                },
            )
        }
        return Outline.Generic(squircleRectPath(size, r, n))
    }

    private companion object {
        /** Full-sweep resolution. 96 segments is smooth at 20dp on a 3x screen. */
        const val SEGMENTS = 96
        const val TWO_PI = (2.0 * Math.PI).toFloat()

        fun squircleRectPath(size: Size, r: Float, n: Float): Path {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // Straight-edge run per side: half-extent minus the corner inset.
            val ex = (cx - r).coerceAtLeast(0f)
            val ey = (cy - r).coerceAtLeast(0f)
            val power = 2f / n

            val path = Path()
            for (i in 0..SEGMENTS) {
                val t = (i.toFloat() / SEGMENTS) * TWO_PI
                val c = cos(t)
                val s = sin(t)
                val cornerX = r * signedPow(c, power)
                val cornerY = r * signedPow(s, power)
                // t = 0 sits at the midpoint of the right edge and sweeps
                // clockwise in screen coordinates (y grows downward).
                val x = cx + ex * abs(c) + cornerX
                val y = cy + ey * abs(s) + cornerY
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }

        /** Odd extension of [v] to [power] that keeps the sign. */
        fun signedPow(v: Float, power: Float): Float =
            if (v >= 0f) v.pow(power) else -((-v).pow(power))
    }
}

/** Corner radius used by `cardStyle()` in the iOS app. */
val CardCornerRadius: Dp = 20.dp

/** Corner radius for the inner artwork of a tool icon tile (iOS uses size * 0.27). */
fun toolIconCorner(size: Dp): Dp = size * 0.27f
