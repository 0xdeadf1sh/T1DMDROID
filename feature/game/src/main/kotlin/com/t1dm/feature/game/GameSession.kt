package com.t1dm.feature.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.t1dm.core.common.GameWorld
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CarState
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.GamePropDensity
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.RunState
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.game.GameTrack
import com.t1dm.ui.game.PropSet
import com.t1dm.ui.game.TrackTrace
import com.t1dm.ui.game.WorldPaint
import com.t1dm.ui.game.buildGameTrack
import com.t1dm.ui.game.buildProps
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

/** Decimation buckets place a max-before-min cliff at edges, not the data; 1e6 = ~9.5y grid. */
private const val TRACK_MAX_POINTS = 1_000_000

/** Immutable; shared by the loop and the draw. */
class GameScene(
    val track: GameTrack,
    val paint: WorldPaint,
    /** The unit the trace was cut in, not the live setting. */
    val unit: UnitSpace,
    val tzOffsetMin: Int,
    val props: PropSet,
)

/** [readings] is the run's window: the chosen start day's midnight to the newest reading. */
suspend fun loadGameScene(
    readings: List<CgmReading>,
    strokes: List<PaintStroke>,
    unit: UnitSpace,
    kovatchevF: ((Double) -> Double)?,
    rangeMinMgdl: Int,
    rangeMaxMgdl: Int,
    thresholds: AlertThresholds? = null,
    propDensity: GamePropDensity = GamePropDensity.Sparse,
): GameScene = withContext(Dispatchers.Default) {
    val frame = buildGraphFrame(readings, unit, maxPoints = TRACK_MAX_POINTS, kovatchevF = kovatchevF)
    val track = buildGameTrack(TrackTrace.of(frame), rangeMinMgdl, rangeMaxMgdl, kovatchevF)
    val paint = if (strokes.isEmpty() || !track.isPlayable) {
        WorldPaint.EMPTY
    } else {
        buildWorldPaint(buildPaintFrame(strokes), track)
    }
    GameScene(track, paint, unit, frame.tzOffsetMin, buildProps(track, readings, thresholds, propDensity))
}

/** Rebuilt at HUD_PERIOD_NS, never frame rate: the one cell whose change recomposes anything. */
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

/** Own holder, so the strip is the only composable that reads it. */
class HudState {
    var value by mutableStateOf(GameHud.EMPTY)
}

/** Plain memory: the loop must not take a snapshot read for the reading age. */
class LiveReadingRef {
    @Volatile
    var tsMs: Long? = null
}

/** 4 Hz — fast enough that the clock never looks stuck at speed. */
private const val HUD_PERIOD_NS = 250_000_000L

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm · d MMM")

private const val SMOKE_IDLE_HZ = 4f

private const val SMOKE_HZ = 26f

/** Used only modulo the puff count; wraps to keep the phase out of float's coarse range. */
private const val SMOKE_PHASE_WRAP = 1_048_576f

/** Runs on game dispatcher, never inference/default; withFrameNanos on MAIN returns the stamp. */
internal suspend fun runGameLoop(
    /** World x of the tap, not the track's origin. */
    dropAtX: Float,
    /** Once, on the game thread, after the first publish; caller keeps the chart up until then. */
    onFirstFrame: suspend () -> Unit,
    /** World x camera opens on; the track's lead must sit outside the view, not shift it. */
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
    var presentNs = 0L
    // One frame behind the solver; zeroed on placement so a restart opens at rest.
    var lastSpeed = 0f
    // Zero of the progress bar; CarState.distanceM is a monotone furthest-reached, not a position.
    var seatX = 0f
    // Advanced on the SIMULATED timestep, so the trail stops with the car during a hold.
    var exhaustPhase = 0f

    /** Seated at or past the finish reads as complete rather than dividing by nothing. */
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
    // Terminal run is a hold: simulating a frozen scene wastes 60 FFI round trips/s; loop-local.
    var terminal = false
    var simS = 0f

    while (coroutineContext.isActive) {
        val nowNs = withFrameNanos { it }
        if (!viewport.ready) {
            // A hold, so the pacer doesn't bank the first-measure wait into the opening frame.
            pacer.tick(nowNs, paused = true)
            continue
        }
        // Presentation time, apart from simulation time: opening animates while the solver is HELD.
        val presentDt = if (presentNs == 0L) 0f else ((nowNs - presentNs) * 1e-6f).coerceIn(0f, 100f)
        presentNs = nowNs
        // Placement is itself a hold: the frame it consumes is dropped rather than simulated.
        val placing = !placed || commands.reset
        if (placing) {
            // Opens on the chart's span, so the panel is identical before and after Drive.
            zoom.seatAt(viewport.visibleWidthM)
            lastSpeed = 0f
        }
        // Read BEFORE opening: off last frame's state, the loop owes the caller a frame.
        val animating = !gate.paused && presentDt > 0f
        // Last frame's speed: the view is sized before the solver runs.
        val viewW = if (animating) zoom.step(viewport.zoomedWidthM, lastSpeed, presentDt / 1000f) else zoom.spanM
        // Same factor on both axes — see `GameZoom.fov`; the car is true-scale.
        val viewH = viewport.worldHeightM * zoom.fov
        val opening = zoom.opening
        val paused = gate.paused || placing || terminal || opening
        val dtMs = pacer.tick(nowNs, paused)

        if (placing) {
            // Always resetAt, not only on a restart: state() leaves the car at the track origin.
            val s = world.resetAt(dropAtX)
            commands.reset = false
            terminal = s.run != RunState.Running
            // On the chart's viewport, not on the car: entering drive mode must not move the panel.
            seatX = s.x
            camera.seatAt(seatAtX, s.y, viewH)
            publish(bus, s, controls, camera, viewW, viewH, zoom.carShown, zoom.liftM, exhaustPhase, progressOf(s.x), simS)
            if (!signalled) {
                signalled = true
                onFirstFrame()
            }
            placed = true
            // At once, not the cadence: dismisses the terminal card the instant Restart is pressed.
            pushHud(s, nowNs)
        } else if (dtMs > 0f) {
            simS += dtMs / 1000f
            // Ramp before stepping: a boolean step from rest lifts the nose at this thrust/weight.
            controls.ramp(dtMs / 1000f)
            val s = world.step(dtMs, controls.throttle, controls.brake)
            camera.follow(s.x, s.y, s.vx, s.vy, viewW, viewH, trackLength, dtMs / 1000f)
            publish(bus, s, controls, camera, viewW, viewH, zoom.carShown, zoom.liftM, exhaustPhase, progressOf(s.x), simS)
            // Simulated frames only: on a 120 Hz panel half the callbacks carry no timestep.
            feel.frame(s)
            lastSpeed = s.vx
            exhaustPhase = (
                exhaustPhase + (dtMs / 1000f) * (SMOKE_IDLE_HZ + (SMOKE_HZ - SMOKE_IDLE_HZ) * s.throttleApplied)
                ) % SMOKE_PHASE_WRAP
            terminal = s.run != RunState.Running

            // Pause changes and the run ending must show at once; this is the last frame simulated.
            if (nowNs - hudAtNs >= HUD_PERIOD_NS || paused != lastPaused || terminal) {
                pushHud(s, nowNs)
            }
        } else if (opening && animating) {
            // Republish every frame, invalidates on commit only; state(), not step(), eases camera.
            val s = world.state()
            camera.follow(s.x, s.y, 0f, 0f, viewW, viewH, trackLength, presentDt / 1000f)
            publish(bus, s, controls, camera, viewW, viewH, zoom.carShown, zoom.liftM, exhaustPhase, progressOf(s.x), simS)
        } else if (paused != lastPaused || (paused && gate.holds.primary != hud.value.hold)) {
            // The reason can change while the answer does not, and the banner names the reason.
            hud.value = hud.value.copy(hold = gate.holds.primary)
        }
        // Haptic layer is finite, re-armed: ceasing to ask IS the stop; the synth must be told.
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
    simS: Float,
) {
    val f = bus.back()
    f.set(
        s, controls.throttle, controls.brake, camera,
        camWidthM, camHeightM, carShown, carLiftM, exhaustPhase, progress,
    )
    f.simS = simS
    bus.commit()
}

/** Plain memory: a restart is a press, not state composition has reason to observe. */
class GameCommands {
    @Volatile
    var reset = false
}
