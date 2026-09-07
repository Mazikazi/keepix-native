package com.sese.keepix.ui.neu

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The two neumorphic shadow modifiers. Compose ships one elevation shadow and no
 * inset shadow at all, so both are drawn by hand.
 *
 * Every shadow is a PAIR: a dark one offset down-right and a light one offset
 * up-left, at the same blur. Nothing in this design system ever changes
 * background colour to show state -- rest is [neuExtruded], pressed / selected /
 * active is [neuInset] at the same colour, and disabled stays extruded with the
 * label dropped to textSecondary.
 */

/**
 * CSS blur-radius -> Android shadow radius.
 *
 * A CSS blur of `b` spreads over roughly `b` px total, i.e. a Gaussian sigma of
 * `b/2`. The paper answer says Skia derives sigma from setShadowLayer's radius as
 * `sigma = radius * 0.57735 + 0.5`, giving `radius ~= 0.87 * b`.
 *
 * Checked against the prototypes' CSS on an SM-S938B (450dpi, 2.8125 px/dp) by
 * screenshotting NeuStatePreview and reading the hero swatch's edge profile.
 * Composited the way both stacks actually composite -- dark shadow, light over
 * it, then the opaque surface fill -- CSS predicts -27.7 luminance at the tile
 * edge; this renders -25.5. Within 8%, and raising the scale only makes it
 * worse. Do not "fix" the apparent cutoff ~32px out: that is the neighbouring
 * tile's own surface fill painting over the shadow, and CSS clips it identically.
 *
 * ponytail: still a knob, because this is one panel. Re-measure rather than
 * fudging blur values at individual call sites.
 */
var NeuBlurScale: Float = 0.87f

/**
 * Raised from the surface. The default 9/16 pair is the hero step (cards,
 * drawers, primary CTAs); see the shadow scale in
 * docs/design/ADDENDUM_geometry_and_decisions.md B4.
 */
fun Modifier.neuExtruded(
    shape: Shape = RoundedCornerShape(16.dp),
    offset: Dp = 9.dp,
    blur: Dp = 16.dp,
    strong: Boolean = false,
    clipContent: Boolean = true,
): Modifier = composed {
    val colors = LocalNeuColors.current
    val dark = if (strong) colors.shadowDarkStrong else colors.shadowDark
    val light = if (strong) colors.shadowLightStrong else colors.shadowLight
    val drawn = drawBehind {
        val path = shapePath(shape)
        val d = offset.toPx()
        val r = blur.toPx() * NeuBlurScale
        // Shadows first, then the opaque surface fills over their interiors --
        // so only the bleed outside the shape survives.
        drawShadowOf(path, d, d, r, dark)
        drawShadowOf(path, -d, -d, r, light)
        drawPath(path, colors.surface)
    }
    if (clipContent) drawn.clip(shape) else drawn
}

/**
 * Carved into the surface. Used for pressed and selected states, photo wells,
 * the active nav tab (the deepest inset in the system, 10/20) and the retention
 * wheel track.
 *
 * Implemented by clipping to the shape and casting the shadow of the *inverse*
 * region inward -- the only way to get a true inset shadow in Compose.
 */
fun Modifier.neuInset(
    shape: Shape = RoundedCornerShape(16.dp),
    offset: Dp = 6.dp,
    blur: Dp = 10.dp,
    strong: Boolean = false,
    clipContent: Boolean = true,
): Modifier = composed {
    val colors = LocalNeuColors.current
    val dark = if (strong) colors.shadowDarkStrong else colors.shadowDark
    val light = if (strong) colors.shadowLightStrong else colors.shadowLight
    val drawn = drawBehind {
        val path = shapePath(shape)
        val d = offset.toPx()
        val r = blur.toPx() * NeuBlurScale
        drawPath(path, colors.surface)
        val margin = d * 2f + r * 3f + 24f
        val inverse = Path().apply {
            addRect(Rect(-margin, -margin, size.width + margin, size.height + margin))
            addPath(path)
            fillType = PathFillType.EvenOdd
        }
        clipPath(path) {
            drawShadowOf(inverse, d, d, r, dark)
            drawShadowOf(inverse, -d, -d, r, light)
        }
    }
    if (clipContent) drawn.clip(shape) else drawn
}

private fun DrawScope.shapePath(shape: Shape): Path =
    Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@shapePath)) }

/**
 * Draws only the blurred shadow of [path], never the path itself: a fully
 * transparent fill still casts its shadow layer. Same trick the outgoing
 * glassmorphism modifier used.
 */
private fun DrawScope.drawShadowOf(path: Path, dx: Float, dy: Float, radius: Float, color: Color) {
    if (radius <= 0f || color.alpha == 0f) return
    drawIntoCanvas { canvas ->
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.TRANSPARENT
            setShadowLayer(radius, dx, dy, color.toArgb())
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }
}
