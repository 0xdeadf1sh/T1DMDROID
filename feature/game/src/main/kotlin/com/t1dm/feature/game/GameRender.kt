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

/**
 * Every colour the scene uses, resolved ONCE from the active palette.
 *
 * A draw that reached for `MaterialTheme` would be reading composition locals inside the draw phase;
 * more to the point, resolving eleven colours sixty times a second is work the frame budget does not
 * need to spend on a value that changes when the user picks a theme.
 */
class GameSkin(p: T1dmPalette) {
    val sky: Color = p.background
    val skyBand: Color = p.surface
    val ground: Color = p.surfaceVariant
    val groundDeep: Color = p.surface
    /** The terrain's top edge IS the BG trace, so it is stroked in the trace's own ink. */
    val trace: Color = p.primary
    val body: Color = p.inRange

    /** The shaded shade: the sill, the footwell recess, the engine bay. Mixed toward the surface rather
     *  than alpha-blended, because the car is drawn OVER terrain and a translucent shadow would take its
     *  colour from whatever the trace happens to be doing underneath. */
    val bodyDeep: Color = lerp(p.inRange, p.surface, 0.52f)
    val bodyEdge: Color = p.ink

    /** The windscreen. Translucent on purpose — glass is the one part that SHOULD take colour from
     *  behind it. */
    val glass: Color = p.secondary.copy(alpha = 0.42f)

    /** Louvres in the engine cover — a third step down, so the bay reads as vented rather than flat. */
    val bodyShadow: Color = lerp(p.inRange, p.surface, 0.78f)

    /** Exhaust smoke. Neutral rather than themed — a coloured plume reads as an effect, a grey one as
     *  exhaust — and drawn over the terrain at low alpha, so it takes its cast from what is behind it. */
    val smoke: Color = p.inkMuted

    /** The wing, and anything else that wants to read as bolted-on rather than moulded. */
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
}

/**
 * The car, hand-drawn: an offroad buggy on oversized wheels, authored in CAR-LOCAL METRES and drawn
 * through the canvas transform rather than re-projected point by point.
 *
 * Built once per tuning. The outline and the cage are `Path`s in local coordinates with y ALREADY
 * flipped for the y-down canvas, so a frame is `translate → rotate → scale → drawPath` and no geometry
 * is recomputed; stroke widths are given in local metres precisely so the same scale carries them.
 *
 * Nothing here is a sprite or an asset — the app draws all of its art, and a car is no exception.
 */
class CarArt(tuning: CarTuning) {
    internal val halfLen = tuning.chassisHalfLen
    internal val halfHeight = tuning.chassisHalfHeight
    internal val wheelRadius = tuning.wheelRadius

    /**
     * VERTICAL LAYOUT IS ANCHORED TO THE AXLE LINE, not to `chassis_half_height`.
     *
     * That constant is the COLLIDER's, and the solver sized it at 1.6 m against a 14 m half-length —
     * an 8.75:1 pancake, thinner than the wheels are tall, with no room to put anything in. Drawing to
     * it is what made the old car a featureless sliver. The tub is allowed to be deeper than the box
     * that collides, because the box is a physics detail the eye never sees and the cage already sits
     * outside it; what the eye needs is a body deep enough to read as a vehicle.
     *
     * Everything vertical is therefore a multiple of the WHEEL RADIUS offset from the nominal sagged
     * axle line, which keeps the car in proportion to the one part of it whose size is not negotiable.
     */
    private val axleY = halfHeight + tuning.suspensionRest * 0.40f
    private val undY = axleY - wheelRadius * 0.46f
    private val deckY = axleY - wheelRadius * 1.92f
    private val noseY = axleY - wheelRadius * 0.86f
    private val cageY = axleY - wheelRadius * 2.85f
    private val wingY = axleY - wheelRadius * 2.52f

    /** Central tub, tapering nose, and the engine bay behind the cockpit. Three fills rather than one
     *  shell: the solver fixes the wheels at ±0.90 of the half-length, so the car is ~28 m long whatever
     *  the art wants, and a single body across that span is an empty slab. A real buggy spends the length
     *  on STRUCTURE — a compact tub, exposed rails out to the suspension, a bumper hoop, a bay — so that
     *  is what this spends it on. */
    internal val tub = Path()
    internal val nose = Path()
    internal val bay = Path()

    /** The cockpit opening, as a recess rather than a hole: a true cut-out would show the TERRAIN
     *  through the car, since there is no sky behind it to show instead. It is left EMPTY — a drawn
     *  driver read as a blob at this scale and the machine alone is the better object. */
    internal val cockpit = Path()

    /** Exposed frame: the lower rail running the length of the car, the bay's upper rail, three braces
     *  and the front bumper hoop. One stroke, because they are all the same tube. */
    internal val frame = Path()

    internal val crease = Path()
    internal val louvres = Path()

    /** Rear wing: the aerofoil is filled, its two struts stroked. */
    internal val wing = Path()
    internal val wingStruts = Path()

    internal val cage = Path()
    internal val windscreen = Path()
    internal val exhaust = Path()

    /** The headlight throw, in two layers — a wide soft wedge and a narrow bright core. Car-local, so it
     *  sweeps with the chassis and points up a climb, which is most of what makes it read as a beam
     *  rather than a decal. */
    internal val beamWide = Path()
    internal val beamCore = Path()

    /** The exhaust, car-local (y-DOWN): where the pipe starts, where it ends, and the unit vector along
     *  it. The mouth is where smoke is emitted and the direction is which way it is thrown — both are
     *  taken from the same two points the pipe is DRAWN from, so the plume can never disagree with the
     *  part it comes out of. */
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
        // Angled DOWN as well as back, so the pipe has a direction worth rotating: a purely rearward
        // stack throws smoke along the ground however the car is pitched, and the whole point of taking
        // the direction from the chassis is that a nose-up climb should blow it downward.
        val pdx = exhaustX - pipeRootX
        val pdy = exhaustY - pipeRootY
        val plen = kotlin.math.hypot(pdx, pdy).coerceAtLeast(1e-4f)
        exhaustDirX = pdx / plen
        exhaustDirY = pdy / plen
        // y is NOT negated here any more — the layout above is already in canvas (y-down) sense, which
        // is what the per-frame transform expects. See `drawCar`.
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
        // Front bumper hoop.
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
        // The struts run PAST the deck line and are then buried by the bay, which is drawn over them.
        // Stopping them at the outline left a hairline of sky under each and the wing read as floating.
        wingStruts.moveTo(-l * 1.12f, wingY + r * 0.20f)
        wingStruts.lineTo(-l * 1.01f, deckY + r * 0.44f)
        wingStruts.moveTo(-l * 0.82f, wingY + r * 0.06f)
        wingStruts.lineTo(-l * 0.76f, deckY + r * 0.22f)

        // The cage is TUBE, so it is one stroke with round caps and the subpaths only exist where a real
        // one would be welded: main hoop, windscreen strut, spreader, rear brace.
        cage.moveTo(-l * 0.80f, deckY + r * 0.42f)
        cage.lineTo(-l * 0.60f, cageY)
        cage.lineTo(l * 0.06f, cageY + r * 0.10f)
        cage.lineTo(l * 0.40f, deckY + r * 0.36f)
        cage.moveTo(-l * 0.54f, cageY + r * 0.62f)
        cage.lineTo(l * 0.00f, cageY + r * 0.68f)
        cage.moveTo(-l * 0.60f, cageY)
        cage.lineTo(-l * 0.94f, deckY + r * 0.34f)

        // A narrow band along the A-pillar, not a slab: a screen seen edge-on. Drawn wide, it read as a
        // second wing.
        windscreen.moveTo(l * 0.05f, cageY + r * 0.14f)
        windscreen.lineTo(l * 0.38f, deckY + r * 0.34f)
        windscreen.lineTo(l * 0.27f, deckY + r * 0.32f)
        windscreen.lineTo(-l * 0.04f, cageY + r * 0.20f)
        windscreen.close()

        exhaust.moveTo(pipeRootX, pipeRootY)
        exhaust.lineTo(exhaustX, exhaustY)

        // THROW, in car half-lengths ahead of the lamp. Long: at the settled zoom the panel shows about
        // three car lengths, so a beam that stopped one length out barely left the bodywork.
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

/** Stroke widths in CAR-LOCAL METRES — the same `scale` that sizes the body sizes these. */
private const val EDGE_M = 0.06f
private const val CAGE_M = 0.10f
private const val SUSPENSION_M = 0.12f
private const val STRUT_M = 0.08f
private const val RAIL_M = 0.22f
private const val EXHAUST_M = 0.16f

/** Headlight opacity — CONSTANT, both layers. See `drawCar` for why it is not a function of throttle. */
private const val BEAM_WIDE_A = 0.10f
private const val BEAM_CORE_A = 0.20f

/** Puffs alive at once, and how long one lives. Together with the loop's emission rate these set how
 *  LONG the trail is: the phase advances faster under load, so a puff's whole life is spent nearer the
 *  car and the plume tightens into a stream rather than stretching. */
private const val PUFFS = 14
private const val PUFF_LIFE_S = 0.6f

/** A puff's radius in world metres new and spent, how fast it leaves the pipe, how fast it rises, and how
 *  much of the car's own velocity it is left behind by.
 *
 *  [PUFF_TRAIL] is deliberately well UNDER 1 even though 1 is the physical value. At the limiter a
 *  fully-weighted trail stretches the plume `100 × life` metres behind the car — 150 m at the old 1.5 s
 *  life, against a panel showing 90 — so every other term was invisible and the smoke read as a flat
 *  horizontal streak whatever the car was doing. That was the real reason the rotation did not show: not
 *  that it was missing, but that it was two orders of magnitude down on the drift. Shortening the life and
 *  discounting the trail puts the ejection back on comparable footing, so the plume visibly leaves the
 *  pipe in the direction the pipe is pointing. */
private const val PUFF_R0_M = 0.7f
private const val PUFF_R1_M = 3.4f
private const val PUFF_EJECT_MS = 24f
private const val PUFF_RISE_MS = 1.0f
private const val PUFF_TRAIL = 0.35f
private const val PUFF_MAX_A = 0.30f

/** Coils in the drawn spring, and how far it swings either side of the leg's axis in world metres.
 *  A FIXED coil count is the whole trick: the zigzag spans mount to hub, so as the leg compresses the
 *  same seven coils pack into less length and the travel becomes visible without anything animating it. */
private const val SPRING_COILS = 7
private const val SPRING_AMP_M = 0.55f

/** The BG trace's stroke width, matching GlucoseGraph's so the curve is identical in either mode. */
private const val TRACE_W = 2.2f

/**
 * The exhaust plume, drawn BEFORE the car so it sits behind it.
 *
 * STATELESS. There is no particle pool and nothing is stored between frames: puff `i` is simply the one
 * emitted `i` intervals ago, so its age falls out of [CarFrame.exhaustPhase] and its index. That is what
 * keeps this off the game thread's back — a real pool would either have to live in the frame buffer (it
 * is a flat scalar record by design) or be shared mutable state read across threads.
 *
 * Emitted in WORLD space and left behind: a puff's position is the exhaust mouth as it was `age` ago,
 * reconstructed from the car's current velocity, plus a rise. Smoke does not rotate with the chassis, so
 * none of this goes through the car's transform.
 */
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
    // Out of car-local (y-DOWN) into the world (y-up): negate y, then rotate. Applied to the mouth AND
    // to the pipe's direction, which is the whole fix — smoke used to be thrown along world -x whatever
    // the car was doing, so a nose-up climb still blew it flat along the ground.
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
    // Harder under load, so the plume visibly punches out of the pipe on a throttle stab.
    val eject = PUFF_EJECT_MS * (0.4f + 0.6f * load)
    for (i in 0 until PUFFS) {
        // (frac(phase) + i) / PUFFS — puffs march outward as the phase advances and recycle at the end.
        val u = ((f.exhaustPhase - floor(f.exhaustPhase)) + i) / PUFFS
        val age = u * PUFF_LIFE_S
        // Three terms: how far the car has moved on since this puff left (so it lags rather than being
        // towed), the throw ALONG THE PIPE, and buoyancy. The middle one is what carries the chassis's
        // attitude — see [PUFF_TRAIL] for why it has to be able to compete with the first.
        val px = mouthX - f.speedMs * PUFF_TRAIL * age + ejectX * eject * age
        val py = mouthY + ejectY * eject * age + PUFF_RISE_MS * age
        val rM = PUFF_R0_M + (PUFF_R1_M - PUFF_R0_M) * u
        // Fades faster than it grows, so the plume tapers instead of ending in a wall of big pale discs.
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

/**
 * Draw the whole vehicle for [f], TRUE SCALE. [world] is horizontal pixels per world metre, [floorPx]
 * the screen y of world y = 0, [camLeft] the world x at the screen's left edge.
 *
 * There is no longer a separate size scale. The car is drawn at exactly the scale the ground is, which
 * is only legible because [GameZoom] drives the horizontal scale to the car's own — see the note in the
 * body for what the constant-size marker cost and why no anchor choice could have saved it.
 */
fun DrawScope.drawCar(
    art: CarArt,
    f: CarFrame,
    skin: GameSkin,
    camLeft: Float,
    floorPx: Float,
    world: Float,
    /** Vertical pixels per world metre. Distinct from [world] because the panel keeps the graph's
     *  axes: value maps down the height independently of time across the width. Positioning the car
     *  with the HORIZONTAL scale sank it to the plot floor, under the curve, where it stuck. */
    worldY: Float = world,
) {
    // EVERY part from its OWN solved pose, through the world's own two scales.
    //
    // What this replaced was a single rigid transform anchored on the ground under the axle MIDPOINT,
    // sized at a constant on-screen [CAR_DRAW_DP] regardless of the window. That registers exactly one
    // point, and only that point: at a 5 h window the constant scale was ~11.8 px per world metre
    // against the ground's 1.15, so the drawn wheels sat 238 px apart while the real ones were 29 px
    // apart on the curve. Each tyre therefore landed 119 px from the only honest point, straddling
    // ~100 world metres of trace it had no relationship to — floating over a rise, sunk through a dip,
    // and worse the wider the window. No anchor choice fixes that, because a constant-size car and a
    // variable-scale ground cannot both be honoured.
    //
    // [GameZoom] is what makes this tractable: it drives the horizontal scale to the car's own, so true
    // scale IS legible scale and the marker is no longer needed. What anisotropy remains (the panel
    // still maps value down the height and time across the width independently) is carried BY the
    // projection instead of approximated inside it — which is also why the faked `drawAngle` is gone.
    // The paths are authored y-DOWN (see [CarArt]), so the reflection is already in them and the
    // transform is `diag(world, worldY) · R(−angle)`, scale applied after the rotation.
    val bodySx = (f.x - camLeft) * world
    val bodySy = floorPx - f.y * worldY
    val rearSx = (f.rearX - camLeft) * world
    val rearSy = floorPx - f.rearY * worldY
    val frontSx = (f.frontX - camLeft) * world
    val frontSy = floorPx - f.frontY * worldY

    // Order: legs, then the tub, then the WHEELS ON TOP.
    //
    // The body used to be painted last and sliced straight across the wheel discs. Raising it clear of
    // them would mean a taller ride height, which puts the centre of mass back up and brings the
    // wheelies with it — so the fix is the paint order, not the geometry: wheels over the tub read as
    // wheels in arches, and the ride height stays low where the handling needs it.
    drawSuspension(art, bodySx, bodySy, f.angle, -art.mountX, rearSx, rearSy, skin, world, worldY)
    drawSuspension(art, bodySx, bodySy, f.angle, art.mountX, frontSx, frontSy, skin, world, worldY)

    val canvas = drawContext.canvas
    canvas.save()
    canvas.translate(bodySx, bodySy)
    canvas.scale(world, worldY)
    // World angles are counter-clockwise-positive; screen rotation is clockwise-positive.
    canvas.rotate(-f.angle * DEG_PER_RAD)
    // Back to front, so each layer buries the one behind it and nothing needs a depth test: the beam and
    // the frame, then bolted-on parts, then the bodywork, then what sits inside it, then what sits on top.
    //
    // The beam goes down FIRST so the nose it emanates from is drawn over its root — otherwise the wedge
    // has a visible hard edge across the bodywork.
    //
    // FIXED brightness. It was scaled by the applied throttle, which read as the lamps surging with the
    // pedal — a headlight is either on or it is not, and tying it to a control made the whole front of the
    // car pulse under acceleration. The bulb is likewise always lit rather than tinted by throttle.
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

    // AFTER the restore: the wheels are placed in screen space already and must not inherit the tub's
    // translate/rotate/scale.
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
    // Rotate the local mount into world, then project. Written out rather than pushed through the
    // canvas transform because the far end of the leg is a WHEEL position, which the transform does
    // not apply to — the two ends live in different frames. The leg is now the ONLY thing that spans
    // them, so it is also what visibly carries the suspension travel.
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

    // The coilover over the arm. Built with drawLine in a loop rather than as a Path, because a Path
    // would be a fresh allocation per wheel per frame in the one place this file is careful not to
    // allocate at all.
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
    // An ELLIPSE, not a circle, and it has to be: the tyre's bottom is what the eye checks against the
    // curve, so its vertical radius must ride the same vertical scale the ground does. Drawing it round
    // at the horizontal scale would sit the contact point `r · (worldY − world)` off the trace — the
    // exact class of error this whole path exists to remove. Once [GameZoom] has settled the two scales
    // agree to about a tenth and it reads as round anyway.
    val rx = art.wheelRadius * world
    val ry = art.wheelRadius * worldY
    fun oval(color: androidx.compose.ui.graphics.Color, k: Float) = drawOval(
        color,
        topLeft = Offset(cx - rx * k, cy - ry * k),
        size = androidx.compose.ui.geometry.Size(2f * rx * k, 2f * ry * k),
    )
    oval(skin.tyre, 1f)
    oval(skin.rim, 0.55f)
    // Beadlock bolts around the rim's lip. Eight is enough to read as a ring of fasteners at the size
    // this is drawn and cheap enough not to think about.
    for (k in 0 until BOLTS) {
        val t = angle + k * (TAU / BOLTS)
        drawOval(
            skin.hub,
            topLeft = Offset(cx + cos(t) * rx * 0.68f - rx * 0.05f, cy + sin(t) * ry * 0.68f - ry * 0.05f),
            size = androidx.compose.ui.geometry.Size(rx * 0.10f, ry * 0.10f),
        )
    }
    oval(skin.hub, 0.17f)

    // Spokes and tread are laid out with plain trigonometry rather than a canvas rotation: it is the
    // same arithmetic, and it keeps the transform stack untouched inside the frame path.
    //
    // NOT negated, unlike the chassis. `CarState.angle` is a right-hand-rule world angle and so flips
    // for the y-down canvas; wheel spin is not — the solver defines it forward-positive
    // (`slip = spin * r - v_t`, game.rs), which on a y-down canvas is already the visual direction of
    // travel. Negating it too spun the wheels backwards while the car drove forwards.
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
    // Chunky offroad tread: alternate blocks reach further in, so the tyre reads as lugged rather than
    // as a dotted ring. The contact colour is load-bearing information, not decoration — it is the only
    // place the solver's per-wheel contact flag is visible.
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

/**
 * The world behind the car: sky, the annotation layer, the terrain fill, and the two end markers.
 *
 * Order is load-bearing and is `:ui:game`'s own contract: paint goes down BEFORE the ground, so a
 * stroke drawn under the trace is buried by the terrain and one drawn above it reads as sky scenery —
 * occlusion for free, with no depth test and no second pass.
 */
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
    /** Vertical pixels per world metre — see [GameTrack.appendGroundLine]. The panel keeps the graph's
     *  own axes, so time and value scale independently. */
    pxPerWorldY: Float = pxPerWorld,
    /** No opaque sky: game mode is a mode of the BG panel, and the panel's background is the
     *  per-theme backdrop painted behind the whole app. Filling here hid it. */
    fillSky: Boolean = true,
) {
    if (fillSky) drawRect(skin.sky)
    drawWorldPaint(paint, camLeft, camWidth, pxPerWorld, floorPx, paintPath, chalk, pxPerWorldY)

    // The terrain is STROKED, not filled. In drive mode the panel is still the BG panel, so the curve
    // must read exactly as it does with the game off — one line, the same weight and colour — rather
    // than becoming the lid of a coloured mass. Nothing is painted below it.
    track.appendGroundLine(groundPath, camLeft, camWidth, pxPerWorld, floorPx, pxPerWorldY)
    drawPath(groundPath, skin.trace, style = Stroke(width = TRACE_W, cap = StrokeCap.Round))

    drawEndMarker(0f, skin.marker, camLeft, camWidth, pxPerWorld, floorPx, track)
    drawEndMarker(track.length, skin.finish, camLeft, camWidth, pxPerWorld, floorPx, track)
}

/** A plain vertical post at an end of the heightfield: the start line, and the present moment. */
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
