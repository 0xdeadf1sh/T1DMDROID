package com.t1dm.feature.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.t1dm.core.common.GameWorld
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.rememberHapticMixer
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.GamePropDensity
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.RunState
import com.t1dm.core.model.Obstacle
import com.t1dm.core.model.TerrainSpec
import com.t1dm.ui.game.obstacles
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.ChalkPens
import com.t1dm.ui.graph.PredictedClock
import kotlinx.coroutines.CoroutineDispatcher
import java.time.ZoneId

/** Above the 48 dp touch-target floor; two fit without crowding the 220 dp panel. */
private val PEDAL_SIZE = 76.dp

/** Apparent car width; GameViewport.zoomedWidthM picks the span a true-scale car is this wide. */
private val CAR_DRAW_DP = 120.dp

/** Ground cut before chart's left edge, as a multiple of visible span; fixed margin fails at 6h. */
internal const val TRACK_LEAD_SPANS = 1f

/** The pair must fit between the pedals. */
private val GAUGE_RADIUS = 30.dp

/** Metres around the drop kept clear of obstacles: a few car lengths, so no run opens in one. */
private const val DROP_KEEP_OUT_M = 60f

/** Solver runs on gameDispatcher, never inference/default; alarmRaised releases the actuator. */
@Composable
fun GameScreen(
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
    carTuning: CarTuning?,
    propDensity: GamePropDensity,
    readingsFrom: suspend (fromMs: Long) -> List<CgmReading>,
    openWorld: (TerrainSpec, CarTuning, List<Obstacle>) -> GameWorld,
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
            built == null || carTuning == null -> GameLoading()
            !built.track.isPlayable -> GameRefusal("Not enough ground to drive", onExit)
            else -> key(built) {
                GameStage(
                    scene = built,
                    kovatchevF = kovatchevF,
                    onFirstFrame = onReady,
                    seatAtMs = trackFromMs,
                    dropAtMs = dropAtMs,
                    thresholds = thresholds,
                    predictedClock = predictedClock,
                    spanMinutes = spanMinutes,
                    tuning = carTuning,
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

/** Own composable so every per-run holder is remembered against one scene's lifetime (via key). */
@Composable
private fun GameStage(
    scene: GameScene,
    kovatchevF: ((Double) -> Double)?,
    onFirstFrame: () -> Unit,
    seatAtMs: Long,
    dropAtMs: Long,
    thresholds: AlertThresholds?,
    predictedClock: PredictedClock?,
    spanMinutes: Float,
    tuning: CarTuning,
    latestReadingMs: Long?,
    openWorld: (TerrainSpec, CarTuning, List<Obstacle>) -> GameWorld,
    gameDispatcher: CoroutineDispatcher,
    alarmRaised: Boolean,
    onExit: () -> Unit,
) {
    val palette = LocalT1dmSemantics.current
    val skin = remember(palette) { GameSkin(palette) }
    val art = remember(tuning) { CarArt(tuning) }
    val dpPx = with(LocalDensity.current) { 1.dp.toPx() }
    val chalk = remember(dpPx) { ChalkPens(dpPx) }
    // The frame path allocates nothing; a Path rewound is a Path reused.
    val groundPath = remember { Path() }
    val paintPath = remember { Path() }
    val propMeasurer = rememberTextMeasurer(cacheSize = 8)
    val signStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val propArt = remember(dpPx, propMeasurer, signStyle) { PropArt(propMeasurer, signStyle, dpPx) }

    val bus = remember { GameFrameBus() }
    val camera = remember { GameCamera() }
    val zoom = remember { GameZoom() }
    val controls = remember { GameControls() }
    val viewport = remember { GameViewport() }
    val gate = remember { GamePauseGate() }
    val commands = remember { GameCommands() }
    val hud = remember { HudState() }
    val liveRef = remember { LiveReadingRef() }
    val zone = remember { ZoneId.systemDefault() }
    // Pinned for the run: a forecast landing mid-run would re-lay the top inset under a moving car.
    val runClock = remember { predictedClock }
    val haptics = rememberT1dmHaptics()
    // Explicit teardown: a generator thread outliving its screen writes silence forever.
    val mixer = rememberHapticMixer()
    val audio = rememberGameAudio()
    val feel = remember(mixer, audio) { GameFeel(audio, mixer) }
    val chrome = remember(scene, thresholds, runClock, kovatchevF) {
        GameChrome(scene.track.map, scene.unit, scene.tzOffsetMin, thresholds, runClock, kovatchevF)
    }

    SideEffect { liveRef.tsMs = latestReadingMs }

    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(confirmExit) { gate.set(GameHold.Modal, confirmExit) }

    fun requestExit() {
        val h = hud.value
        if (h.run == RunState.Running && h.distanceM > 1f) confirmExit = true else onExit()
    }

    BackHandler { requestExit() }

    val density = LocalDensity.current
    // Past the furniture's per-frame set; the gauges' own labels share the cache.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val gaugeRadiusPx = with(density) { GAUGE_RADIUS.toPx() }
    // Pixels per car-local metre off tune's length, so a retune can't change the drawn size.
    val carScalePx = with(density) { CAR_DRAW_DP.toPx() } / (tuning.chassisHalfLen * 2f)
    val gaugeInsetPx = with(density) { 8.dp.toPx() }
    val furnitureColors = MaterialTheme.colorScheme

    GameShell(
        bus = bus,
        viewport = viewport,
        gate = gate,
        senses = feel,
        chrome = chrome,
        spanMinutes = spanMinutes,
        artScalePx = carScalePx,
        settledWidthM = 0f,
        alarmRaised = alarmRaised,
        gameDispatcher = gameDispatcher,
        onFirstFrame = onFirstFrame,
        onBackground = { controls.release() },
        loop = { handOff ->
            val walls = scene.props.obstacles(lowOnly = true, scene.track.map.worldXOf(dropAtMs), DROP_KEEP_OUT_M)
            val world = runCatching { openWorld(scene.track.terrain, tuning, walls) }.getOrNull()
                ?: return@GameShell
            try {
                runGameLoop(
                    scene.track.map.worldXOf(dropAtMs),
                    handOff,
                    scene.track.map.worldXOf(seatAtMs),
                    world, scene.track, bus, camera, zoom, controls, viewport,
                    gate, commands, hud, liveRef, zone, feel,
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
                props = scene.props,
                propArt = propArt,
                simS = f.simS,
                hour = hourAt(scene.track.map, scene.tzOffsetMin, p.camLeftM + p.camWidthM * 0.5f),
                plotTop = p.plotTop,
                plotBottom = p.plotBottom,
            )
            // True scale on both axes. See [drawCar].
            if (f.carShown) {
                // World metres, so it rides the vertical scale.
                translate(top = -f.carLiftM * p.pxPerYM) {
                    // Before the car, so the plume sits behind it, inside the same lift.
                    drawSmoke(art, f, skin, p.camLeftM, p.floorPx, p.pxPerXM, worldY = p.pxPerYM)
                    drawCar(art, f, skin, p.camLeftM, p.floorPx, p.pxPerXM, worldY = p.pxPerYM)
                }
            }
        },
        drawOverlay = { f, p ->
            drawGauges(
                f,
                // Panel, not plot rect: the plot's asymmetric insets sit the pair off-centre.
                centreX = size.width * 0.5f,
                bottomY = p.plotBottom - gaugeInsetPx,
                radius = gaugeRadiusPx,
                measurer = measurer,
                ink = furnitureColors.onSurface,
                accent = furnitureColors.primary,
                warn = furnitureColors.error,
            )
        },
    ) {
        // Inset from the edges so the gesture navigation bar cannot swallow a press.
        PedalZone(
            skin,
            Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 10.dp).size(PEDAL_SIZE),
            brake = true,
        ) { down -> controls.brakeTarget = if (down) 1f else 0f }
        PedalZone(
            skin,
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp).size(PEDAL_SIZE),
            brake = false,
        ) { down -> controls.throttleTarget = if (down) 1f else 0f }

        TerminalCard(
            hud,
            Modifier.align(Alignment.Center),
            onRestart = { haptics.perform(HapticEvent.Tap); commands.reset = true },
            onExit = onExit,
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard run?") },
            confirmButton = { TextButton(onClick = onExit) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep driving") } },
        )
    }
}

@Composable
private fun TerminalCard(hud: HudState, modifier: Modifier, onRestart: () -> Unit, onExit: () -> Unit) {
    val run = hud.value.run
    if (run == RunState.Running) return
    val text = when (run) {
        RunState.Crashed -> "Crashed"
        RunState.Finished -> "Reached now"
        RunState.Running -> return
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onRestart) { Text("Restart") }
                TextButton(onClick = onExit) { Text("Exit") }
            }
        }
    }
}

/** pressed reads only in the draw lambda: a press invalidates the zone's draw, not composition. */
@Composable
private fun PedalZone(
    skin: GameSkin,
    modifier: Modifier,
    brake: Boolean,
    onPressed: (Boolean) -> Unit,
) {
    DisposableEffect(Unit) { onDispose { onPressed(false) } }
    var pressed by remember { mutableStateOf(false) }
    Canvas(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPressed(true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    pressed = false
                    onPressed(false)
                }
            },
    ) {
        val r = size.minDimension * 0.5f
        val c = Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(if (pressed) skin.pedalDown else skin.pedalIdle, r, c)
        drawCircle(skin.pedalInk, r, c, style = Stroke(width = r * 0.06f), alpha = 0.5f)
        val ink = skin.pedalInk
        if (brake) {
            drawRoundRect(
                ink,
                topLeft = Offset(c.x - r * 0.42f, c.y - r * 0.13f),
                size = Size(r * 0.84f, r * 0.26f),
                cornerRadius = CornerRadius(r * 0.13f),
            )
        } else {
            val bar = r * 0.12f
            for (k in -1..1) {
                val y = c.y + k * r * 0.30f
                drawRoundRect(
                    ink,
                    topLeft = Offset(c.x - r * 0.40f, y - bar * 0.5f),
                    size = Size(r * 0.80f, bar),
                    cornerRadius = CornerRadius(bar * 0.5f),
                )
            }
        }
    }
}
