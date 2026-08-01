package com.t1dm.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A gaussian blur of the app backdrop, for a chrome surface that wants depth instead of a flat slab.
 *
 * Compose 1.7 has no behind-blur: `Modifier.blur` blurs a composable's OWN content, so putting it on
 * the bottom bar would blur the icons and labels and leave the backdrop untouched. What works is the
 * second-copy approach — draw [ThemeBackdrop] again, at window size, offset so the slice landing
 * inside these bounds is the same slice that is on screen behind them, and hand the whole layer a
 * [BlurEffect]. `minSdk` is 34 and `RenderEffect` needs 31, so there is no version guard and no
 * fallback path to keep in step.
 */

/** The blur radius. Wide enough that the Tron lattice reads as a wash rather than as smeared lines. */
val BackdropBlurRadius: Dp = 24.dp

/** The most transparent the scrim over the blur may ever go. Below this the bar stops reading as
 *  chrome and starts reading as a hole. */
const val BACKDROP_BLUR_SCRIM_MIN: Float = 0.55f

/** Past this the motif contributes too little to see, and an offscreen render target is being paid
 *  for nothing. The caller draws its flat surface instead. */
internal const val BACKDROP_BLUR_SCRIM_MAX = 0.90f

/**
 * How much of the flat surface's contrast the blur is allowed to cost, as a bound on the ratio
 * `(L + 0.05)` may move by. 1.15 = the contrast of ANY ink over this surface, whatever the theme
 * chose it to be, keeps at least ~87 % of what it had against the flat surface.
 */
internal const val BACKDROP_BLUR_TOLERANCE = 1.15f

/**
 * The scrim opacity at which a blurred backdrop is still safe to show under [surface], or `1f` for
 * "it is not — draw the flat surface".
 *
 * **What this defends against.** The failure case for a translucent bar is a bright thing blurred in
 * behind a light label. The argument that it cannot happen here has three parts, and only the third
 * is a mechanism:
 *
 * 1. Nothing but the backdrop is ever behind the bar. `Scaffold` gives its content lambda a bottom
 *    inset the app applies exactly once, so no screen's content — no glucose trace — passes under the
 *    bar to begin with. True today, and worth nothing tomorrow: it is one edge-to-edge change away
 *    from false, so nothing here may rest on it.
 * 2. A blur is a weighted mean of its neighbourhood, so every blurred pixel lies inside the convex
 *    hull of the pixels it sampled. Bounding the *sources* bounds the result.
 * 3. Those sources are bounded by black and white, and by nothing narrower. The painters do not stay
 *    within palette roles — Umbrella lerps its wedges toward `Color.Black`, and a drop-in
 *    `theme_bg_<id>.png` (which [ThemeBackdrop] honours) is an arbitrary user image that could be a
 *    white square. So the two extremes are the only sound bound, and this solves against them.
 *
 * For each of black and white behind the backdrop at [alphaPct] over [background], the composite
 * under a scrim of [surface] must keep its relative luminance within [BACKDROP_BLUR_TOLERANCE] of the
 * flat surface's. Luminance moves monotonically toward the surface as the scrim rises, so the first
 * value that satisfies both extremes is the smallest one that does — the most blur that is safe.
 *
 * The result degrades continuously rather than snapping: raising Settings → Display → Background
 * raises the scrim, so the effect fades out as the thing behind it gets louder, and only becomes
 * nothing once even [BACKDROP_BLUR_SCRIM_MAX] cannot hold the bound. The bound is stated against the
 * flat surface rather than an absolute AA floor deliberately — an absolute floor would refuse the
 * blur on Hello Kitty, whose muted ink stands at 4.16 on its own white bar before any of this, and
 * would be blaming the blur for a contrast the theme already had.
 *
 * **[hasMotif] is the second flat-backdrop door, and the reachable one.** `alphaPct <= 0` is a user
 * turning the backdrop off; [themeBackdropHasMotif] is false whenever the active theme has neither a
 * painter nor a drop-in raster — which is every imported theme, at every opacity. The layer handed to
 * a [BlurEffect] is then one uniform colour and the gaussian is provably an identity, so granting the
 * blur would buy an offscreen render target, a full-width filter and the loss of the bar's tonal lift
 * in exchange for the pixels that were already there.
 */
fun backdropBlurScrim(surface: Color, background: Color, alphaPct: Int, hasMotif: Boolean): Float {
    // ThemeBackdrop early-returns at 0, leaving a flat colour — and a flat colour blurred is itself.
    // The same holds at ANY opacity for a theme whose backdrop is a flat wash to begin with.
    if (alphaPct <= 0 || !hasMotif) return 1f
    val motifAlpha = (alphaPct / 100f).coerceIn(0f, 1f)
    val surfaceArgb = surface.toArgb()
    val backgroundArgb = background.toArgb()
    // The flat bar AS PAINTED. `surface` is a free role in an imported palette and may carry alpha,
    // and relativeLuminanceArgb reads alpha as opaque unless the colour is composited onto its
    // backing first — so the reference this entire bound is stated against would otherwise be a bar
    // nobody sees. Opaque in, unchanged out: the bundled palettes are untouched by this.
    val flat = relativeLuminanceArgb(compositeArgb(surfaceArgb, backgroundArgb)) + 0.05f
    val ceiling = flat * BACKDROP_BLUR_TOLERANCE - 0.05f
    val floor = flat / BACKDROP_BLUR_TOLERANCE - 0.05f
    val extremes = intArrayOf(Color.Black.toArgb(), Color.White.toArgb())

    var scrim = BACKDROP_BLUR_SCRIM_MIN
    while (scrim <= BACKDROP_BLUR_SCRIM_MAX + 1e-4f) {
        val safe = extremes.all { motif ->
            val behind = compositeArgb(argbWithAlpha(motif, motifAlpha), backgroundArgb)
            val bar = compositeArgb(argbWithAlpha(surfaceArgb, scrim), behind)
            val l = relativeLuminanceArgb(bar)
            l >= floor && l <= ceiling
        }
        if (safe) return scrim
        scrim += 0.01f
    }
    return 1f
}

/**
 * The blurred backdrop slice, sized and positioned to match what is actually on screen behind it.
 *
 * **What it costs, stated exactly.** The window size and this composable's origin are read in
 * `onGloballyPositioned` into two states that only ever change on a layout that moves the surface —
 * writing an unchanged value to a `mutableStateOf` is a no-op, so a sibling that scrolls invalidates
 * nothing here. The backdrop painter (~120 stroked lines on Tron, heavier on Hello Kitty) therefore
 * re-records when the theme, the backdrop opacity or the window geometry changes, and on no other
 * frame.
 *
 * The gaussian is not per-frame either, but for a different reason and one worth naming rather than
 * assuming: a `RenderNode` carrying a `RenderEffect` is promoted to a render layer, so HWUI keeps the
 * FILTERED result and re-runs the blur only when that layer's own content or bounds are invalidated.
 * Neither is touched by anything drawn beside it — the bar's ripple and its tile scroll live in the
 * sibling `Surface`, not in this node. What remains on an ordinary frame is one cached layer being
 * composited.
 *
 * The layer clips, so the blur's edge treatment is [TileMode.Clamp] — the outermost row of the slice
 * is extended rather than fading into transparency at the seam.
 */
@Composable
fun ThemeBackdropBlur(alphaPct: Int, modifier: Modifier = Modifier, radius: Dp = BackdropBlurRadius) {
    val palette = LocalT1dmSemantics.current
    val density = LocalDensity.current
    var window by remember { mutableStateOf(IntSize.Zero) }
    var topInWindow by remember { mutableStateOf(0) }
    Box(
        modifier
            .onGloballyPositioned { coords ->
                window = coords.findRootCoordinates().size
                topInWindow = coords.positionInRoot().y.roundToInt()
            }
            .graphicsLayer {
                clip = true
                val r = radius.toPx()
                renderEffect = BlurEffect(r, r, TileMode.Clamp)
            }
            // The opaque base ThemeBackdrop is drawn over everywhere else in the app, so the slice
            // composites identically to the one on screen rather than over whatever is beneath.
            .background(palette.background),
    ) {
        if (window.height > 0) {
            with(density) {
                ThemeBackdrop(
                    alphaPct,
                    Modifier
                        .offset(y = -topInWindow.toDp())
                        .requiredSize(window.width.toDp(), window.height.toDp()),
                )
            }
        }
    }
}
