package com.t1dm.feature.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.t1dm.core.design.T1dmPalette
import com.t1dm.core.model.CarTuning
import com.t1dm.ui.game.GameTrack
import com.t1dm.ui.game.WorldPaint
import com.t1dm.ui.game.appendGroundLine
import com.t1dm.ui.game.drawWorldPaint
import com.t1dm.ui.graph.ChalkPens
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Resolved ONCE from the palette; reaching for MaterialTheme would read locals in draw phase. */
class GameSkin(p: T1dmPalette) {
    val sky: Color = p.background
    val skyBand: Color = p.surface
    val ground: Color = p.surfaceVariant
    val groundDeep: Color = p.surface
    /** The terrain's top edge IS the BG trace, so it is stroked in the trace's own ink. */
    val trace: Color = p.primary
    val body: Color = p.inRange

    /** Mixed toward the surface, not alpha-blended: the car is drawn OVER terrain. */
    val bodyDeep: Color = lerp(p.inRange, p.surface, 0.52f)
    val bodyEdge: Color = p.ink

    /** Translucent on purpose: glass should take its colour from behind. */
    val glass: Color = p.secondary.copy(alpha = 0.42f)

    /** Louvres in the engine cover: a third step down. */
    val bodyShadow: Color = lerp(p.inRange, p.surface, 0.78f)

    /** Neutral rather than themed, drawn over the terrain at low alpha. */
    val smoke: Color = p.inkMuted

    val accent: Color = p.secondary
    val cage: Color = p.ink
    val tyre: Color = p.ink.copy(alpha = 0.92f)
    val rim: Color = p.surfaceVariant
    val hub: Color = p.inkMuted
    val lamp: Color = p.high
    val marker: Color = p.inkMuted
    val finish: Color = p.urgentHigh
    val pedalIdle: Color = p.ink.copy(alpha = 0.10f)
    val pedalDown: Color = p.primary.copy(alpha = 0.26f)
    val pedalInk: Color = p.ink.copy(alpha = 0.55f)

    /** A golf ball is white in every theme: whichever neutral the palette made the light one. */
    val ball: Color = if (p.dark) p.ink else p.surface

    /** Mixed toward the dark neutral, never alpha: the turf behind must not tint the ball. */
    val ballEdge: Color = lerp(ball, if (p.dark) p.surface else p.ink, 0.45f)
    val ballShade: Color = lerp(ball, if (p.dark) p.surface else p.ink, 0.20f)

    /** The golfer: ink over both sky and turf, the club a step back from it. */
    val figure: Color = p.ink
    val club: Color = p.inkMuted

    /** Kit and skin, from the palette rather than literal: the figure must read in every theme. */
    val shirt: Color = p.secondary
    val trousers: Color = lerp(p.ink, p.surface, 0.30f)
    val cap: Color = p.primary
    val flesh: Color = lerp(p.ink, p.surface, if (p.dark) 0.55f else 0.40f)
}

/** Built once per tuning, CAR-LOCAL METRES, y flipped; a frame is translate/rotate/scale. */
class CarArt(tuning: CarTuning) {
    internal val halfLen = tuning.chassisHalfLen
    internal val halfHeight = tuning.chassisHalfHeight
    internal val wheelRadius = tuning.wheelRadius

    /** Vertical layout anchored to the AXLE LINE, not chassis_half_height (COLLIDER's, 1.6m). */
    private val axleY = halfHeight + tuning.suspensionRest * 0.40f
    private val undY = axleY - wheelRadius * 0.46f
    private val deckY = axleY - wheelRadius * 1.92f
    private val noseY = axleY - wheelRadius * 0.86f
    private val cageY = axleY - wheelRadius * 2.85f
    private val wingY = axleY - wheelRadius * 2.52f

    /** Three fills not one shell: wheels fixed at ±0.90 half-length, ~28m long, one is a slab. */
    internal val tub = Path()
    internal val nose = Path()
    internal val bay = Path()

    /** A recess, not a hole: a true cut-out would show TERRAIN through the car. Left empty. */
    internal val cockpit = Path()

    /** One stroke, because they are all the same tube. */
    internal val frame = Path()

    internal val crease = Path()
    internal val louvres = Path()

    internal val wing = Path()
    internal val wingStruts = Path()

    internal val cage = Path()
    internal val windscreen = Path()
    internal val exhaust = Path()

    /** A wide soft wedge and a narrow bright core; car-local, beam sweeps with the chassis. */
    internal val beamWide = Path()
    internal val beamCore = Path()

    /** Car-local (y-DOWN); mouth/direction from the same two points the pipe is drawn from. */
    private val pipeRootX = -halfLen * 0.90f
    private val pipeRootY = axleY - wheelRadius * 0.46f - wheelRadius * 0.40f
    internal val exhaustX = -halfLen * 1.16f
    internal val exhaustY = axleY - wheelRadius * 0.46f - wheelRadius * 0.16f
    internal val exhaustDirX: Float
    internal val exhaustDirY: Float

    /** Suspension mounts, car-local, y flipped. */
    internal val mountX = halfLen * 0.72f
    internal val mountY = -(undY - wheelRadius * 0.10f)

    internal val lampX = halfLen * 0.95f
    internal val lampY = noseY + wheelRadius * 0.06f
    internal val lampR = wheelRadius * 0.13f

    init {
        val l = halfLen
        val r = wheelRadius
        // Angled down as well as back, so a nose-up climb blows the smoke downward.
        val pdx = exhaustX - pipeRootX
        val pdy = exhaustY - pipeRootY
        val plen = kotlin.math.hypot(pdx, pdy).coerceAtLeast(1e-4f)
        exhaustDirX = pdx / plen
        exhaustDirY = pdy / plen
        // The layout above is already in canvas (y-down) sense the per-frame transform expects.
        val tubR = -l * 0.46f
        val tubF = l * 0.34f

        tub.moveTo(tubR, undY)
        tub.lineTo(tubF, undY)
        tub.lineTo(tubF + l * 0.06f, deckY + r * 0.30f)
        tub.lineTo(l * 0.02f, deckY + r * 0.06f)
        tub.lineTo(-l * 0.34f, deckY)
        tub.lineTo(tubR - l * 0.04f, deckY + r * 0.26f)
        tub.close()

        nose.moveTo(tubF, undY - r * 0.06f)
        nose.lineTo(l * 0.86f, undY - r * 0.16f)
        nose.lineTo(l * 1.05f, undY - r * 0.38f)
        nose.lineTo(l * 1.02f, noseY - r * 0.02f)
        nose.lineTo(l * 0.62f, noseY - r * 0.10f)
        nose.lineTo(tubF + l * 0.04f, deckY + r * 0.44f)
        nose.close()

        bay.moveTo(tubR, deckY + r * 0.24f)
        bay.lineTo(-l * 0.52f, deckY - r * 0.04f)
        bay.lineTo(-l * 0.98f, deckY + r * 0.30f)
        bay.lineTo(-l * 1.02f, undY - r * 0.24f)
        bay.lineTo(tubR, undY - r * 0.10f)
        bay.close()

        cockpit.moveTo(-l * 0.40f, deckY + r * 0.10f)
        cockpit.lineTo(-l * 0.02f, deckY + r * 0.22f)
        cockpit.lineTo(-l * 0.04f, deckY + r * 0.78f)
        cockpit.lineTo(-l * 0.42f, deckY + r * 0.66f)
        cockpit.close()

        frame.moveTo(-l * 1.02f, undY - r * 0.04f)
        frame.lineTo(l * 1.02f, undY - r * 0.30f)
        frame.moveTo(-l * 0.96f, deckY + r * 0.34f)
        frame.lineTo(-l * 0.50f, deckY + r * 0.06f)
        frame.moveTo(-l * 0.86f, undY - r * 0.14f)
        frame.lineTo(-l * 0.62f, deckY + r * 0.18f)
        frame.moveTo(-l * 1.00f, undY - r * 0.16f)
        frame.lineTo(-l * 0.90f, deckY + r * 0.36f)
        frame.moveTo(l * 0.52f, undY - r * 0.22f)
        frame.lineTo(l * 0.72f, noseY - r * 0.06f)
        frame.moveTo(l * 0.98f, undY - r * 0.34f)
        frame.lineTo(l * 1.14f, undY - r * 0.46f)
        frame.lineTo(l * 1.16f, noseY + r * 0.16f)
        frame.lineTo(l * 1.02f, noseY)

        crease.moveTo(tubR, undY - r * 0.44f)
        crease.lineTo(l * 0.10f, undY - r * 0.50f)
        crease.lineTo(l * 0.80f, undY - r * 0.40f)

        for (k in 0 until 4) {
            louvres.moveTo(-l * 0.92f + k * l * 0.055f, deckY + r * 0.36f)
            louvres.lineTo(-l * 0.88f + k * l * 0.055f, undY - r * 0.28f)
        }

        wing.moveTo(-l * 1.20f, wingY + r * 0.10f)
        wing.lineTo(-l * 0.76f, wingY - r * 0.06f)
        wing.lineTo(-l * 0.76f, wingY + r * 0.14f)
        wing.lineTo(-l * 1.20f, wingY + r * 0.30f)
        wing.close()
        // Run PAST the deck line, buried by the bay; stopping at the outline leaves a hairline.
        wingStruts.moveTo(-l * 1.12f, wingY + r * 0.20f)
        wingStruts.lineTo(-l * 1.01f, deckY + r * 0.44f)
        wingStruts.moveTo(-l * 0.82f, wingY + r * 0.06f)
        wingStruts.lineTo(-l * 0.76f, deckY + r * 0.22f)

        // One stroke with round caps; subpaths only where a real cage would be welded.
        cage.moveTo(-l * 0.80f, deckY + r * 0.42f)
        cage.lineTo(-l * 0.60f, cageY)
        cage.lineTo(l * 0.06f, cageY + r * 0.10f)
        cage.lineTo(l * 0.40f, deckY + r * 0.36f)
        cage.moveTo(-l * 0.54f, cageY + r * 0.62f)
        cage.lineTo(l * 0.00f, cageY + r * 0.68f)
        cage.moveTo(-l * 0.60f, cageY)
        cage.lineTo(-l * 0.94f, deckY + r * 0.34f)

        // A narrow band along the A-pillar, edge-on; drawn wide reads as a second wing.
        windscreen.moveTo(l * 0.05f, cageY + r * 0.14f)
        windscreen.lineTo(l * 0.38f, deckY + r * 0.34f)
        windscreen.lineTo(l * 0.27f, deckY + r * 0.32f)
        windscreen.lineTo(-l * 0.04f, cageY + r * 0.20f)
        windscreen.close()

        exhaust.moveTo(pipeRootX, pipeRootY)
        exhaust.lineTo(exhaustX, exhaustY)

        // Throw in car half-lengths; long, since settled zoom shows about three car lengths.
        beamWide.moveTo(lampX, lampY - r * 0.16f)
        beamWide.lineTo(lampX + l * 2.60f, lampY - r * 1.85f)
        beamWide.lineTo(lampX + l * 2.60f, lampY + r * 2.05f)
        beamWide.lineTo(lampX, lampY + r * 0.20f)
        beamWide.close()

        beamCore.moveTo(lampX, lampY - r * 0.09f)
        beamCore.lineTo(lampX + l * 1.95f, lampY - r * 0.78f)
        beamCore.lineTo(lampX + l * 1.95f, lampY + r * 0.92f)
        beamCore.lineTo(lampX, lampY + r * 0.13f)
        beamCore.close()
    }
}

private const val TAU = (2.0 * Math.PI).toFloat()
private const val DEG_PER_RAD = (180.0 / Math.PI).toFloat()
private const val SPOKES = 6
private const val TREADS = 16
private const val BOLTS = 8

/** Stroke widths in CAR-LOCAL METRES; the same `scale` that sizes the body sizes these. */
private const val EDGE_M = 0.06f
private const val CAGE_M = 0.10f
private const val SUSPENSION_M = 0.12f
private const val STRUT_M = 0.08f
private const val RAIL_M = 0.22f
private const val EXHAUST_M = 0.16f

/** Constant, both layers: deliberately not a function of throttle. */
private const val BEAM_WIDE_A = 0.10f
private const val BEAM_CORE_A = 0.20f

/** Puffs alive at once, and how long one lives. */
private const val PUFFS = 14
private const val PUFF_LIFE_S = 0.6f

/** Puff radius new/spent, eject/rise speed, trail fraction; PUFF_TRAIL well under physical 1. */
private const val PUFF_R0_M = 0.7f
private const val PUFF_R1_M = 3.4f
private const val PUFF_EJECT_MS = 24f
private const val PUFF_RISE_MS = 1.0f
private const val PUFF_TRAIL = 0.35f
private const val PUFF_MAX_A = 0.30f

/** Coils and swing either side of the leg's axis; a FIXED count packs tighter as it compresses. */
private const val SPRING_COILS = 7
private const val SPRING_AMP_M = 0.55f

/** Matches GlucoseGraph's, so the curve is identical in either mode. */
private const val TRACE_W = 2.2f

/** Drawn BEFORE the car. STATELESS: puff i's age falls out of exhaustPhase and index, no state. */
internal fun DrawScope.drawSmoke(
    art: CarArt,
    f: CarFrame,
    skin: GameSkin,
    camLeft: Float,
    floorPx: Float,
    world: Float,
    worldY: Float,
) {
    val load = f.throttleApplied.coerceIn(0f, 1f)
    if (f.run != 0 || load <= 0.02f) return
    // Car-local (y-DOWN) to world (y-up): negate y then rotate; mouth and pipe direction.
    val ca = cos(f.angle)
    val sa = sin(f.angle)
    val ex = art.exhaustX
    val ey = -art.exhaustY
    val mouthX = f.x + ca * ex - sa * ey
    val mouthY = f.y + sa * ex + ca * ey
    val dx = art.exhaustDirX
    val dy = -art.exhaustDirY
    val ejectX = ca * dx - sa * dy
    val ejectY = sa * dx + ca * dy
    val eject = PUFF_EJECT_MS * (0.4f + 0.6f * load)
    for (i in 0 until PUFFS) {
        // Puffs march outward as the phase advances, and recycle at the end.
        val u = ((f.exhaustPhase - floor(f.exhaustPhase)) + i) / PUFFS
        val age = u * PUFF_LIFE_S
        // Three terms: car's motion since the puff left, the throw ALONG THE PIPE, and buoyancy.
        val px = mouthX - f.speedMs * PUFF_TRAIL * age + ejectX * eject * age
        val py = mouthY + ejectY * eject * age + PUFF_RISE_MS * age
        val rM = PUFF_R0_M + (PUFF_R1_M - PUFF_R0_M) * u
        // Fades faster than it grows, so the plume tapers.
        val fade = (1f - u) * (1f - u)
        val alpha = PUFF_MAX_A * fade * (0.35f + 0.65f * load)
        if (alpha <= 0.004f) continue
        val cx = (px - camLeft) * world
        val cy = floorPx - py * worldY
        val rx = rM * world
        val ry = rM * worldY
        drawOval(
            skin.smoke.copy(alpha = alpha),
            topLeft = Offset(cx - rx, cy - ry),
            size = androidx.compose.ui.geometry.Size(2f * rx, 2f * ry),
        )
    }
}

/** TRUE SCALE: legible because GameZoom drives horizontal scale to the car's own. */
fun DrawScope.drawCar(
    art: CarArt,
    f: CarFrame,
    skin: GameSkin,
    camLeft: Float,
    floorPx: Float,
    world: Float,
    /** Vertical px per world metre, distinct from world; value maps height independent of time. */
    worldY: Float = world,
) {
    // Every part from its own solved pose; paths authored y-DOWN, transform is diag*R(-angle).
    val bodySx = (f.x - camLeft) * world
    val bodySy = floorPx - f.y * worldY
    val rearSx = (f.rearX - camLeft) * world
    val rearSy = floorPx - f.rearY * worldY
    val frontSx = (f.frontX - camLeft) * world
    val frontSy = floorPx - f.frontY * worldY

    // Order: legs, tub, then WHEELS ON TOP; wheels over the tub read as wheels in arches.
    drawSuspension(art, bodySx, bodySy, f.angle, -art.mountX, rearSx, rearSy, skin, world, worldY)
    drawSuspension(art, bodySx, bodySy, f.angle, art.mountX, frontSx, frontSy, skin, world, worldY)

    val canvas = drawContext.canvas
    canvas.save()
    canvas.translate(bodySx, bodySy)
    canvas.scale(world, worldY)
    // World angles are counter-clockwise-positive; screen rotation is clockwise-positive.
    canvas.rotate(-f.angle * DEG_PER_RAD)
    // Back to front, no depth test; beam goes down FIRST so the nose is drawn over its root.
    drawPath(art.beamWide, skin.lamp.copy(alpha = BEAM_WIDE_A), style = Fill)
    drawPath(art.beamCore, skin.lamp.copy(alpha = BEAM_CORE_A), style = Fill)
    drawPath(art.frame, skin.hub, style = Stroke(width = RAIL_M, cap = StrokeCap.Round))
    drawPath(art.wingStruts, skin.hub, style = Stroke(width = STRUT_M, cap = StrokeCap.Round))
    drawPath(art.wing, skin.accent, style = Fill)
    drawPath(art.exhaust, skin.hub, style = Stroke(width = EXHAUST_M, cap = StrokeCap.Round))
    drawPath(art.bay, skin.bodyDeep, style = Fill)
    drawPath(art.louvres, skin.bodyShadow, style = Stroke(width = EDGE_M * 1.4f))
    drawPath(art.nose, skin.body, style = Fill)
    drawPath(art.nose, skin.bodyEdge, style = Stroke(width = EDGE_M))
    drawPath(art.tub, skin.body, style = Fill)
    drawPath(art.cockpit, skin.bodyDeep, style = Fill)
    drawPath(art.crease, skin.bodyDeep, style = Stroke(width = EDGE_M))
    drawPath(art.tub, skin.bodyEdge, style = Stroke(width = EDGE_M))
    drawPath(art.cage, skin.cage, style = Stroke(width = CAGE_M, cap = StrokeCap.Round))
    drawPath(art.windscreen, skin.glass, style = Fill)
    drawCircle(skin.lamp, art.lampR, Offset(art.lampX, art.lampY))
    canvas.restore()

    // After the restore: wheels are already in screen space, must not inherit the tub's transform.
    drawWheel(art, rearSx, rearSy, f.rearAngle, f.rearContact, skin, world, worldY)
    drawWheel(art, frontSx, frontSy, f.frontAngle, f.frontContact, skin, world, worldY)
}

private fun DrawScope.drawSuspension(
    art: CarArt,
    bodySx: Float,
    bodySy: Float,
    angle: Float,
    localX: Float,
    hubSx: Float,
    hubSy: Float,
    skin: GameSkin,
    world: Float,
    worldY: Float,
) {
    // Written out, not via canvas transform: the leg's far end is a WHEEL in a different frame.
    val ca = cos(angle)
    val sa = sin(angle)
    val wx = ca * localX - sa * art.mountY
    val wy = sa * localX + ca * art.mountY
    val mx = bodySx + wx * world
    val my = bodySy - wy * worldY
    drawLine(
        skin.hub,
        Offset(mx, my),
        Offset(hubSx, hubSy),
        strokeWidth = SUSPENSION_M * worldY,
        cap = StrokeCap.Round,
    )

    // drawLine in a loop rather than a Path: a Path would allocate per wheel per frame.
    val dx = hubSx - mx
    val dy = hubSy - my
    val len = kotlin.math.hypot(dx, dy)
    if (len < 1e-3f) return
    val ux = dx / len
    val uy = dy / len
    val amp = SPRING_AMP_M * worldY
    var px = mx
    var py = my
    for (k in 1..SPRING_COILS) {
        val t = k.toFloat() / SPRING_COILS
        val swing = if (k == SPRING_COILS) 0f else if (k % 2 == 0) amp else -amp
        val nx = mx + ux * len * t - uy * swing
        val ny = my + uy * len * t + ux * swing
        drawLine(skin.rim, Offset(px, py), Offset(nx, ny), strokeWidth = EDGE_M * worldY * 1.4f)
        px = nx
        py = ny
    }
}

private fun DrawScope.drawWheel(
    art: CarArt,
    cx: Float,
    cy: Float,
    angle: Float,
    contact: Boolean,
    skin: GameSkin,
    world: Float,
    worldY: Float,
) {
    // An ELLIPSE not a circle: tyre bottom checks the curve, vertical radius rides ground scale.
    val rx = art.wheelRadius * world
    val ry = art.wheelRadius * worldY
    fun oval(color: androidx.compose.ui.graphics.Color, k: Float) = drawOval(
        color,
        topLeft = Offset(cx - rx * k, cy - ry * k),
        size = androidx.compose.ui.geometry.Size(2f * rx * k, 2f * ry * k),
    )
    oval(skin.tyre, 1f)
    oval(skin.rim, 0.55f)
    for (k in 0 until BOLTS) {
        val t = angle + k * (TAU / BOLTS)
        drawOval(
            skin.hub,
            topLeft = Offset(cx + cos(t) * rx * 0.68f - rx * 0.05f, cy + sin(t) * ry * 0.68f - ry * 0.05f),
            size = androidx.compose.ui.geometry.Size(rx * 0.10f, ry * 0.10f),
        )
    }
    oval(skin.hub, 0.17f)

    // NOT negated unlike chassis: solver's forward-positive spin is already travel direction here.
    val a = angle
    for (k in 0 until SPOKES) {
        val t = a + k * (TAU / SPOKES)
        val c = cos(t)
        val d = sin(t)
        drawLine(
            skin.hub,
            Offset(cx + c * rx * 0.18f, cy + d * ry * 0.18f),
            Offset(cx + c * rx * 0.50f, cy + d * ry * 0.50f),
            strokeWidth = ry * 0.09f,
            cap = StrokeCap.Round,
        )
    }
    // The contact colour is information: only place the solver's per-wheel flag is visible.
    val tread = if (contact) skin.trace else skin.rim
    for (k in 0 until TREADS) {
        val t = a + k * (TAU / TREADS)
        val c = cos(t)
        val d = sin(t)
        val inner = if (k % 2 == 0) 0.74f else 0.83f
        drawLine(
            tread,
            Offset(cx + c * rx * inner, cy + d * ry * inner),
            Offset(cx + c * rx * 0.99f, cy + d * ry * 0.99f),
            strokeWidth = ry * 0.13f,
            cap = StrokeCap.Round,
        )
    }
}

/** Order is load-bearing (:ui:game contract): paint goes down BEFORE the ground. */
fun DrawScope.drawGameWorld(
    track: GameTrack,
    paint: WorldPaint,
    skin: GameSkin,
    camLeft: Float,
    camWidth: Float,
    pxPerWorld: Float,
    floorPx: Float,
    groundPath: Path,
    paintPath: Path,
    chalk: ChalkPens,
    /** Vertical px per world metre — see appendGroundLine; time/value scale independently. */
    pxPerWorldY: Float = pxPerWorld,
    /** Panel's background is the per-theme backdrop behind the whole app; filling hides it. */
    fillSky: Boolean = true,
) {
    if (fillSky) drawRect(skin.sky)
    drawWorldPaint(paint, camLeft, camWidth, pxPerWorld, floorPx, paintPath, chalk, pxPerWorldY)

    // STROKED not filled: the curve must read exactly as with the game off; nothing below it.
    track.appendGroundLine(groundPath, camLeft, camWidth, pxPerWorld, floorPx, pxPerWorldY)
    drawPath(groundPath, skin.trace, style = Stroke(width = TRACE_W, cap = StrokeCap.Round))

    drawEndMarker(0f, skin.marker, camLeft, camWidth, pxPerWorld, floorPx, track)
    drawEndMarker(track.length, skin.finish, camLeft, camWidth, pxPerWorld, floorPx, track)
}

/** The start line, and the present moment. */
private fun DrawScope.drawEndMarker(
    worldX: Float,
    color: Color,
    camLeft: Float,
    camWidth: Float,
    pxPerWorld: Float,
    floorPx: Float,
    track: GameTrack,
) {
    if (worldX < camLeft - 2f || worldX > camLeft + camWidth + 2f) return
    val ground = track.groundAt(worldX)
    if (!ground.isFinite()) return
    val sx = (worldX - camLeft) * pxPerWorld
    val baseY = floorPx - ground * pxPerWorld
    drawLine(
        color,
        Offset(sx, baseY),
        Offset(sx, baseY - MARKER_HEIGHT_M * pxPerWorld),
        strokeWidth = pxPerWorld * 0.18f,
    )
}

private const val MARKER_HEIGHT_M = 6f
