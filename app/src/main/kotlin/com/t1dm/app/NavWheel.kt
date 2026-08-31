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
 * The gesture must not recompose: every quantity the finger moves is snapshot state read only inside
 * a draw lambda, so icons and labels are drawn onto one Canvas rather than composed, and the assets
 * are built in [NavWheelArc], above the mount gate. The latched path alone is composed, for TalkBack.
 */

private const val SLOT_DEG = 26f

/** Half-width, in slots, of what is drawn either side of the marker. Alpha reaches zero here. */
private const val VISIBLE_HALF = 3.4f

/** Slots within which the icon is at full opacity. */
private const val SOLID_HALF = 2.0f

/** Outermost latched touch target. Within [VISIBLE_HALF], so each sits under a drawn icon. */
private const val LATCHED_HALF = 3

/** Thumb travel along the turn, in dp, per destination. Distance rather than angle: angular gain
 *  goes as 1/radius and is undefined at the hub's centre. */
private val TURN_TRAVEL_DP = 42.dp

/** Fractions of the overlay width, and of the height above the hub, the arc may claim. */
private const val ARC_W_FRAC = 0.38f
private const val ARC_H_FRAC = 0.74f

/** Horizontal travel, as a fraction of the overlay width, that steps one slot while latched. */
private const val LATCHED_SWIPE_FRAC = 0.16f

private const val SCRIM_ALPHA = 0.90f

/** The scrim's clear centre, as a multiple of the hub radius, and the fraction of it that stays fully
 *  clear. Both stay inside the icon ring at about 2.7r, or the falloff reads as a point light under
 *  the thumb; the clear radius holds the swollen hub and the pointer above it. */
private const val SCRIM_FADE_R = 2.0f
private const val SCRIM_CLEAR = 0.75f

private val PUCK_DP = 104.dp

private const val PRESS_SWELL = 0.20f

/** Glow radius, as a multiple of the hub, and alpha: resting value plus breath plus press. Drawn once
 *  at [GLOW_R] and [GLOW_A_MAX] and animated by a layer; a `Brush` built in the draw lambda would
 *  construct a shader every frame for the life of the process. */
private const val GLOW_R = 1.5f
private const val GLOW_R_BREATH = 0.22f
private const val GLOW_R_PRESS = 0.30f
private const val GLOW_A = 0.20f
private const val GLOW_A_BREATH = 0.20f
private const val GLOW_A_PRESS = 0.34f
private const val GLOW_A_MAX = GLOW_A + GLOW_A_BREATH + GLOW_A_PRESS

/** The glow's node, as a multiple of the puck. A layer carrying an alpha crops to its own bounds, so
 *  this must contain the gradient at its largest. */
private const val GLOW_BOX = 2.05f

/** Fractions of the hub radius. */
private const val POINTER_OUT = 1.02f
private const val POINTER_HALF_W = 0.15f
private const val POINTER_LEN = 0.20f

private val ARC_ICON_DP = 30.dp
private val ARC_LABEL_GAP_DP = 6.dp
private val ARC_SLOT_TOUCH_DP = 56.dp

@Stable
internal class NavWheelState(val count: Int) {
    /** Fractional list position under the centre slot, unbounded. Written by the pointer loop, read in draw. */
    var offset by mutableFloatStateOf(0f)

    var pressed by mutableStateOf(false)

    /** Tap-opened menu; the accessible path. */
    var latched by mutableStateOf(false)

    /** The top-level destination, not the current route: sub-screens sit on no slot of the wheel. */
    var seated by mutableIntStateOf(0)

    /** 0 = shut, 1 = open. [Animatable] so the reveal is a draw property, not a recomposition. */
    val open = Animatable(0f)

    /** Hub centre and radius in root coordinates. */
    var centre by mutableStateOf(Offset.Unspecified)
    var radiusPx by mutableFloatStateOf(0f)

    fun wrap(n: Int): Int = ((n % count) + count) % count

    fun pointed(): Int = wrap(offset.roundToInt())

    /** Integer position, so the composed latched targets sit exactly on the drawn icons. */
    fun quantise() {
        offset = offset.roundToInt().toFloat()
    }

    /** Seat on [index] by the shortest turn; [offset] is unbounded, so assigning the index would spin
     *  the wheel by up to a full revolution. */
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

/** [press] runs only while a finger is on the wheel; [breath] never stops, so it must stay out of
 *  every draw lambda and drive a `graphicsLayer` instead. Neither may be read in composition. */
@Stable
internal class NavWheelMotion(
    val press: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    val breath: State<Float>,
)

@Composable
internal fun rememberNavWheelMotion(): NavWheelMotion {
    val animationsOn = LocalAnimationsEnabled.current
    val press = remember { Animatable(0f) }
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

/** Both lists are indexed by destination. Rebuilt on a theme change and at no other time. */
@Stable
internal class NavWheelAssets(
    val painters: List<VectorPainter>,
    val labels: List<TextLayoutResult>,
)

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

    // Current without re-keying the pointer loop, which must survive a recomposition mid-gesture.
    val select by rememberUpdatedState(onSelect)

    // -1 on every sub-screen; only a top-level arrival moves the wheel.
    val routeIndex = remember(currentRoute, destinations) {
        destinations.indexOfFirst { it.route == currentRoute }
    }
    // The pointer loop outlives any single composition, so a plain capture would pin one route.
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
    // Rebuilt in place each frame rather than allocated.
    val pointerPath = remember { Path() }
    val glowInk = cs.primary

    Box(
        modifier
            .size(PUCK_DP)
            // Without its own layer the press animation dirties the Scaffold's full-window RenderNode.
            // `clip` stays false: the glow and the pointer both reach outside these bounds.
            .graphicsLayer()
            .onGloballyPositioned {
                val p = it.positionInRoot()
                state.centre = Offset(p.x + it.size.width / 2f, p.y + it.size.height / 2f)
                state.radiusPx = min(it.size.width, it.size.height) / 2f
            }
            .semantics {
                role = Role.Button
                contentDescription = "Navigation wheel, on $hubLabel"
                // Role.Button alone leaves `isClickable` false, so Switch Access finds nothing to scan.
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
                    val startOffset = state.offset
                    val startSlot = state.pointed()
                    // Compose does not unwind on ACTION_CANCEL; it synthesises a released change that
                    // arrives already consumed. Without this the app navigates off a cancelled gesture.
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

                        // Turn by the tangential arc about the hub: the cross product of the two radius
                        // vectors (y negated to y-up) is |a||b|sin(dθ), so dividing by the mean radius
                        // leaves r·dθ.
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
                    // Committed only if the wheel turned, tested against the start slot: on a sub-screen
                    // no destination matches the route, so a route test reads "different" always and
                    // any stray brush of the hub navigates away.
                    if (!cancelled && pointed != startSlot) {
                        state.quantise()
                        select(pointed)
                    } else {
                        // Leaving the wheel wound would arm a destination the user did not pick.
                        state.offset = startOffset
                        if (!cancelled) haptics.perform(HapticEvent.DragEnd)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
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
                    // The radius is the puck's, recovered from the oversized box that holds it.
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
            // The only place the dial is drawn. `Painter.draw` resolves against the DrawScope it is
            // handed, so a second copy in the overlay's full-screen canvas landed 14px off.
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

/** The dial, without the glow — that is a sibling layer. Every term is a function of [press] alone. */
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

        // Negated: `rotate` is clockwise-positive on a y-down canvas while `offset` falls under a
        // clockwise finger, so without the sign the ticks creep against the arc above.
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
        if (press > 0.01f) {
            pointerPath.rewind()
            pointerPath.moveTo(c.x, c.y - r * (POINTER_OUT + POINTER_LEN))
            pointerPath.lineTo(c.x - r * POINTER_HALF_W, c.y - r * POINTER_OUT)
            pointerPath.lineTo(c.x + r * POINTER_HALF_W, c.y - r * POINTER_OUT)
            pointerPath.close()
            drawPath(pointerPath, cs.secondary.copy(alpha = press))
        }
    }

    // Nothing below may vary with [press]: `Painter.draw` places content by comparing the size it is
    // handed against the DrawScope's own, and a press term in that comparison walked the glyph's
    // centre 5.5px on device. The enclosing scale is an affine about `c`, so it cannot.
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

/** In the root Box above the Scaffold: the `bottomBar` slot is measured to its content and drawn
 *  before it. Owns the mount decision and the assets; must not read `open.value`. */
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
    // Colour is applied at draw time, so the measurement survives a theme change.
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
    // Driven here, not in the puck: the puck must not recompose on `pressed` or `latched`.
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

    // Overlay origin and size, to put the hub's root-space centre into this Canvas's local space.
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
                        // Whole slots only: a fractional offset would put the composed targets off the
                        // icons the Canvas draws, and recompose them on every pointer sample.
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
                    // Swallows every other pointer: the app underneath stays live, so a second finger
                    // could log a dose behind a menu the user believes is modal. The turning finger
                    // was hit-tested to the hub already and keeps its own stream.
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
        // Clears around the hub as a radial fade, not a punched circle: a hard edge crops whatever
        // reaches past its radius, the pointer and the glow included. The shader runs only over the
        // square where it varies; the rest is flat fill.
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
            val pointedN = off.roundToInt()

            var n = floor(off - VISIBLE_HALF).toInt()
            val last = ceil(off + VISIBLE_HALF).toInt()
            while (n <= last) {
                val k = n - off
                // Governs what is drawn only; selection is the marker's alone.
                val t = ((abs(k) - SOLID_HALF) / (VISIBLE_HALF - SOLID_HALF)).coerceIn(0f, 1f)
                val a = (1f - t * t) * op
                if (a > 0.01f) {
                    val idx = state.wrap(n)
                    val isHover = n == pointedN
                    val grow = (0.80f + 0.34f * (1f - t)) * (0.72f + 0.28f * op)
                    val rad = arcR * (0.86f + 0.14f * op)
                    val ang = Math.toRadians((90f - k * SLOT_DEG).toDouble())
                    val x = c.x + rad * cos(ang).toFloat()
                    val y = c.y - rad * sin(ang).toFloat()
                    val edge = iconPx * grow

                    translate(left = x - edge / 2f, top = y - edge / 2f) {
                        with(assets.painters[idx]) {
                            draw(
                                Size(edge, edge),
                                alpha = a,
                                colorFilter = ColorFilter.tint(if (isHover) cs.primary else cs.onSurfaceVariant),
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

        // Real targets with a real click action: TalkBack cannot focus the Canvas above. Safe at
        // integer slots because the wheel is held at an integer position while latched.
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
                        // No haptic: the select callback speaks NavSwitch for every arrival.
                        .pointerInput(idx, k) { detectTapGestures { go() } },
                )
            }
        }
    }
}

private fun arcRadius(widthPx: Float, hubCentreY: Float): Float =
    min(widthPx * ARC_W_FRAC, hubCentreY * ARC_H_FRAC)

