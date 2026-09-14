package com.t1dm.feature.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.t1dm.core.common.GolfWorld
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.rememberHapticMixer
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.GamePropDensity
import com.t1dm.core.model.GolfRun
import com.t1dm.core.model.GolfTuning
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.Obstacle
import com.t1dm.core.model.TerrainSpec
import com.t1dm.ui.game.obstacles
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.game.GameTrack
import com.t1dm.ui.graph.ChalkPens
import com.t1dm.ui.graph.PredictedClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Settled span as a share of a 45° full-power carry: the shot fits, the ball stays aimable. */
private const val VIEW_CARRY_FRAC = 0.65f

/** Floor on the press radius, so the ball can be grabbed at any zoom the camera reaches. */
private val MIN_GRAB = 28.dp

/** Metres kept clear of obstacles behind the tee, and ahead of it: room for the opening shot. */
private const val TEE_BEHIND_M = 15f
private const val TEE_AHEAD_M = 80f

/** The figure's screen height: fixed, so the golfer is a golfer at every zoom the round reaches. */
private val GOLFER_H = 64.dp

/** Solver runs on gameDispatcher, never inference or default; alarmRaised releases actuator now. */
@Composable
fun GolfScreen(
    modifier: Modifier = Modifier,
    trackFromMs: Long,
    dropAtMs: Long,
    thresholds: AlertThresholds?,
    predictedClock: PredictedClock?,
    /** Caller keeps the chart up until this fires. */
    onReady: () -> Unit = {},
    spanMinutes: Float,
    latestReadingMs: Long?,
    unit: UnitSpace,
    kovatchevF: ((Double) -> Double)?,
    rangeMinMgdl: Int,
    rangeMaxMgdl: Int,
    paintStrokes: List<PaintStroke>,
    golfTuning: GolfTuning?,
    propDensity: GamePropDensity,
    readingsFrom: suspend (fromMs: Long) -> List<CgmReading>,
    openWorld: (TerrainSpec, GolfTuning, List<Obstacle>) -> GolfWorld,
    gameDispatcher: CoroutineDispatcher,
    alarmRaised: Boolean,
    onExit: () -> Unit,
) {
    val scene by produceState<GameScene?>(null, trackFromMs, spanMinutes, unit, thresholds, propDensity) {
        value = null
        val leadMs = (spanMinutes.toDouble() * 60_000.0 * TRACK_LEAD_SPANS).toLong()
        value = loadGameScene(
            readingsFrom(trackFromMs - leadMs),
            paintStrokes, unit, kovatchevF, rangeMinMgdl, rangeMaxMgdl, thresholds, propDensity,
        )
    }

    val built = scene
    // No opaque base: game mode is a mode of the panel, and the backdrop must show through.
    Box(modifier) {
        when {
            built == null || golfTuning == null -> GameLoading()
            !built.track.isPlayable -> GameRefusal("Not enough ground to play", onExit)
            else -> key(built) {
                GolfStage(
                    scene = built,
                    kovatchevF = kovatchevF,
                    onFirstFrame = onReady,
                    seatAtMs = trackFromMs,
                    dropAtMs = dropAtMs,
                    thresholds = thresholds,
                    predictedClock = predictedClock,
                    spanMinutes = spanMinutes,
                    tuning = golfTuning,
                    latestReadingMs = latestReadingMs,
                    openWorld = openWorld,
                    gameDispatcher = gameDispatcher,
                    alarmRaised = alarmRaised,
                    onExit = onExit,
                )
            }
        }
    }
}

/** Written in the draw phase, read by the pointer handler; plain volatile, never snapshot state. */
private class AimAnchor {
    @Volatile
    var ballPxX = 0f

    @Volatile
    var ballPxY = 0f

    @Volatile
    var grabPx = 0f

    @Volatile
    var fullPx = 0f

    /** False whenever a press must not start an aim: mid-flight, mid-opening, or holed. */
    @Volatile
    var live = false
}

/** Own composable so every per-round holder lives one scene's lifetime (via key). */
@Composable
private fun GolfStage(
    scene: GameScene,
    kovatchevF: ((Double) -> Double)?,
    onFirstFrame: () -> Unit,
    seatAtMs: Long,
    dropAtMs: Long,
    thresholds: AlertThresholds?,
    predictedClock: PredictedClock?,
    spanMinutes: Float,
    tuning: GolfTuning,
    latestReadingMs: Long?,
    openWorld: (TerrainSpec, GolfTuning, List<Obstacle>) -> GolfWorld,
    gameDispatcher: CoroutineDispatcher,
    alarmRaised: Boolean,
    onExit: () -> Unit,
) {
    val palette = LocalT1dmSemantics.current
    val skin = remember(palette) { GameSkin(palette) }
    val art = remember(tuning) { GolfArt(tuning) }
    val dpPx = with(LocalDensity.current) { 1.dp.toPx() }
    val chalk = remember(dpPx) { ChalkPens(dpPx) }
    // The frame path allocates nothing; a Path rewound is a Path reused.
    val groundPath = remember { Path() }
    val paintPath = remember { Path() }
    val propMeasurer = rememberTextMeasurer(cacheSize = 8)
    val signStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val propArt = remember(dpPx, propMeasurer, signStyle) { PropArt(propMeasurer, signStyle, dpPx) }

    val bus = remember { GolfFrameBus() }
    // Tighter and longer-sighted than the drive's: the ball outruns a car's follow at launch.
    val camera = remember { GameCamera(followHz = 9f, leadSeconds = 0.6f) }
    val zoom = remember { GameZoom(fullScaleSpeedMs = tuning.maxLaunchSpeed, fovWiden = 1.2f) }
    val controls = remember { GolfControls() }
    val viewport = remember { GameViewport() }
    val gate = remember { GamePauseGate() }
    val commands = remember { GolfCommands() }
    val hud = remember { GolfHudState() }
    val liveRef = remember { LiveReadingRef() }
    val anchor = remember { AimAnchor() }
    val aim = remember { GolfAim() }
    val zone = remember { ZoneId.systemDefault() }
    // Pinned for the round: a forecast arriving mid-round would re-lay the inset under the ball.
    val runClock = remember { predictedClock }
    val haptics = rememberT1dmHaptics()
    // Explicit teardown: a generator thread outliving its screen writes silence forever.
    val mixer = rememberHapticMixer()
    val audio = rememberGameAudio()
    val feel = remember(mixer, audio, tuning) { GolfFeel(audio, mixer, tuning.maxLaunchSpeed) }
    val chrome = remember(scene, thresholds, runClock, kovatchevF) {
        GameChrome(scene.track.map, scene.unit, scene.tzOffsetMin, thresholds, runClock, kovatchevF)
    }
    // From the tuning, not transcribed: a full-power 45° shot is `v²/g` of carry.
    val settledWidthM = remember(tuning) {
        VIEW_CARRY_FRAC * tuning.maxLaunchSpeed * tuning.maxLaunchSpeed / tuning.gravity
    }
    val minGrabPx = with(LocalDensity.current) { MIN_GRAB.toPx() }
    // Converted in composition, not per frame: sixty density lookups a second for a constant.
    val golferPx = with(LocalDensity.current) { GOLFER_H.toPx() }

    SideEffect {
        liveRef.tsMs = latestReadingMs
        art.golferPx = golferPx
    }

    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(confirmExit) { gate.set(GameHold.Modal, confirmExit) }

    fun requestExit() {
        val h = hud.value
        if (h.run == GolfRun.Playing && h.score > 0) confirmExit = true else onExit()
    }

    BackHandler { requestExit() }

    // The shell draws nothing until the loop hands off, so a refused world must say so itself.
    var openFailed by remember { mutableStateOf(false) }
    if (openFailed) {
        GameRefusal("This stretch cannot be played", onExit)
        return
    }

    val aimInput = Modifier.pointerInput(tuning) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!anchor.live) return@awaitEachGesture
            val dx0 = down.position.x - anchor.ballPxX
            val dy0 = down.position.y - anchor.ballPxY
            if (!nearBall(dx0, dy0, anchor.grabPx)) return@awaitEachGesture
            down.consume()
            aim.clear()
            controls.aiming = true
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                aim.set(
                    change.position.x - anchor.ballPxX,
                    change.position.y - anchor.ballPxY,
                    anchor.fullPx,
                    tuning.maxLaunchSpeed,
                )
                controls.aimVx = aim.vx
                controls.aimVy = aim.vy
                change.consume()
                if (!change.pressed) break
            }
            val vx = aim.vx
            val vy = aim.vy
            controls.release()
            aim.clear()
            // Released back inside the dead radius: the aim zeroed itself, so nothing is fired.
            if (vx != 0f || vy != 0f) commands.fire(vx, vy)
        }
    }

    GameShell(
        bus = bus,
        viewport = viewport,
        gate = gate,
        senses = feel,
        chrome = chrome,
        spanMinutes = spanMinutes,
        artScalePx = 0f,
        settledWidthM = settledWidthM,
        alarmRaised = alarmRaised,
        gameDispatcher = gameDispatcher,
        onFirstFrame = onFirstFrame,
        onBackground = { controls.release() },
        canvasModifier = aimInput,
        loop = { handOff ->
            val walls = scene.props.obstacles(
                scene.track.map.worldXOf(dropAtMs), TEE_BEHIND_M, TEE_AHEAD_M, insetM = tuning.ballRadius,
            )
            val world = runCatching { openWorld(scene.track.terrain, tuning, walls) }.getOrNull()
            if (world == null) {
                withContext(Dispatchers.Main) { openFailed = true }
                return@GameShell
            }
            try {
                art.cup = world.cup
                runGolfLoop(
                    scene.track.map.worldXOf(dropAtMs),
                    handOff,
                    scene.track.map.worldXOf(seatAtMs),
                    world, scene.track, bus, camera, zoom, controls, viewport,
                    gate, commands, hud, liveRef, zone, feel,
                    Golfer(tuning.ballRadius, tuning.maxLaunchSpeed).also { it.stancePx = STANCE_H * golferPx },
                )
            } finally {
                // Refcounted in Rust, freed by a JVM Cleaner: dropping it leaks the heightfield.
                world.close()
            }
        },
        drawWorld = { f, p ->
            drawGameWorld(
                scene.track, scene.paint, skin,
                p.camLeftM, p.camWidthM, p.pxPerXM, p.floorPx,
                groundPath, paintPath, chalk,
                pxPerWorldY = p.pxPerYM,
                fillSky = false,
                // With the ball, not before it: the scenery lands when the drop does.
                props = if (f.ballShown) scene.props else null,
                propArt = propArt,
                simS = f.simS,
                hour = hourAt(scene.track.map, scene.tzOffsetMin, p.camLeftM + p.camWidthM * 0.5f),
                plotTop = p.plotTop,
                plotBottom = p.plotBottom,
            )
            drawCup(art, skin, p.camLeftM, p.pxPerXM, p.pxPerYM, p.floorPx)
            drawTee(art, f, skin, p.camLeftM, p.pxPerXM, p.pxPerYM, p.floorPx)
            drawSplash(art, f, skin, p.camLeftM, p.pxPerXM, p.pxPerYM, p.floorPx)
            drawGolfer(art, f, skin, scene.track, p.camLeftM, p.pxPerXM, p.pxPerYM, p.floorPx)
            val rPx = ballPx(art, p.pxPerXM)
            // Drawn centre less physical centre; the drop's lift rides the vertical scale.
            val ballDy = art.ballRadius * p.pxPerYM - rPx - f.ballLiftM * p.pxPerYM
            if (f.ballShown) {
                // World metres, so the opening drop rides the vertical scale.
                translate(top = -f.ballLiftM * p.pxPerYM) {
                    drawBall(art, f, skin, p.camLeftM, p.pxPerXM, p.pxPerYM, p.floorPx)
                }
            }
            if (f.aiming) {
                val n = arcOf(art, f, scene.track)
                drawAimArc(art, n, p.camLeftM, p.pxPerXM, p.pxPerYM, p.floorPx, skin, ballDy)
            }
            // Panel coordinates, so the pointer handler need not redo the plot transform.
            anchor.ballPxX = p.plotLeft + (f.x - p.camLeftM) * p.pxPerXM
            // The DRAWN disc's centre: the finger grabs what is on the panel, not the collider.
            anchor.ballPxY = p.floorPx - f.y * p.pxPerYM + ballDy
            anchor.grabPx = grabRadiusPx(art.ballRadius, p.pxPerXM, minGrabPx)
            anchor.fullPx = dragFullPx(size.width, size.height)
            anchor.live = f.ballShown && f.atRest && f.run == GolfRun.Playing.ordinal
        },
    ) {
        EndCard(
            hud,
            Modifier.align(Alignment.Center),
            onRestart = { haptics.perform(HapticEvent.Tap); commands.reset = true },
            onExit = onExit,
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard round?") },
            confirmButton = { TextButton(onClick = onExit) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep playing") } },
        )
    }
}

/** Draw-phase only: samples the arc the current aim would fly. */
private fun arcOf(art: GolfArt, f: BallFrame, track: GameTrack): Int = ballisticArc(
    f.x, f.y, f.aimVx, f.aimVy, art.gravity, ARC_STEP_S, art.ballRadius, track, art.arc,
)

@Composable
private fun EndCard(hud: GolfHudState, modifier: Modifier, onRestart: () -> Unit, onExit: () -> Unit) {
    val h = hud.value
    if (h.run == GolfRun.Playing) return
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Holed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val score = if (h.penalties > 0) {
                "${h.score} strokes · ${h.penalties} in water"
            } else {
                "${h.score} strokes"
            }
            Text(score, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = onRestart) { Text("Restart") }
                TextButton(onClick = onExit) { Text("Exit") }
            }
        }
    }
}
