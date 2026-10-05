package tech.iflink.seuwiki.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A rounded rectangle whose corners are superelliptical rather than circular —
 * the Android equivalent of SwiftUI's `RoundedRectangleStyle.continuous`.
 *
 * Compose's built-in `RoundedCornerShape` draws circular corner arcs, which reads
 * noticeably "sharper" than iOS placed beside it. This traces the same curve
 * Apple uses: straight edges through the middle of each side, with the corner
 * quadrants following `|x/a|^n + |y/a|^n = 1`.
 *
 * Each quadrant is a single cubic Bézier rather than a polyline. An earlier
 * version sampled the curve into 96 `lineTo` segments; that polygon is both
 * slower and — on the emulator's GPU path rasteriser — rendered as garbage
 * brush-stroke artefacts across every clipped card. Four curves is exact enough
 * at these radii and tessellates cleanly.
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

        if (r <= 0f || size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(Rect(Offset.Zero, size))
        }
        return Outline.Generic(squircleRectPath(size, r, n))
    }

    private companion object {

        /**
         * Cubic control-point reach for one superellipse quadrant, as a fraction
         * of the corner radius.
         *
         * For a quadrant running A=(1,0) → B=(0,1) with control points
         * C1=(1,k) and C2=(k,1), the curve midpoint is `(4 + 3k) / 8`. Setting
         * that equal to the superellipse's diagonal point `2^(-1/n)` gives
         * `k = (8 · 2^(-1/n) - 4) / 3` — which evaluates to the familiar 0.5523
         * for a circle (n=2) and ≈0.909 for the squircle (n=4) used here.
         */
        fun controlReach(n: Float): Float {
            val diagonal = Math.pow(2.0, -1.0 / n.toDouble()).toFloat()
            return ((8f * diagonal) - 4f) / 3f
        }

        fun squircleRectPath(size: Size, r: Float, n: Float): Path {
            val x0 = 0f
            val y0 = 0f
            val x1 = size.width
            val y1 = size.height
            val c = controlReach(n) * r

            val path = Path()
            // Straight run → corner → straight run → corner … clockwise. Every
            // side needs its own `lineTo`: the first one is easy to forget
            // because `moveTo` already sits on the top edge's start, and
            // omitting it makes a single Bézier span the whole top side, bowing
            // the edge inward.
            path.moveTo(x0 + r, y0)
            path.lineTo(x1 - r, y0)
            path.cubicTo(x1 - r + c, y0, x1, y0 + r - c, x1, y0 + r)
            path.lineTo(x1, y1 - r)
            path.cubicTo(x1, y1 - r + c, x1 - r + c, y1, x1 - r, y1)
            path.lineTo(x0 + r, y1)
            path.cubicTo(x0 + r - c, y1, x0, y1 - r + c, x0, y1 - r)
            path.lineTo(x0, y0 + r)
            path.cubicTo(x0, y0 + r - c, x0 + r - c, y0, x0 + r, y0)
            path.close()
            return path
        }
    }
}

/** Corner radius used by `cardStyle()` in the iOS app. */
val CardCornerRadius: Dp = 20.dp

/** Corner radius for the inner artwork of a tool icon tile (iOS uses size * 0.27). */
fun toolIconCorner(size: Dp): Dp = size * 0.27f
