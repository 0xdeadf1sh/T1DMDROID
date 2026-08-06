package com.t1dm.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmHaptics
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.design.navIcon
import com.t1dm.core.design.rememberHapticDetent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The navigation wheel — a hub in the bottom bar, and a half-circle of destinations that opens above
 * it under the thumb.
 *
 * The whole interaction is ONE decision: press, turn until the destination you want is under the
 * fixed marker at the top of the hub, let go. The marker never moves; the list moves past it. An
 * earlier build asked the thumb to leave the hub and reach out to a slot on the arc, which made
 * selecting a second act — aim, then commit — on a control whose point is to be operated without
 * looking.
 *
 * ONE rule governs this file, and it is a performance rule rather than a style one: **the gesture must
 * not recompose anything**. A press-turn-release runs at the panel's 165 Hz, so every quantity the
 * finger moves — the wheel's position, the reveal — is snapshot state read ONLY inside a draw
 * lambda. That is why the icons are drawn as [VectorPainter]s and the labels as
 * pre-measured [TextLayoutResult]s onto a single [Canvas] instead of being composed as `Icon`/`Text`
 * children: composed children would put a measure and layout pass behind every degree of rotation.
 * It is the discipline `Navigation.kt`'s status badge and the dashboard's `HeartbeatChip` already
 * state for their own endless animations — hold the State, unwrap it in the draw block.
 *
 * Those assets are built ONCE, in [NavWheelArc], which is composed for the app's whole life. Building
 * them inside the mounted overlay instead would rebuild fourteen `ImageVector`s, fourteen painter
 * subcompositions and fourteen text layouts on every single touch-down — on the frame the reveal
 * starts, which is the worst frame in the gesture to spend main-thread time on.
 *
 * A whole gesture costs four recompositions, all of them [NavWheelArc]'s as it observes `pressed` and
 * `mounted`. Nothing in between, and nothing at all while the wheel is shut: the hub's endless breath
 * drives a `graphicsLayer`, so at rest it updates a RenderNode property and does not even redraw.
 *
 * The tap-latched path is the exception and is deliberately built the other way — real composed touch
 * targets with real semantics AND a real `onClick` action, because a Canvas is a picture: TalkBack
 * cannot focus one, and Switch Access and Voice Access can only operate a node that publishes
 * `SemanticsActions.OnClick`. It is only ever composed while latched, so it costs the drag path
 * nothing. While latched the wheel is held at an INTEGER position, which is what lets those targets
 * sit exactly where the Canvas draws the icons they name.
 */

/** Angular pitch between neighbouring destinations on the arc. */
private const val SLOT_DEG = 26f

/** How far either side of the marker a destination is still drawn — how much of the list ahead and
 *  behind is visible while turning. Alpha reaches zero here, so it is a fade rather than a clip. */
private const val VISIBLE_HALF = 3.4f

/** Inside this the icon is at full opacity; beyond it, it fades out to nothing at [VISIBLE_HALF]. */
private const val SOLID_HALF = 2.0f

/** Outermost slot the latched menu offers a touch target for. Within [VISIBLE_HALF], so every target
 *  sits under a visible icon. */
private const val LATCHED_HALF = 3

/**
 * Thumb travel ALONG the turn, in dp, that advances the list by one destination.
 *
 * The gain is expressed as distance rather than as an angle, and that is what removes the dead zone.
 * Angular gain goes as 1/radius, so measuring the turn as an angle makes the dial unusable near its
 * middle — the same millimetre of travel that is a few degrees at the rim is tens of degrees at the
 * centre, and undefined at the exact centre. Measuring the TANGENTIAL ARC the thumb sweeps instead
 * gives a quantity that stays finite everywhere: at the rim it is the true angle times the radius,
 * and toward the middle it falls away smoothly to nothing on its own. Every part of the hub turns the
 * wheel, and none of it runs away.
 */
private val TURN_TRAVEL_DP = 42.dp

/** How much of the overlay width, and of the height above the hub, the arc may claim. The width term
 *  leaves room for the outermost label, which is wider than its icon. */
private const val ARC_W_FRAC = 0.38f
private const val ARC_H_FRAC = 0.74f

/** Latched-mode swipe: horizontal travel, as a fraction of the overlay width, that steps one slot. */
private const val LATCHED_SWIPE_FRAC = 0.16f

/** How far the app behind the arc is pushed back. Enough that the icons read cleanly over a busy
 *  graph, short of hiding what is underneath. */
private const val SCRIM_ALPHA = 0.90f

/** The scrim's clear centre, as a multiple of the hub radius, and the fraction of it that stays fully
 *  clear before the dimming ramps in. Wide enough to hold the swollen hub, its pointer and its glow. */
private const val SCRIM_FADE_R = 3.2f
private const val SCRIM_CLEAR = 0.55f

private val PUCK_DP = 104.dp

/** How much the hub swells while it is being turned. */
private const val PRESS_SWELL = 0.20f

/**
 * The glow, as a radius multiple of the hub and as alpha, split into its resting value and what the
 * breath and the press each add.
 *
 * The shape is drawn ONCE at [GLOW_R] and [GLOW_A_MAX]; the animation is a `graphicsLayer` scale and
 * alpha over it. That split is the whole point — a gradient built inside a draw lambda would allocate
 * a `Brush` and construct a native shader on every frame, and because the breath never stops it would
 * do so for the life of the process, on every screen in the app, while nothing is happening.
 */
private const val GLOW_R = 1.5f
private const val GLOW_R_BREATH = 0.22f
private const val GLOW_R_PRESS = 0.30f
private const val GLOW_A = 0.20f
private const val GLOW_A_BREATH = 0.20f
private const val GLOW_A_PRESS = 0.34f
private const val GLOW_A_MAX = GLOW_A + GLOW_A_BREATH + GLOW_A_PRESS

/**
 * The glow's own node, as a multiple of the puck, sized to contain the gradient at its LARGEST.
 *
 * A `graphicsLayer` carrying an alpha composites through a buffer the size of its node, so anything
 * the layer draws outside its own bounds is cropped — which showed up on device as a hard square edge
 * around the hub. Big enough that even the fully swollen glow fits inside, the crop has nothing to cut.
 */
private const val GLOW_BOX = 2.05f

/** The pointer above the hub, as a fraction of its radius: how far out it sits, and how wide it is. */
private const val POINTER_OUT = 1.02f
private const val POINTER_HALF_W = 0.15f
private const val POINTER_LEN = 0.20f

private val ARC_ICON_DP = 30.dp
private val ARC_LABEL_GAP_DP = 6.dp
private val ARC_SLOT_TOUCH_DP = 56.dp

/**
 * Everything the wheel's gesture writes and its two surfaces read.
 *
 * [offset] is the fractional list position sitting at the arc's centre slot — the wheel's angle,
 * expressed in destinations. It is unbounded and wraps on read, so turning never meets an end.
 */
@Stable
internal class NavWheelState(val count: Int) {
    /** Fractional list position under the centre slot. Written by the pointer loop; read in DRAW. */
    var offset by mutableFloatStateOf(0f)

    /** True from touch-down to release. One of the two states a gesture is allowed to recompose on. */
    var pressed by mutableStateOf(false)

    /** Tap-opened: the arc stays up as an ordinary tappable menu. The accessible path. */
    var latched by mutableStateOf(false)

    /**
     * The top-level destination the app is in, which is NOT the same as the current route: most routes
     * are sub-screens (`settings/display`, `models/{id}`, `meals/builder`) that are on no slot of the
     * wheel. Holding the section they were reached from is what stops the hub claiming to be on BG —
     * and re-seating itself there — every time the user drills down.
     */
    var seated by mutableIntStateOf(0)

    /** 0 = shut, 1 = fully open. An [Animatable] so the reveal is a draw property, not a recomposition. */
    val open = Animatable(0f)

    /** Hub centre and radius in ROOT coordinates, published by the puck for the overlay to draw around. */
    var centre by mutableStateOf(Offset.Unspecified)
    var radiusPx by mutableFloatStateOf(0f)

    /** Fold an unbounded position onto a list index. */
    fun wrap(n: Int): Int = ((n % count) + count) % count

    /** The destination the pointer is on: the slot the wheel has brought to the top. */
    fun pointed(): Int = wrap(offset.roundToInt())

    /** Drop any fractional part, so the drawn arc and the composed latched targets agree exactly. */
    fun quantise() {
        offset = offset.roundToInt().toFloat()
    }

    /**
     * Seat the wheel on [index] by the SHORTEST turn.
     *
     * [offset] is unbounded and the index is not, so assigning the index outright would jump the wheel
     * by up to a full revolution — visible as the arc spinning past a dozen destinations while it
     * closes. This picks the congruent position nearest to where the wheel already is.
     */
    fun seatOn(index: Int) {
        val base = floor(offset / count) * count
        var best = base + index
        for (cand in floatArrayOf(base + index - count, base + index + count)) {
            if (abs(cand - offset) < abs(best - offset)) best = cand
        }
        offset = best
    }

}

@Composable
internal fun rememberNavWheelState(count: Int): NavWheelState = remember(count) { NavWheelState(count) }

/**
 * The hub's two animated quantities: [press], which runs only while a finger is on the wheel, and
 * [breath], which never stops.
 *
 * Both are held in [T1dmApp] and deliberately NOT read there. Each is unwrapped inside a draw or
 * layer block, so neither recomposes anything — and [breath] in particular MUST stay out of any draw
 * lambda: it ticks at the panel rate for the life of the process, on every screen, so a draw that
 * reads it is a draw the app can never stop doing. It drives a `graphicsLayer` instead.
 */
@Stable
internal class NavWheelMotion(
    val press: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val breath: State<Float>,
)

@Composable
internal fun rememberNavWheelMotion(): NavWheelMotion {
    val animationsOn = LocalAnimationsEnabled.current
    val press = remember { Animatable(0f) }
    // The idle life of the hub is a GLOW that breathes — a halo swelling and fading around the rim,
    // not a highlight travelling around it. A rotating mark on a dial reads as the dial turning by
    // itself, which is a lie about a control that has not been touched. This never ends, so it must
    // invalidate the draw and nothing above it.
    val breath: State<Float> = if (animationsOn) {
        rememberInfiniteTransition(label = "wheelIdle").animateFloat(
            initialValue = 0.18f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
            label = "wheelBreath",
        )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }
    return remember(press, breath) { NavWheelMotion(press, breath) }
}

/**
 * The arc's per-theme drawing assets, built once and held for the app's life.
 *
 * Both lists are indexed by destination. They are theme-dependent (the glyph geometry is re-derived
 * per palette) and so are rebuilt on a theme change and at no other time.
 */
@Stable
internal class NavWheelAssets(
    val painters: List<VectorPainter>,
    val labels: List<TextLayoutResult>,
)

/**
 * The hub, sized for the bottom bar.
 *
 * The destination the app is IN is drawn in the middle so the wheel says where it is while it is shut.
 * A control whose entire affordance appears only on touch is a control nobody presses.
 */
@Composable
internal fun NavWheelPuck(
    state: NavWheelState,
    motion: NavWheelMotion,
    destinations: List<Destination>,
    currentRoute: String?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val palette = LocalT1dmSemantics.current
    val style = iconStyleForTheme(palette.id)
    val haptics = LocalT1dmHaptics.current
    val turnDetent = rememberHapticDetent(HapticEvent.SegmentTick)

    // Kept current without re-keying the pointer loop, which must survive a recomposition mid-gesture.
    val select by rememberUpdatedState(onSelect)

    // -1 for every sub-screen. Only a real top-level arrival moves the wheel; a drill-down leaves it
    // on the section it descended from.
    val routeIndex = remember(currentRoute, destinations) {
        destinations.indexOfFirst { it.route == currentRoute }
    }
    // Read by the pointer loop on release, which outlives any single composition — `pointerInput` is
    // keyed on the state object alone so the gesture survives a recomposition mid-turn, and a plain
    // capture here would pin the route the wheel was first composed on.
    val liveRouteIndex by rememberUpdatedState(routeIndex)
    LaunchedEffect(routeIndex) {
        if (routeIndex < 0) return@LaunchedEffect
        state.seated = routeIndex
        if (!state.pressed && !state.latched) state.seatOn(routeIndex)
    }

    val seated = state.seated
    val hubPainter = rememberVectorPainter(
        remember(seated, style, destinations) { navIcon(destinations[seated].route, style) },
    )

    val hubLabel = destinations[seated].label
    // Rebuilt in place each frame rather than allocated: the hub redraws on every frame of a turn.
    val pointerPath = remember { Path() }
    val glowInk = cs.primary

    Box(
        modifier
            .size(PUCK_DP)
            // ITS OWN LAYER. Compose walks up from an invalidated draw to the nearest coordinator that
            // owns one, and the bar no longer has a `Surface` to supply it — so without this the hub's
            // press animation would dirty the Scaffold's full-window RenderNode and force the whole
            // screen's display list to be re-recorded. `clip` stays false: the glow and the pointer
            // both reach outside these bounds, which is why the Surface had to go in the first place.
            .graphicsLayer()
            .onGloballyPositioned {
                val p = it.positionInRoot()
                state.centre = Offset(p.x + it.size.width / 2f, p.y + it.size.height / 2f)
                state.radiusPx = min(it.size.width, it.size.height) / 2f
            }
            .semantics {
                role = Role.Button
                contentDescription = "Navigation wheel, on $hubLabel"
                // Role.Button alone leaves `isClickable` false on the node, so Switch Access finds
                // nothing to scan and Voice Access has no action to issue. The gesture below is the
                // pointer path; this is the one the accessibility framework can actually operate.
                onClick(label = "Open navigation") {
                    state.quantise()
                    state.latched = true
                    true
                }
            }
            .pointerInput(state) {
                val travelPerItem = TURN_TRAVEL_DP.toPx()
                val cx = size.width / 2f
                val cy = size.height / 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var prev = down.position
                    // Where the wheel stood when the finger landed. It decides BOTH questions at
                    // release: whether the user chose anything (did the wheel reach a different
                    // destination?) and, if not, what to put back.
                    val startOffset = state.offset
                    val startSlot = state.pointed()
                    // Set when the platform CANCELS the gesture rather than the user ending it. Compose
                    // does not unwind this coroutine on ACTION_CANCEL; it synthesises a released change
                    // that arrives already consumed. Without this the app would navigate off a gesture
                    // the system took away — an alarm's full-screen intent, or the shade being pulled.
                    var cancelled = false

                    state.pressed = true
                    state.latched = false
                    turnDetent.reset()
                    haptics.perform(HapticEvent.DragStart)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            cancelled = change.isConsumed
                            break
                        }
                        change.consume()

                        // Turn by the TANGENTIAL ARC swept about the hub. In y-up terms the cross
                        // product of the two radius vectors is |a||b|sin(dθ), so dividing by the mean
                        // radius leaves r·dθ — the distance the thumb travelled ALONG the turn. Every
                        // part of the hub therefore turns the wheel, including the middle, and the gain
                        // falls off smoothly toward the centre instead of diverging there: a lateral
                        // flick across the exact centre sweeps almost no arc and moves the wheel
                        // almost not at all. No dead zone, no ±180° seam, no quadrant sign to get
                        // wrong.
                        val p = change.position
                        val ax = prev.x - cx
                        val ay = -(prev.y - cy)
                        val bx = p.x - cx
                        val by = -(p.y - cy)
                        val rMean = (hypot(ax, ay) + hypot(bx, by)) * 0.5f
                        if (rMean > 1f) {
                            val arc = (ax * by - ay * bx) / rMean
                            state.offset += arc / travelPerItem
                            turnDetent.at(state.offset.roundToInt())
                        }
                        prev = p
                    }

                    state.pressed = false
                    val pointed = state.pointed()
                    // RELEASE COMMITS THE POINTER — but only if the wheel actually TURNED to a
                    // different destination. Turning is the act of choosing, so a press that never
                    // moved, or one wound away and back, has chosen nothing and must leave the app
                    // where it was.
                    //
                    // The test is against where the wheel started, NOT against the current route: on
                    // the app's two dozen sub-screens (`settings/display`, `models/{id}`,
                    // `meals/builder`, …) there IS no matching destination, so a route comparison read
                    // "different" unconditionally and every stray brush of the hub threw the user out
                    // of the screen they were on.
                    if (!cancelled && pointed != startSlot) {
                        state.quantise()
                        select(pointed)
                    } else {
                        // Nothing was chosen, so nothing is kept: leaving the wheel wound would arm it
                        // on a destination the user did not pick, one stray tap away from committing.
                        state.offset = startOffset
                        if (!cancelled) haptics.perform(HapticEvent.DragEnd)
                    }
                }
            },
        // The glow's node is deliberately larger than the hub, so it has to be centred on it rather
        // than pinned to the Box's top-left.
        contentAlignment = Alignment.Center,
    ) {
        // THE GLOW, and the reason it is a sibling node rather than part of the dial below: its shape
        // is built once and the animation rides on a LAYER, so the breath — which never stops, on
        // every screen, for the life of the process — costs a RenderNode property update per frame
        // and not a redraw. Built inside the draw lambda it allocated a Brush and constructed a native
        // gradient shader 165 times a second, forever, with the wheel untouched.
        Box(
            Modifier
                .requiredSize(PUCK_DP * GLOW_BOX)
                .graphicsLayer {
                    val b = motion.breath.value
                    val p = motion.press.value
                    val s = (GLOW_R + GLOW_R_BREATH * b + GLOW_R_PRESS * p) / GLOW_R
                    scaleX = s
                    scaleY = s
                    alpha = ((GLOW_A + GLOW_A_BREATH * b + GLOW_A_PRESS * p) / GLOW_A_MAX)
                        .coerceIn(0f, 1f)
                }
                .drawWithCache {
                    // Rebuilt only when this node's size or the theme changes. The radius is the
                    // PUCK's, recovered from the oversized box that holds it.
                    val gr = size.minDimension / 2f * (GLOW_R / GLOW_BOX)
                    val mid = Offset(size.width / 2f, size.height / 2f)
                    val brush = Brush.radialGradient(
                        0.00f to glowInk.copy(alpha = GLOW_A_MAX),
                        0.38f to glowInk.copy(alpha = GLOW_A_MAX * 0.72f),
                        0.70f to glowInk.copy(alpha = GLOW_A_MAX * 0.24f),
                        1.00f to Color.Transparent,
                        center = mid,
                        radius = gr,
                    )
                    onDrawBehind { drawCircle(brush, radius = gr, center = center) }
                },
        )
        Canvas(Modifier.fillMaxSize()) {
            // This is the ONLY place the dial is ever drawn, open or shut. The overlay used to redraw
            // it over its own backdrop, and that second copy did not render identically: frame-by-frame
            // capture of a press showed the glyph tracking its centre exactly while this canvas owned
            // it, then jumping 14px right on the single frame the overlay took over. `Painter.draw`
            // resolves against the DrawScope it is handed, and reproducing this one inside a
            // full-screen canvas well enough to be pixel-identical is not a thing worth getting right
            // — so the overlay clears around it and lets the real hub show through.
            //
            // Nothing here reads the breath, so at rest this canvas is not invalidated at all.
            drawHub(
                c = Offset(size.width / 2f, size.height / 2f),
                r = min(size.width, size.height) / 2f,
                press = motion.press.value,
                pointerPath = pointerPath,
                offset = state.offset,
                count = state.count,
                cs = cs,
                plate = palette.surfaceVariant,
                grid = palette.grid,
                painter = hubPainter,
            )
        }
    }
}

/**
 * The dial: plate, knurl, rim, pointer and the destination glyph. NOT the glow, which is a sibling
 * layer above this — see the call site.
 *
 * Every term here is a function of [press] alone, so this draws only while a gesture is in flight and
 * is otherwise recorded once.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHub(
    c: Offset,
    r: Float,
    press: Float,
    pointerPath: Path,
    offset: Float,
    count: Int,
    cs: androidx.compose.material3.ColorScheme,
    plate: Color,
    grid: Color,
    painter: VectorPainter,
) {
    val thin = 1.5.dp.toPx()

    scale(1f + PRESS_SWELL * press, c) {
        drawCircle(plate, radius = r * 0.80f, center = c)

        // The knurl: 36 ticks that turn with the wheel, which is what sells the hub as a dial rather
        // than a button. One destination is one fourteenth of a revolution — a display mapping, NOT
        // the thumb's own angular rate, which the travel-based gain deliberately decouples from the
        // dial's geometry. Every third tick is major, giving the eye a coarse index to track.
        //
        // NEGATED: `rotate` is clockwise-positive on a y-down canvas, while `offset` FALLS under a
        // clockwise finger (that is what slides the arc's icons right, with the thumb). Without the
        // sign the ticks would creep left under a thumb moving right, in the same frame the arc above
        // moved right.
        // One destination is one fourteenth of a turn, so the knurl and the index arc below it agree:
        // a full revolution of the dial is a full pass round the ring.
        rotate(-offset * 360f / count, c) {
            for (i in 0 until 36) {
                val a = Math.toRadians(i * 10.0)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val major = i % 3 == 0
                val inner = if (major) 0.60f else 0.68f
                drawLine(
                    color = if (major) {
                        cs.primary.copy(alpha = 0.55f + 0.35f * press)
                    } else {
                        grid.copy(alpha = 0.40f)
                    },
                    start = Offset(c.x + ca * r * inner, c.y + sa * r * inner),
                    end = Offset(c.x + ca * r * 0.76f, c.y + sa * r * 0.76f),
                    strokeWidth = if (major) 2f else 1f,
                )
            }
        }

        drawCircle(
            color = cs.primary.copy(alpha = 0.35f + 0.45f * press),
            radius = r * 0.80f,
            center = c,
            style = Stroke(width = thin),
        )
        // THE POINTER, and only while the wheel is UP. It does not move: the list turns under it, and
        // whatever it is aimed at when the thumb lifts is where the app goes. Shut, there is no list
        // for it to point at and nothing to choose, so it fades out with the arc it belongs to.
        if (press > 0.01f) {
            pointerPath.rewind()
            pointerPath.moveTo(c.x, c.y - r * (POINTER_OUT + POINTER_LEN))
            pointerPath.lineTo(c.x - r * POINTER_HALF_W, c.y - r * POINTER_OUT)
            pointerPath.lineTo(c.x + r * POINTER_HALF_W, c.y - r * POINTER_OUT)
            pointerPath.close()
            drawPath(pointerPath, cs.secondary.copy(alpha = press))
        }
    }

    // The glyph's RENDERING is press-invariant; the swell is a pure transform about the hub centre.
    //
    // This split is deliberate and was arrived at by measurement. `Painter.draw` decides where to put
    // its content by comparing the size it is handed against the DrawScope's own — and the exact
    // relationship is not one worth depending on, because every formulation that fed the press factor
    // INTO that comparison came out asymmetric on device: frame-by-frame capture of the press ramp
    // showed the glyph box growing 4px up-and-left against 15px down-and-right, walking its centre
    // 5.5px toward the bottom-right corner over a swell that should not have moved it at all.
    //
    // So nothing below varies with [press]: `base` is fixed, the inset makes the DrawScope exactly the
    // glyph box, and `draw` is handed that same size. Whatever the painter does with it, it does
    // identically on every frame. The only press term is the enclosing `scale`, an affine about `c`,
    // which cannot move a centre that starts on the pivot.
    val base = r * 0.52f
    scale(1f + PRESS_SWELL * press, c) {
        translate(left = c.x - base / 2f, top = c.y - base / 2f) {
            inset(left = 0f, top = 0f, right = size.width - base, bottom = size.height - base) {
                with(painter) {
                    draw(
                        size,
                        alpha = 0.65f + 0.35f * press,
                        colorFilter = ColorFilter.tint(cs.primary),
                    )
                }
            }
        }
    }
}

/**
 * The half-circle of destinations, drawn in the root Box ABOVE the Scaffold.
 *
 * It cannot share the `bottomBar` slot with the hub. The Scaffold measures that slot to its content,
 * so a full-screen overlay inside it would set the bar's height to the window; and the slot is drawn
 * before the content, so an arc placed there would sit UNDER the screen it is meant to cover.
 * Hoisting it here also puts it over the graph, which is where it has to be.
 *
 * This wrapper owns the mount decision and the drawing assets. Reading `open.value` here would
 * recompose the overlay on every frame of the reveal, which is exactly what this file exists to
 * avoid; building the assets below the mount gate would rebuild all fourteen of them per touch.
 */
@Composable
internal fun NavWheelArc(
    state: NavWheelState,
    motion: NavWheelMotion,
    destinations: List<Destination>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = iconStyleForTheme(LocalT1dmSemantics.current.id)
    val icons: List<ImageVector> = remember(style, destinations) {
        destinations.map { navIcon(it.route, style) }
    }
    val painters: List<VectorPainter> = icons.map { rememberVectorPainter(it) }

    val measurer = rememberTextMeasurer(cacheSize = 0)
    // Colour is applied at draw time, so the measurement is colour-independent and survives a theme
    // change without re-measuring fourteen strings.
    val labelStyle = remember { TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White) }
    val labels: List<TextLayoutResult> = remember(measurer, labelStyle, destinations) {
        destinations.map { measurer.measure(it.label, labelStyle) }
    }
    val assets = remember(painters, labels) { NavWheelAssets(painters, labels) }

    var mounted by remember { mutableStateOf(false) }
    val animationsOn = LocalAnimationsEnabled.current
    val wanted = state.pressed || state.latched
    LaunchedEffect(wanted, animationsOn) {
        if (wanted) {
            mounted = true
            state.open.animateTo(1f, tween(if (animationsOn) 190 else 0))
        } else {
            state.open.animateTo(0f, tween(if (animationsOn) 150 else 0))
            mounted = false
        }
    }
    // The hub's press response is driven here rather than in the puck: this is the composable that
    // already tracks `pressed`/`latched`, and the puck must not recompose on either.
    LaunchedEffect(wanted, animationsOn) {
        motion.press.animateTo(if (wanted) 1f else 0f, tween(if (animationsOn) 160 else 0))
    }
    if (mounted) NavWheelArcContent(state, destinations, assets, onSelect, modifier)
}

@Composable
private fun NavWheelArcContent(
    state: NavWheelState,
    destinations: List<Destination>,
    assets: NavWheelAssets,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val palette = LocalT1dmSemantics.current
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelect)
    val swipeDetent = rememberHapticDetent(HapticEvent.SegmentTick)

    // The overlay's own origin and size, so the hub's root-space centre lands in this Canvas's local
    // space and the latched targets can be placed on the same arc the Canvas draws.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlay by remember { mutableStateOf(IntSize.Zero) }
    val scrimInk = palette.background.copy(alpha = SCRIM_ALPHA)

    val latched = state.latched
    if (latched) BackHandler { state.latched = false }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .onSizeChanged { overlay = it }
            .then(
                if (latched) {
                    Modifier.pointerInput(state) {
                        // Latched mode is point-and-tap, but a swipe still winds the wheel so every
                        // destination stays reachable without a drag from the hub. It steps in WHOLE
                        // slots: a fractional offset here would put the composed targets off the icons
                        // the Canvas draws, and would recompose them on every pointer sample.
                        var acc = 0f
                        val step = size.width * LATCHED_SWIPE_FRAC
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onDragEnd = { acc = 0f },
                            onDragCancel = { acc = 0f },
                        ) { _, dx ->
                            acc += dx
                            while (acc >= step) {
                                acc -= step
                                state.offset -= 1f
                            }
                            while (acc <= -step) {
                                acc += step
                                state.offset += 1f
                            }
                            swipeDetent.at(state.offset.roundToInt())
                        }
                    }.pointerInput(state) {
                        detectTapGestures { state.latched = false }
                    }
                } else {
                    // While the arc is up under a thumb, this layer SWALLOWS every other pointer. The
                    // app is still there under a 0.90 scrim and stays fully live otherwise, so a second
                    // finger could log a dose or drag the graph behind a menu the user believes is
                    // modal. It consumes rather than acts: the turning finger was hit-tested to the hub
                    // before this node existed and keeps its own stream.
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            ),
    ) {
        // THE BACKDROP. It DARKENS the app rather than replacing it: the tint is the theme's own
        // background, so what shows through settles toward the colour the app already is instead of
        // reading as a grey modal layer, but it stays translucent — the graph, the chips and the
        // read-out are meant to remain legible behind the arc, just pushed back. Opaque was tried and
        // the screen simply vanished for the length of the gesture.
        //
        // It clears around the hub so the real one shows through while the thumb is on it, as a radial
        // FADE rather than a punched circle: a hard edge cropped whatever reached past its radius,
        // which is how the pointer went missing exactly when the wheel came up, and it would crop the
        // glow the same way.
        //
        // Same construction as the glow, for the same reason: the gradient is built once per size, the
        // reveal is a layer alpha, and the shader runs only over the square where it actually varies —
        // clamped flat everywhere else, which on this screen is most of it.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = state.open.value }
                .drawWithCache {
                    val hub = state.centre
                    val c = if (hub == Offset.Unspecified) {
                        Offset(size.width / 2f, size.height)
                    } else {
                        Offset(hub.x - origin.x, hub.y - origin.y)
                    }
                    val fade = state.radiusPx.coerceAtLeast(1f) * SCRIM_FADE_R
                    val flat = scrimInk
                    val brush = Brush.radialGradient(
                        0.00f to Color.Transparent,
                        SCRIM_CLEAR to Color.Transparent,
                        1.00f to flat,
                        center = c,
                        radius = fade,
                    )
                    onDrawBehind {
                        val l = (c.x - fade).coerceIn(0f, size.width)
                        val t = (c.y - fade).coerceIn(0f, size.height)
                        val rt = (c.x + fade).coerceIn(0f, size.width)
                        val b = (c.y + fade).coerceIn(0f, size.height)
                        drawRect(flat, Offset(0f, 0f), Size(size.width, t))
                        drawRect(flat, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(flat, Offset(0f, t), Size(l, b - t))
                        drawRect(flat, Offset(rt, t), Size(size.width - rt, b - t))
                        drawRect(brush, Offset(l, t), Size(rt - l, b - t))
                    }
                },
        )

        Canvas(Modifier.fillMaxSize()) {
            val op = state.open.value
            if (op <= 0.001f) return@Canvas
            val hub = state.centre
            if (hub == Offset.Unspecified) return@Canvas
            val c = Offset(hub.x - origin.x, hub.y - origin.y)
            val arcR = arcRadius(size.width, c.y)
            if (arcR <= 0f) return@Canvas
            val off = state.offset

            val iconPx = ARC_ICON_DP.toPx()
            val gapPx = ARC_LABEL_GAP_DP.toPx()
            // The slot the pointer is on — the one nearest the top of the arc. Marked by colour alone,
            // matching the marker on the hub below it.
            val pointedN = off.roundToInt()

            var n = floor(off - VISIBLE_HALF).toInt()
            val last = ceil(off + VISIBLE_HALF).toInt()
            while (n <= last) {
                val k = n - off
                // Flat through the middle, then a fade only over the last stretch: the slots the thumb
                // can reach stay fully legible, and the outer ones fall away rather than ending at a
                // hard edge. It governs what is DRAWN only — selection is the marker's alone.
                val t = ((abs(k) - SOLID_HALF) / (VISIBLE_HALF - SOLID_HALF)).coerceIn(0f, 1f)
                val a = (1f - t * t) * op
                if (a > 0.01f) {
                    val idx = state.wrap(n)
                    val isHover = n == pointedN
                    // Fade and shrink away from the centre; the reveal itself scales the whole fan out
                    // of the hub.
                    val grow = (0.80f + 0.34f * (1f - t)) * (0.72f + 0.28f * op)
                    val rad = arcR * (0.86f + 0.14f * op)
                    val ang = Math.toRadians((90f - k * SLOT_DEG).toDouble())
                    val x = c.x + rad * cos(ang).toFloat()
                    val y = c.y - rad * sin(ang).toFloat()
                    val edge = iconPx * grow

                    // The slot under the thumb is marked by COLOUR alone — no ring. The icon and its
                    // label take the theme's primary while everything else stays on-surface ink, which
                    // is legible without putting another shape on an already busy arc.
                    translate(left = x - edge / 2f, top = y - edge / 2f) {
                        with(assets.painters[idx]) {
                            draw(
                                Size(edge, edge),
                                alpha = a,
                                colorFilter = ColorFilter.tint(if (isHover) cs.primary else cs.onSurface),
                            )
                        }
                    }
                    val layout = assets.labels[idx]
                    drawText(
                        textLayoutResult = layout,
                        color = (if (isHover) cs.primary else cs.onSurfaceVariant)
                            .copy(alpha = a * if (isHover) 1f else 0.85f),
                        topLeft = Offset(x - layout.size.width / 2f, y + edge / 2f + gapPx),
                    )
                }
                n++
            }
        }

        // Latched mode gets REAL touch targets with a REAL click action: the Canvas above is a picture,
        // TalkBack cannot focus one, and Switch Access and Voice Access can only drive a node that
        // publishes SemanticsActions.OnClick. Composed only while latched, so the press-turn-release
        // path never pays for this layout — and safe to place at integer slots because the wheel is
        // held at an integer position for as long as it is latched.
        if (latched) {
            val slotPx = with(density) { ARC_SLOT_TOUCH_DP.roundToPx() }
            for (k in -LATCHED_HALF..LATCHED_HALF) {
                val idx = state.wrap(state.offset.roundToInt() + k)
                val label = destinations[idx].label
                val go = {
                    state.latched = false
                    state.offset = (state.offset.roundToInt() + k).toFloat()
                    select(idx)
                }
                Box(
                    Modifier
                        .size(ARC_SLOT_TOUCH_DP)
                        .offset {
                            val hub = state.centre
                            if (hub == Offset.Unspecified || overlay.width == 0) return@offset IntOffset.Zero
                            val hc = Offset(hub.x - origin.x, hub.y - origin.y)
                            val arcR = arcRadius(overlay.width.toFloat(), hc.y)
                            val ang = Math.toRadians((90f - k * SLOT_DEG).toDouble())
                            IntOffset(
                                (hc.x + arcR * cos(ang).toFloat() - slotPx / 2f).roundToInt(),
                                (hc.y - arcR * sin(ang).toFloat() - slotPx / 2f).roundToInt(),
                            )
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = label
                            onClick(label = label) {
                                go()
                                true
                            }
                        }
                        // No haptic here: the select callback speaks NavSwitch for every arrival, by
                        // this path and by the drag's.
                        .pointerInput(idx, k) { detectTapGestures { go() } },
                )
            }
        }
    }
}

/** The arc's radius: as wide as the overlay allows, but never so tall that it climbs off the top. */
private fun arcRadius(widthPx: Float, hubCentreY: Float): Float =
    min(widthPx * ARC_W_FRAC, hubCentreY * ARC_H_FRAC)

