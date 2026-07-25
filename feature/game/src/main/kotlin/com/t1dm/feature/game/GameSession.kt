package com.t1dm.feature.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.t1dm.core.common.GameWorld
import com.t1dm.core.model.CarState
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.RunState
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.game.GameTrack
import com.t1dm.ui.game.TrackTrace
import com.t1dm.ui.game.WorldPaint
import com.t1dm.ui.game.buildGameTrack
import com.t1dm.ui.game.buildWorldPaint
import com.t1dm.ui.graph.buildGraphFrame
import com.t1dm.ui.graph.buildPaintFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/**
 * `GraphFrame`'s decimation cap, deliberately lifted out of reach.
 *
 * The panel's default (6 000) is a DRAWING budget; as terrain it would be a correctness bug, because
 * min/max envelope decimation emits a bucket's extremes in time order and a bucket whose maximum
 * precedes its minimum becomes a near-vertical cliff whose position is a function of bucket boundaries
 * rather than of the data (`TrackTrace.of`). A million readings is ~9.5 years of the 5-min grid, so
 * this never fires; level-of-detail belongs to the terrain grid, which `buildGameTrack` already owns.
 */
private const val TRACK_MAX_POINTS = 1_000_000

/** The world, loaded. Immutable and shared by the loop and the draw. */
class GameScene(
    val track: GameTrack,
    val paint: WorldPaint,
    /** Carried so the panel's furniture can be redrawn around the game's own viewport: the axis is
     *  labelled in the SAME unit and timezone the trace was cut in, not the live setting, which the
     *  user may change mid-run without the ground under the car changing with it. */
    val unit: UnitSpace,
    val tzOffsetMin: Int,
)

/**
 * Cut the track out of the record. Pure CPU, forced off the caller's thread — a year of history is a
 * few hundred thousand samples through three transforms and must never touch main.
 *
 * [readings] are the run's window: the chosen start day's midnight through to the newest reading. The
 * unit, the axis span and the Kovatchev transform are the panel's own, so the ground is the line the
 * user was looking at when they pressed Drive.
 */
suspend fun loadGameScene(
    readings: List<CgmReading>,
    strokes: List<PaintStroke>,
    unit: UnitSpace,
    kovatchevF: ((Double) -> Double)?,
    rangeMinMgdl: Int,
    rangeMaxMgdl: Int,
): GameScene = withContext(Dispatchers.Default) {
    val frame = buildGraphFrame(readings, unit, maxPoints = TRACK_MAX_POINTS, kovatchevF = kovatchevF)
    val track = buildGameTrack(TrackTrace.of(frame), rangeMinMgdl, rangeMaxMgdl, kovatchevF)
    val paint = if (strokes.isEmpty() || !track.isPlayable) {
        WorldPaint.EMPTY
    } else {
        buildWorldPaint(buildPaintFrame(strokes), track)
    }
    GameScene(track, paint, unit, frame.tzOffsetMin)
}

/** How the run ended, plus the numbers the strip carries. Rebuilt at [HUD_PERIOD_NS], never at frame
 *  rate: a clock at minute resolution and a distance in whole metres have nothing to say sixty times
 *  a second, and this is the ONE thing on the screen whose change recomposes anything. */
data class GameHud(
    val clock: String,
    val distanceM: Float,
    val readingAgeMin: Long?,
    val run: RunState,
    val hold: GameHold?,
) {
    companion object {
        val EMPTY = GameHud("", 0f, null, RunState.Running, null)
    }
}

/** The HUD's snapshot cell, kept in its own holder so the strip is the only composable that reads it
 *  — a `var` in the screen body would put every 250 ms update through the whole tree, Canvas included. */
class HudState {
    var value by mutableStateOf(GameHud.EMPTY)
}

/** The newest reading's stamp, republished out of composition into plain memory. The loop needs the
 *  reading age and must not perform a snapshot read to get it. */
class LiveReadingRef {
    @Volatile
    var tsMs: Long? = null
}

/** 4 Hz. Fast enough that the clock never looks stuck (the car covers a quarter-hour of history a
 *  second at speed), slow enough to be free. */
private const val HUD_PERIOD_NS = 250_000_000L

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm · d MMM")

/** Puffs a second at idle and at full throttle. The RATE is what makes the trail read as effort — a
 *  denser stream under load — while the draw scales each puff's size and opacity by the same throttle,
 *  so the two cues agree. */
private const val SMOKE_IDLE_HZ = 4f

private const val SMOKE_HZ = 26f

/** The phase is only ever used modulo the puff count, so wrapping it costs nothing and keeps it out of
 *  float's coarse range on a long run. A multiple of any plausible puff count. */
private const val SMOKE_PHASE_WRAP = 1_048_576f

/**
 * The frame loop. Runs on `T1dmDispatchers.game` — its own thread, never `t1dm-inference`, never a
 * `default` worker.
 *
 * **Where each piece executes, because that is the whole point.** `withFrameNanos` registers a
 * Choreographer callback whose body runs on MAIN; the body here is `{ it }` — it reads the frame stamp
 * and returns, and the continuation resumes on the game thread because that is this coroutine's
 * dispatcher. So of the per-frame work, the SIMULATION, the camera and the publish are all off main;
 * what unavoidably remains on it is the callback returning a Long, the snapshot apply-notification the
 * commit schedules, and the draw record itself. That matters because `CgmScanService.lifecycleScope`
 * IS `Dispatchers.Main.immediate` and the CGM reading relay runs on it, feeding both the §3.6-A alarm
 * engine and the forecast driver — so what is left on main has to stay small, and a frame body doing
 * the physics inline would not be.
 *
 * Nothing in this loop reads or writes Compose snapshot state except [GameFrameBus.commit] (one
 * `Long` increment, subscribed only by the draw lambda) and the 4 Hz HUD cell.
 *
 * [feel] is the sensory layer, and it is narrow on purpose ([FeelSink]): the loop may describe a frame
 * or say that there is not one, and nothing else. Sound costs it four volatile writes; the sustained
 * haptic layer costs it one `vibrate()` per control period rather than one per frame — see
 * `ContinuousHaptics.kt`, which is also where the actuator is given back when an alarm raises, a
 * decision this loop deliberately has no way to make.
 */
internal suspend fun runGameLoop(
    /** World x the car is dropped at — the tap, not the track's origin. */
    dropAtX: Float,
    /** Invoked ONCE, on the game thread, after the very first frame has been published. The caller
     *  keeps the chart on screen until then: the draw reads a shared frame buffer, and before the first
     *  publish that buffer is still all zeros — a car at the world origin under a camera at zero, which
     *  renders for one frame as the "flash". */
    onFirstFrame: suspend () -> Unit,
    /** World x the camera opens on — the chart's left edge. The track is cut a little earlier so the
     *  curve enters the panel from off-screen; that lead must sit outside the view, not shift it. */
    seatAtX: Float,
    world: GameWorld,
    track: GameTrack,
    bus: GameFrameBus,
    camera: GameCamera,
    zoom: GameZoom,
    controls: GameControls,
    viewport: GameViewport,
    gate: GamePauseGate,
    commands: GameCommands,
    hud: HudState,
    latest: LiveReadingRef,
    zone: ZoneId,
    feel: FeelSink = FeelSink.None,
    pacer: FrameClockPacer = FrameClockPacer(),
) {
    val trackLength = world.trackLength
    var placed = false
    var signalled = false
    var hudAtNs = 0L
    var hudMinute = Long.MIN_VALUE
    var clock = ""
    var lastPaused = false
    // The presentation clock, loop-local for the same reason `terminal` is: a fact the loop learns from
    // its own frames. The opening's own progress lives in `zoom`, which owns it.
    var presentNs = 0L
    // The speed the view is sized from, one frame behind the solver. Zeroed on placement so a restart
    // opens at rest rather than inheriting the width the crash happened at.
    var lastSpeed = 0f
    // Where the car was seated. The run is measured from the TAP, so this is the zero of the progress
    // bar; `CarState.distanceM` cannot serve, being a monotone furthest-reached rather than a position.
    var seatX = 0f
    // The exhaust's emission phase. Advanced on the SIMULATED timestep, so the trail stops with the car
    // rather than drifting on during a hold.
    var exhaustPhase = 0f

    /** The HUD write, hoisted so PLACEMENT can use it too.
     *
     *  It used to live only in the simulated-frame branch, and that was the bug behind a terminal card
     *  that would not dismiss: Restart places the car and clears `terminal` immediately, but the card is
     *  rendered off `hud.value.run`, which still said `Finished` — and the next write was gated behind a
     *  simulated frame, which does not arrive until the whole opening (zoom, then drop) has finished. So
     *  the card sat over the reveal and vanished only once the car was already on screen. */
    /** Fraction of the way from the seat to the finish. A track seated at or past the finish has no run
     *  to measure, so it reads as complete rather than dividing by nothing. */
    fun progressOf(x: Float): Float {
        val span = trackLength - seatX
        if (!span.isFinite() || span <= 1e-3f) return 1f
        val p = (x - seatX) / span
        return if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
    }

    fun pushHud(s: CarState, atNs: Long) {
        hudAtNs = atNs
        val minute = track.map.tsMsAt(s.x) / 60_000L
        if (minute != hudMinute) {
            hudMinute = minute
            clock = Instant.ofEpochMilli(minute * 60_000L).atZone(zone).format(CLOCK_FORMAT)
        }
        hud.value = GameHud(
            clock = clock,
            distanceM = s.distanceM,
            readingAgeMin = latest.tsMs?.let { (System.currentTimeMillis() - it) / 60_000L },
            run = s.run,
            hold = gate.holds.primary,
        )
    }
    // A terminal run is a hold in its own right ([GameHold.Modal]'s own case, the terminal card): the
    // scene behind that card cannot change, and the solver short-circuits on it anyway, so simulating
    // it would be sixty FFI round trips and sixty full-screen redraws a second of a frozen picture.
    // Loop-local rather than a gate bit because the gate is the COMPOSITION's to write; this is a fact
    // the loop learns from the state it just published, and a restart is the only thing that clears it.
    var terminal = false

    while (coroutineContext.isActive) {
        val nowNs = withFrameNanos { it }
        if (!viewport.ready) {
            // An un-laid-out viewport counts as a hold: the pacer must not bank the time spent waiting
            // for the first measure, or the opening frame arrives carrying all of it.
            pacer.tick(nowNs, paused = true)
            continue
        }
        // PRESENTATION time, kept apart from simulation time on purpose. The opening has to animate
        // while the solver is HELD — a car that drove during its own reveal would arrive somewhere the
        // camera had not been told about — so the zoom and the drop are
        // stepped on the frame clock directly while the pacer's timestep stays reserved for physics.
        val presentDt = if (presentNs == 0L) 0f else ((nowNs - presentNs) * 1e-6f).coerceIn(0f, 100f)
        presentNs = nowNs
        // The opening frame, and every restart. Placement is itself a hold, so the frame it consumes is
        // dropped rather than simulated: a car being put on the start line has no timestep.
        val placing = !placed || commands.reset
        if (placing) {
            // The zoom OPENS on the chart's span — so the panel is identical the frame before and the
            // frame after Drive is tapped — and eases to the car's own. The move that follows is one the
            // player can watch happen rather than a cut.
            zoom.seatAt(viewport.visibleWidthM)
            lastSpeed = 0f
        }
        // BEFORE `opening` is read, deliberately. Reading the hold off last frame's state would spend one
        // whole frame held after the opening had in fact finished — invisible in production, but it is a
        // frame of simulation the loop owes the caller and every step-counting test would be short by it.
        val animating = !gate.paused && presentDt > 0f
        // LAST frame's speed, because the view is sized before the solver runs. One frame of lag on a
        // filtered quantity is not perceptible, and reordering to get the current speed would mean
        // sizing the camera against a pose that has not been published yet.
        val viewW = if (animating) zoom.step(viewport.zoomedWidthM, lastSpeed, presentDt / 1000f) else zoom.spanM
        // Both axes by the same factor — see `GameZoom.fov`. The car is true-scale, so widening time
        // alone would squash it.
        val viewH = viewport.worldHeightM * zoom.fov
        // A loop-local hold, like `terminal`: cosmetic state the loop learns from its own frames rather
        // than something composition could tell it.
        val opening = zoom.opening
        val paused = gate.paused || placing || terminal || opening
        val dtMs = pacer.tick(nowNs, paused)

        if (placing) {
            // Snap rather than fly in from wherever the camera was, and publish once so there is
            // something to draw before the first live tick.
            // ALWAYS place at the tap, not only on a restart. `commands.reset` is false on the first
            // placement, so this used to fall through to `state()` — the car wherever the constructor's
            // own reset left it, i.e. the track ORIGIN, a full span left of the viewport. That is why it
            // appeared at the far left and the camera set off after it.
            val s = world.resetAt(dropAtX)
            commands.reset = false
            terminal = s.run != RunState.Running
            // Seat the camera on the CHART'S viewport, not on the car: entering drive mode must not
            // move the panel. The track begins at the viewport's left edge, so world x = 0 is exactly
            // where the chart's left edge already is. The camera then eases onto the car as it drives,
            // which is motion the player caused rather than a jump they did not.
            seatX = s.x
            camera.seatAt(seatAtX, s.y, viewH)
            publish(bus, s, controls, camera, viewW, viewH, zoom.carShown, zoom.liftM, exhaustPhase, progressOf(s.x))
            if (!signalled) {
                signalled = true
                onFirstFrame()
            }
            placed = true
            // At once, not at the cadence: this is what dismisses the terminal card the instant Restart
            // is pressed, rather than leaving it over the reveal.
            pushHud(s, nowNs)
        } else if (dtMs > 0f) {
            // Ease the pedals toward what the finger is asking for BEFORE stepping: the solver must
            // never see a boolean step from rest, which at this thrust-to-weight only lifts the nose.
            controls.ramp(dtMs / 1000f)
            val s = world.step(dtMs, controls.throttle, controls.brake)
            camera.follow(s.x, s.y, s.vx, s.vy, viewW, viewH, trackLength, dtMs / 1000f)
            publish(bus, s, controls, camera, viewW, viewH, zoom.carShown, zoom.liftM, exhaustPhase, progressOf(s.x))
            // Sound and touch ride the SIMULATED frames, not the callbacks: on a 120 Hz panel half the
            // callbacks carry no timestep, and a bed re-armed from one of those would be describing a
            // world that had not moved.
            feel.frame(s)
            lastSpeed = s.vx
            exhaustPhase = (
                exhaustPhase + (dtMs / 1000f) * (SMOKE_IDLE_HZ + (SMOKE_HZ - SMOKE_IDLE_HZ) * s.throttleApplied)
                ) % SMOKE_PHASE_WRAP
            terminal = s.run != RunState.Running

            // A pause change must show at once, and so must the run ending — this is the last frame
            // simulated, so a HUD left waiting for the cadence would never carry the terminal state
            // the card is rendered off. Everything else waits.
            if (nowNs - hudAtNs >= HUD_PERIOD_NS || paused != lastPaused || terminal) {
                pushHud(s, nowNs)
            }
        } else if (opening && animating) {
            // The world is still opening. Republish every frame so the zoom and the drop are actually
            // seen — the draw is invalidated by a frame commit and nothing else, so a held loop that
            // stopped publishing would freeze the panel at the placement frame and then cut to the
            // settled one. `state()` rather than `step()`: the car is being shown, not driven.
            //
            // The camera eases with zero velocity, which is what pans it off the chart's left edge and
            // onto the car as the span closes. It has to move: `targetLeft` is a function of the span,
            // so a camera left seated while the span shrank tenfold would leave the car off-screen.
            val s = world.state()
            camera.follow(s.x, s.y, 0f, 0f, viewW, viewH, trackLength, presentDt / 1000f)
            publish(bus, s, controls, camera, viewW, viewH, zoom.carShown, zoom.liftM, exhaustPhase, progressOf(s.x))
        } else if (paused != lastPaused || (paused && gate.holds.primary != hud.value.hold)) {
            // The REASON can change while the answer does not — an alarm raised behind the terminal
            // card, or over an already-backgrounded run — and the banner names the reason. Guarded by
            // `paused` so the un-simulated callbacks of a 120 Hz panel read nothing at all.
            hud.value = hud.value.copy(hold = gate.holds.primary)
        }
        // A hold silences both surfaces, and keeps doing so: the sustained haptic layer is finite and
        // re-armed (see `ContinuousHaptics.kt`), so ceasing to ask for it IS the stop — but the synth's
        // engine level is a held field and has to be told.
        if (paused) feel.hold()
        lastPaused = paused
    }
}

private fun publish(
    bus: GameFrameBus,
    s: CarState,
    controls: GameControls,
    camera: GameCamera,
    camWidthM: Float,
    camHeightM: Float,
    carShown: Boolean,
    carLiftM: Float,
    exhaustPhase: Float,
    progress: Float,
) {
    bus.back().set(
        s, controls.throttle, controls.brake, camera,
        camWidthM, camHeightM, carShown, carLiftM, exhaustPhase, progress,
    )
    bus.commit()
}

/** One-shot requests from the UI to the loop, in plain memory for the same reason as [GameControls]:
 *  a restart is a press, not a state the composition has any reason to observe. */
class GameCommands {
    @Volatile
    var reset = false
}
