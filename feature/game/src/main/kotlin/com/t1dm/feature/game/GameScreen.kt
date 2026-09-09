package com.t1dm.feature.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.rememberTextMeasurer
import com.t1dm.ui.graph.GraphLabelCache
import com.t1dm.ui.graph.drawGraphFurniture
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1dm.core.common.GameWorld
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.rememberHapticMixer
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CarTuning
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.RunState
import com.t1dm.core.model.TerrainSpec
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.ChalkPens
import com.t1dm.ui.graph.GraphInsets
import com.t1dm.ui.graph.PredictedClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

/** Above the 48 dp touch-target floor; two fit without crowding the 220 dp panel. */
private val PEDAL_SIZE = 76.dp

/** Apparent car width; GameViewport.zoomedWidthM picks the span a true-scale car is this wide. */
private val CAR_DRAW_DP = 120.dp

/** Ground cut before chart's left edge, as a multiple of visible span; fixed margin fails at 6h. */
private const val TRACK_LEAD_SPANS = 1f

/** Fits the top inset's base 10 dp band without touching the plot edge or the clock axis. */
private val PROGRESS_H = 5.dp

/** The pair must fit between the pedals. */
private val GAUGE_RADIUS = 30.dp


/** Hand-off fade, paired BY HAND with dashboard cross-fade; see handOff in GameStage. */
private const val HAND_OFF_MS = 220



private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

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
    readingsFrom: suspend (fromMs: Long) -> List<CgmReading>,
    openWorld: (TerrainSpec, CarTuning) -> GameWorld,
    gameDispatcher: CoroutineDispatcher,
    alarmRaised: Boolean,
    onExit: () -> Unit,
) {
    val scene by produceState<GameScene?>(null, trackFromMs, spanMinutes, unit) {
        value = null
        val leadMs = (spanMinutes.toDouble() * 60_000.0 * TRACK_LEAD_SPANS).toLong()
        value = loadGameScene(
            readingsFrom(trackFromMs - leadMs),
            paintStrokes, unit, kovatchevF, rangeMinMgdl, rangeMaxMgdl,
        )
    }

    val built = scene
    // No opaque base: game mode is a mode of the panel, and the backdrop must show through.
    Box(modifier) {
        when {
            built == null || carTuning == null -> Loading()
            !built.track.isPlayable -> Refusal(onExit)
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
    openWorld: (TerrainSpec, CarTuning) -> GameWorld,
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
    // The loop outlives any recomposition that swaps the lambda.
    val firstFrame by rememberUpdatedState(onFirstFrame)

    /** Flipped in same Main-thread write as hand-off; frame-alone gating draws bands twice. */
    var handedOver by remember { mutableStateOf(false) }

    /** Complementary to dashboard's cross-fade: translucent fills add; duration coupled by hand. */
    val motionOn = LocalAnimationsEnabled.current
    val handOff by animateFloatAsState(
        targetValue = if (handedOver) 1f else 0f,
        animationSpec = tween(if (motionOn) HAND_OFF_MS else 0),
        label = "gameHandOff",
    )
    // Explicit teardown: a generator thread outliving its screen writes silence forever.
    val mixer = rememberHapticMixer()
    val audio = rememberGameAudio()
    val feel = remember(mixer, audio) { GameFeel(audio, mixer) }

    SideEffect { liveRef.tsMs = latestReadingMs }

    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(confirmExit) { gate.set(GameHold.Modal, confirmExit) }

    LaunchedEffect(alarmRaised) {
        if (alarmRaised || gate.holds.has(GameHold.Background)) feel.release() else feel.resume()
    }

    // Frame clock stops only on DETACH, so a dialog leaves the world running; pedals released too.
    val owner = LocalLifecycleOwner.current
    val alarmNow by rememberUpdatedState(alarmRaised)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { o, _ ->
            val backgrounded = !o.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            gate.set(GameHold.Background, backgrounded)
            if (backgrounded) {
                controls.release()
                // A game route left on the nav stack must not keep another app ducked.
                feel.release()
            } else if (!alarmNow) {
                feel.resume()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            gate.set(GameHold.Background, true)
            feel.close()
        }
    }

    LaunchedEffect(Unit) {
        withContext(gameDispatcher) {
            val world = runCatching { openWorld(scene.track.terrain, tuning) }.getOrNull()
                ?: return@withContext
            try {
                runGameLoop(
                    scene.track.map.worldXOf(dropAtMs),
                    // ONE Main-thread write for both, or the bands draw twice. See [handedOver].
                    { withContext(Dispatchers.Main) { firstFrame(); handedOver = true } },
                    scene.track.map.worldXOf(seatAtMs),
                    world, scene.track, bus, camera, zoom, controls, viewport,
                    gate, commands, hud, liveRef, zone, feel,
                )
            } finally {
                // Refcounted in Rust, freed by a JVM Cleaner: dropping it leaks the heightfield.
                world.close()
            }
        }
    }

    fun requestExit() {
        val h = hud.value
        if (h.run == RunState.Running && h.distanceM > 1f) confirmExit = true else onExit()
    }

    BackHandler { requestExit() }

    // In composition, never in the draw lambda: sixty density lookups a second for a constant.
    val density = LocalDensity.current
    val leftInsetPx = with(density) { GraphInsets.Left.toPx() }
    val rightInsetPx = with(density) { GraphInsets.Right.toPx() }
    val topInsetPx = with(density) { GraphInsets.top(runClock != null).toPx() }
    val bottomInsetPx = with(density) { GraphInsets.Bottom.toPx() }
    val modelAxisPx = with(density) { if (runClock != null) GraphInsets.ModelAxis.toPx() else 0f }
    // Past furniture's per-frame working set (>20 strings); every miss is a text layout at 60 Hz.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelCache = remember { GraphLabelCache() }
    val gaugeRadiusPx = with(density) { GAUGE_RADIUS.toPx() }
    // Pixels per car-local metre off tune's length, so a retune can't change the drawn size.
    val carScalePx = with(density) { CAR_DRAW_DP.toPx() } / (tuning.chassisHalfLen * 2f)
    val gaugeInsetPx = with(density) { 8.dp.toPx() }
    val progressThicknessPx = with(density) { PROGRESS_H.toPx() }
    val furnitureColors = MaterialTheme.colorScheme
    // Plain memory, so neither enters the frame path.
    SideEffect {
        viewport.visibleWidthM = spanMinutes * scene.track.map.metresPerMinute
        viewport.worldHeightM = scene.track.map.worldHeight
        viewport.carScalePx = carScalePx
        viewport.plotInsetPx = leftInsetPx + rightInsetPx
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Read inside the layer block: the fade invalidates the LAYER, not composition.
                .graphicsLayer { alpha = handOff }
                .onSizeChanged {
                    // Plain memory, so a layout pass never enters the frame path.
                    viewport.widthPx = it.width.toFloat()
                    viewport.heightPx = it.height.toFloat()
                },
        ) {
            // Draw-phase read only: read the tick in composition and every frame recomposes.
            val frameTick = bus.tick
            // Nothing until hand-off: no opaque base; earlier frame paints under; tick subscribes.
            if (!handedOver || frameTick == 0L) return@Canvas
            val f = bus.published

            val plotLeft = leftInsetPx
            val plotRight = size.width - rightInsetPx
            val plotTop = topInsetPx
            val plotBottom = size.height - bottomInsetPx
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            // Anisotropic by design: value maps down height, time across width, independently.
            val map = scene.track.map
            val pxX = plotW / f.camWidth.coerceAtLeast(1e-3f)
            // No intro pan: the panel must be identical the frame before and after Drive is tapped.
            val camLeftM = f.camLeft
            // From the frame, not the map: speed widening opens the value axis too (GameZoom.fov).
            val pxY = plotH / f.camHeight.coerceAtLeast(1e-3f)
            // World metres, so it rides the vertical scale.
            val carLiftPx = f.carLiftM * pxY
            // Dead-band rescue, zero normally; unhonoured, a car above the ceiling stays gone.
            val camBottomM = f.camBottom
            val floorPx = plotBottom + camBottomM * pxY
            val camRightMs = map.tsMsAt(camLeftM + f.camWidth)
            val camLeftMs = map.tsMsAt(camLeftM)
            drawGraphFurniture(
                unit = scene.unit,
                kovatchevF = kovatchevF,
                tzOffsetMin = scene.tzOffsetMin,
                plotLeft = plotLeft, plotTop = plotTop, plotRight = plotRight, plotBottom = plotBottom,
                viewStartMs = camLeftMs.toDouble(),
                viewSpanMs = (camRightMs - camLeftMs).toDouble().coerceAtLeast(1.0),
                yMin = map.valueAt(camBottomM),
                yMax = map.valueAt(camBottomM + f.camHeight),
                thresholds = thresholds,
                predictedClock = runClock,
                measurer = measurer,
                cs = furnitureColors,
                labels = labelCache,
            )

            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
                translate(left = plotLeft, top = 0f) {
                    drawGameWorld(
                        scene.track, scene.paint, skin,
                        camLeftM, f.camWidth, pxX, floorPx,
                        groundPath, paintPath, chalk,
                        pxPerWorldY = pxY,
                        fillSky = false,
                    )
                    // True scale on both axes. See [drawCar].
                    if (f.carShown) {
                        translate(top = -carLiftPx) {
                            // Before the car, so the plume sits behind it, inside the same lift.
                            drawSmoke(art, f, skin, camLeftM, floorPx, pxX, worldY = pxY)
                            drawCar(art, f, skin, camLeftM, floorPx, pxX, worldY = pxY)
                        }
                    }
                }
            }

            // In the top inset, not the plot: plot's top holds the peaks and the highest label.
            drawProgress(
                progress = f.progress,
                left = plotLeft,
                right = plotRight,
                top = ((plotTop - modelAxisPx - progressThicknessPx) * 0.5f).coerceAtLeast(1f),
                thickness = progressThicknessPx,
                ink = furnitureColors.onSurface,
                accent = furnitureColors.primary,
            )

            drawGauges(
                f,
                // Panel, not plot rect: the plot's asymmetric insets sit the pair off-centre.
                centreX = size.width * 0.5f,
                bottomY = plotBottom - gaugeInsetPx,
                radius = gaugeRadiusPx,
                measurer = measurer,
                ink = furnitureColors.onSurface,
                accent = furnitureColors.primary,
                warn = furnitureColors.error,
            )
        }

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

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Refusal(onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onExit,
        title = { Text("Not enough ground to drive") },
        confirmButton = { TextButton(onClick = onExit) { Text("Back") } },
    )
}
