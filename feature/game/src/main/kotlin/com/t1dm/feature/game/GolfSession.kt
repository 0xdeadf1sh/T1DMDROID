package com.t1dm.feature.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.t1dm.core.common.GolfWorld
import com.t1dm.core.model.BallState
import com.t1dm.core.model.GolfRun
import com.t1dm.ui.game.GameTrack
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlin.math.hypot

/** Rebuilt at HUD_PERIOD_NS, never at frame rate: the one cell whose change recomposes anything. */
data class GolfHud(
    val clock: String,
    val strokes: Int,
    val penalties: Int,
    val readingAgeMin: Long?,
    val run: GolfRun,
    val hold: GameHold?,
) {
    /** What the end card reads out: a penalty costs a stroke. */
    val score: Int get() = strokes + penalties

    companion object {
        val EMPTY = GolfHud("", 0, 0, null, GolfRun.Playing, null)
    }
}

/** Own holder, so the card is the only composable that reads it. */
class GolfHudState {
    var value by mutableStateOf(GolfHud.EMPTY)
}

/** 4 Hz — fast enough that the stroke count never looks stuck. */
private const val HUD_PERIOD_NS = 250_000_000L

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm · d MMM")

/** Seconds a splash ring stays on the panel after the ball is lost. */
private const val SPLASH_LIFE_S = 1.1f

/** Runs on T1dmDispatchers.game, never inference/default; snapshot touched by commit/HUD only. */
internal suspend fun runGolfLoop(
    /** World x of the tap: where the ball is teed, not the track's origin. */
    teeAtX: Float,
    /** Once, on the game thread, after the first publish; caller keeps the chart up until then. */
    onFirstFrame: suspend () -> Unit,
    /** World x the camera opens on; the track's lead must sit outside the view, not shift it. */
    seatAtX: Float,
    world: GolfWorld,
    track: GameTrack,
    bus: GolfFrameBus,
    camera: GameCamera,
    zoom: GameZoom,
    controls: GolfControls,
    viewport: GameViewport,
    gate: GamePauseGate,
    commands: GolfCommands,
    hud: GolfHudState,
    latest: LiveReadingRef,
    zone: ZoneId,
    feel: GolfFeelSink = GolfFeelSink.None,
    pacer: FrameClockPacer = FrameClockPacer(),
) {
    val cup = world.cup
    var placed = false
    var signalled = false
    var hudAtNs = 0L
    var hudMinute = Long.MIN_VALUE
    var clock = ""
    var lastPaused = false
    var presentNs = 0L
    // One frame behind the solver; zeroed on placement so a restart opens at rest.
    var lastSpeed = 0f
    var teeX = 0f
    var teeY = 0f
    var penalties = 0
    var splashX = 0f
    var splashY = 0f
    var splash = 0f

    /** Teed at or past the lip reads as complete rather than dividing by nothing. */
    fun progressOf(x: Float): Float {
        val span = cup.x1 - teeX
        if (!span.isFinite() || span <= 1e-3f) return 1f
        val p = (x - teeX) / span
        return if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
    }

    fun pushHud(s: BallState, atNs: Long) {
        hudAtNs = atNs
        val minute = track.map.tsMsAt(s.x) / 60_000L
        if (minute != hudMinute) {
            hudMinute = minute
            clock = Instant.ofEpochMilli(minute * 60_000L).atZone(zone).format(CLOCK_FORMAT)
        }
        hud.value = GolfHud(
            clock = clock,
            strokes = s.strokes,
            penalties = s.penalties,
            readingAgeMin = latest.tsMs?.let { (System.currentTimeMillis() - it) / 60_000L },
            run = s.run,
            hold = gate.holds.primary,
        )
    }

    fun publish(s: BallState, viewW: Float, viewH: Float) {
        bus.back().set(
            s, camera, viewW, viewH, zoom.carShown, zoom.liftM, controls,
            teeX, teeY, splashX, splashY, splash, progressOf(s.x),
        )
        bus.commit()
    }
    // A holed round is a hold: simulating it is sixty FFI round trips a second of a frozen frame.
    var terminal = false

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
            // Opens on the chart's span, so the panel matches before and after Golf is tapped.
            zoom.seatAt(viewport.visibleWidthM)
            lastSpeed = 0f
        }
        // Read BEFORE opening: off last frame's state the loop would owe the caller a frame of sim.
        val animating = !gate.paused && presentDt > 0f
        // Last frame's speed: the view is sized before the solver runs.
        val viewW = if (animating) zoom.step(viewport.zoomedWidthM, lastSpeed, presentDt / 1000f) else zoom.spanM
        // Same factor on both axes — see `GameZoom.fov`; the ball is true-scale.
        val viewH = viewport.worldHeightM * zoom.fov
        val opening = zoom.opening
        val paused = gate.paused || placing || terminal || opening
        val dtMs = pacer.tick(nowNs, paused)

        if (placing) {
            // Always teeAt, not only on a restart: state() leaves the ball at the track origin.
            val s = world.teeAt(teeAtX)
            commands.reset = false
            commands.takeShot()
            controls.release()
            terminal = s.run != GolfRun.Playing
            teeX = s.x
            teeY = s.y
            penalties = s.penalties
            splash = 0f
            // On the chart's viewport, not the ball: entering golf mode must not move the panel.
            camera.seatAt(seatAtX, s.y, viewH)
            publish(s, viewW, viewH)
            if (!signalled) {
                signalled = true
                onFirstFrame()
            }
            placed = true
            // At once, not at cadence: this dismisses the end card the instant Restart hits.
            pushHud(s, nowNs)
        } else if (dtMs > 0f) {
            val shot = commands.takeShot()
            if (shot != 0L) world.shoot(GolfCommands.shotVx(shot), GolfCommands.shotVy(shot))
            val s = world.step(dtMs)
            // The drop-back has already moved the ball, so the ring is pinned where it went in.
            if (s.penalties != penalties) {
                penalties = s.penalties
                splashX = s.x
                splashY = s.y
                splash = 1f
            } else if (splash > 0f) {
                splash = (splash - (dtMs / 1000f) / SPLASH_LIFE_S).coerceAtLeast(0f)
            }
            camera.follow(s.x, s.y, s.vx, s.vy, viewW, viewH, cup.x1, dtMs / 1000f)
            publish(s, viewW, viewH)
            // Simulated frames only: on a 120 Hz panel half the callbacks carry no timestep.
            feel.frame(s)
            lastSpeed = hypot(s.vx, s.vy)
            terminal = s.run != GolfRun.Playing

            // A pause change and the round ending must show at once; this frame is the last one.
            if (nowNs - hudAtNs >= HUD_PERIOD_NS || paused != lastPaused || terminal) {
                pushHud(s, nowNs)
            }
        } else if (opening && animating) {
            // Republish every frame; state() not step(): shown not played; camera eases the span.
            val s = world.state()
            camera.follow(s.x, s.y, 0f, 0f, viewW, viewH, cup.x1, presentDt / 1000f)
            publish(s, viewW, viewH)
        } else if (paused != lastPaused || (paused && gate.holds.primary != hud.value.hold)) {
            // The reason can change while the answer does not, and the banner names the reason.
            hud.value = hud.value.copy(hold = gate.holds.primary)
        }
        // The haptic layer is finite and re-armed, so ceasing to ask IS the stop; synth must know.
        if (paused) feel.hold()
        lastPaused = paused
    }
}
