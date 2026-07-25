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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

/** Pedal diameter. The pedals are the only controls and are used blind, so they sit in the lower
 *  corners where thumbs rest; at this size the two never crowd the 220 dp panel yet stay comfortably
 *  above the 48 dp touch-target floor, and both can be held at once. */
private val PEDAL_SIZE = 76.dp

/**
 * How wide the car should READ on the glass — and therefore, now, what the camera zooms to.
 *
 * It used to size the car directly, decoupled from the world scale, which is what let a constant-size
 * marker sit on a variable-scale ground and register at exactly one point (see `drawCar`). It is now the
 * other end of that relationship: [GameViewport.zoomedWidthM] divides the plot width by this to get the
 * span at which the car drawn TRUE SCALE is this wide, and `GameZoom` eases the view onto it. So this is
 * still the one knob for the car's apparent size — it just moves the camera instead of the car, which is
 * the only version of it that keeps both tyres on the curve.
 *
 * Raising it zooms in and shows less of the trace; lowering it zooms out and shrinks the car.
 */
private val CAR_DRAW_DP = 120.dp

/**
 * How far BEFORE the chart's left edge the track is cut, as a MULTIPLE OF THE VISIBLE SPAN.
 *
 * Two things need ground behind the car. The chart's polyline enters the panel from off-screen — its
 * frame holds readings from before the viewport — so a track starting exactly at the viewport edge
 * begins at the first reading INSIDE it and looks lopped off. And the camera seats the car at ~38 % of
 * the width, so once it follows, better than a third of a span must exist behind it.
 *
 * A fixed margin cannot serve either: 45 minutes is ample at a half-hour window and nothing at six
 * hours, which is exactly why the curve stayed cut. One full span of lead covers both at any zoom.
 */
private const val TRACK_LEAD_SPANS = 1f

/** Progress-bar thickness. It lives in the 10 dp top inset, so it has to be slim enough to sit there
 *  without touching the plot's edge. */
private val PROGRESS_H = 5.dp

/** Dial radius. Small enough that the pair sits between the pedals without crowding the track. */
private val GAUGE_RADIUS = 30.dp


/** Hand-off fade, paired BY HAND with the dashboard's chart cross-fade. See `handOff` in [GameStage]. */
private const val HAND_OFF_MS = 220



private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * The hill-climb minigame: an immersive route whose terrain IS the glucose trace.
 *
 * Stateless and callback-driven like every other feature screen — it owns no repository, no navigation
 * and no FFI. `:app` hands down the coverage histogram, a reader for one run's readings, the panel's
 * unit/axis settings, the Rust car tune, a world constructor and the dedicated game dispatcher.
 *
 * **Isolation, structurally.** The solver runs on [gameDispatcher] (`t1dm-game`), never on
 * `t1dm-inference` and never on a `default` worker the §3.6-A alarm engine and the decode path share.
 * The frame path touches Compose snapshot state exactly twice — a `Long` counter read only inside the
 * draw lambda, and a 4 Hz HUD cell — so sixty frames a second invalidate DRAW and recompose nothing.
 * See [runGameLoop] and [CarFrame].
 *
 * **The sensory layer** is procedural end to end and ships no assets: [GameSynth] on an `AudioTrack` at
 * `USAGE_GAME`, and `:core:design`'s continuous haptic mixer on the vibrator. Both are per-composition
 * resources with explicit teardown, and both take their level from the app-wide `ui.haptics` /
 * `AudioManager` settings rather than from a knob of their own. See [GameFeel].
 *
 * [alarmRaised] is **the interlock**: any deterministic alarm OR predictive urgent alert silences the
 * sound, hands audio focus back, and RELEASES the vibrator outright — never ducks it, because
 * `:alerts` announces on that same actuator and a game holding a sustained effect could supersede the
 * buzz meant to wake the user. It does NOT pause. An earlier build froze the world on any raised
 * alarm, which left the game unplayable for the hours a routine high stays up; and confiscating the
 * controls is not this app's posture (advisory only, never actuating). The alarm
 * keeps its own loud, DND-piercing surface; the game simply gets out of its way.
 *
 * IT IS NOT SURFACED, and that is a known gap rather than a decision: the sensory layer just stops, so a
 * player whose glucose has crossed a threshold experiences the game going mute for no stated reason and
 * reasonably reads it as a fault. (This comment claimed the HUD marked it. It never did.)
 * `:app` maps both alarm writers onto this boolean and deliberately reports FALSE under DEATH mode,
 * whose whole contract is that the user has chosen to defeat §3.6's presentation.
 *
 * The global "disable all animations" flag does NOT gate the loop. That flag governs decorative and
 * looping motion (`Motion.kt`); here the motion is the content, exactly as the BG panel's line is, and
 * a snapped game is not a game.
 */
@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    /** The left edge of the chart's viewport: where the TRACK begins, so the curve behind the car is
     *  drawn rather than cut off. */
    trackFromMs: Long,
    /** The instant the user tapped: where the CAR is dropped, somewhere inside the track. */
    dropAtMs: Long,
    /** The panel's threshold bands, so the hypo/hyper tints survive into drive mode. */
    thresholds: AlertThresholds?,
    /** Fired once the track is built and the first frame is drawable. Until then the caller keeps the
     *  chart on screen: swapping earlier shows this composable's loading state, which is the "flash"
     *  between chart and game. */
    onReady: () -> Unit = {},
    /** The panel's LIVE visible span, in minutes — the chart's own viewport, adopted wholesale so the
     *  panel neither zooms nor re-spans when the game starts. */
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
    // NO opaque base. The panel's background is the per-theme backdrop the Scaffold paints behind the
    // whole app, and game mode is a mode of that panel: filling here would hide the very motif the
    // graph shows through, which is exactly what it looked like when it did.
    Box(modifier) {
        when {
            built == null || carTuning == null -> Loading()
            !built.track.isPlayable -> Refusal(onExit)
            else -> key(built) {
                GameStage(
                    scene = built,
                    onFirstFrame = onReady,
                    seatAtMs = trackFromMs,
                    dropAtMs = dropAtMs,
                    thresholds = thresholds,
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

/**
 * The immersive shell around one loaded world.
 *
 * Split out of [GameScreen] so every per-run holder is `remember`-ed against this composable's own
 * lifetime: a new scene enters a new instance (via `key`) and the previous loop, world and buffers go
 * with the old one.
 */
@Composable
private fun GameStage(
    scene: GameScene,
    onFirstFrame: () -> Unit,
    seatAtMs: Long,
    dropAtMs: Long,
    thresholds: AlertThresholds?,
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
    // Per-frame scratch, memoised exactly as GlucoseGraph memoises its corridor and pens: the frame
    // path must allocate nothing, and a Path rewound is a Path reused.
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
    val haptics = rememberT1dmHaptics()
    // Read through rememberUpdatedState: the loop outlives any recomposition that swaps the lambda.
    val firstFrame by rememberUpdatedState(onFirstFrame)

    /**
     * Whether the CALLER has been told to put the chart away. Nothing is drawn here until it has, and the
     * two facts are flipped by the same Main-thread write so they land in ONE recomposition.
     *
     * The handover cannot be atomic on its own: the loop must publish a frame before it can say "I have
     * something to show", and telling the caller costs a hop to Main. Gating on the published frame alone
     * is not enough — that frame's draw already sees a non-zero tick, so the game drew its own copy of the
     * threshold bands while the chart underneath was still drawing them too. Two translucent fills
     * stacked, for the one or two frames until the caller recomposed: the bright flash on the hypo and
     * hyper bands the instant the car was dropped. Pairing the two writes is what makes them exchange
     * cleanly rather than overlap.
     */
    var handedOver by remember { mutableStateOf(false) }

    /**
     * The hand-off fade, and it must be COMPLEMENTARY to the chart's.
     *
     * The dashboard does not swap the chart out; it cross-fades it over [HAND_OFF_MS] once told the game
     * is ready, deliberately, so that everything the chart carries and the game does not — the forecast
     * fan, the curve overlays, its own point styling — dissolves rather than vanishing in a frame. But the
     * two surfaces also draw the SAME furniture, and two translucent fills do not cross-fade, they ADD:
     * with the chart at 1 and the game at 1, the hypo and hyper bands were briefly drawn at twice their
     * opacity and then settled back. That was the flash on the bands.
     *
     * Fading in on the same curve keeps the sum at unity for everything the two share, which is what makes
     * it an exchange rather than an overlap. The duration is coupled to the dashboard's by hand; if that
     * one moves, this must move with it.
     */
    val motionOn = LocalAnimationsEnabled.current
    val handOff by animateFloatAsState(
        targetValue = if (handedOver) 1f else 0f,
        animationSpec = tween(if (motionOn) HAND_OFF_MS else 0),
        label = "gameHandOff",
    )
    // The sensory layer. Both halves are per-composition resources with explicit teardown — the
    // vibrator because a sustained layer must be given back, the AudioTrack because a generator thread
    // outliving its screen is a thread writing silence forever.
    val mixer = rememberHapticMixer()
    val audio = rememberGameAudio()
    val feel = remember(mixer, audio) { GameFeel(audio, mixer) }

    SideEffect { liveRef.tsMs = latestReadingMs }

    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(confirmExit) { gate.set(GameHold.Modal, confirmExit) }

    /**
     * THE ALARM INTERLOCK. A raised alarm silences the sound and hands the actuator back outright —
     * never ducks it. `:alerts` renders an urgent-low on the SAME single vibrator, and the platform has
     * no mixing: whichever writer went last owns it. A game holding a bed here would either be
     * superseded (harmless) or supersede the buzz meant to wake the user (not), and the asymmetry of
     * those outcomes is the whole argument for release over attenuation.
     *
     * It does NOT pause. An earlier build froze the world on any raised alarm, which made the game
     * unplayable for the hours a routine high stays up — and confiscating the controls is not this
     * app's posture anyway (advisory only). The alarm keeps its own loud,
     * DND-piercing surface; the game gets out of its way and says so in the HUD.
     */
    LaunchedEffect(alarmRaised) {
        if (alarmRaised || gate.holds.has(GameHold.Background)) feel.release() else feel.resume()
    }

    // HARD PAUSE the instant the screen stops being RESUMED. The frame clock alone is not enough: it
    // stops on window DETACH, so a dialog or a partially-occluding overlay would leave the world
    // running under it. Releasing the pedals here too, because a pointer that never gets its up event
    // would otherwise resume at full throttle.
    val owner = LocalLifecycleOwner.current
    val alarmNow by rememberUpdatedState(alarmRaised)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { o, _ ->
            val backgrounded = !o.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            gate.set(GameHold.Background, backgrounded)
            if (backgrounded) {
                controls.release()
                // Audio focus goes back too: a game route left on the nav stack must not keep another
                // app ducked, nor keep a generator thread alive behind a locked screen.
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
                    // ONE Main-thread write for both: the caller drops the chart and this Canvas starts
                    // drawing in the same recomposition, so the bands are never drawn twice. See
                    // [handedOver].
                    { withContext(Dispatchers.Main) { firstFrame(); handedOver = true } },
                    scene.track.map.worldXOf(seatAtMs),
                    world, scene.track, bus, camera, zoom, controls, viewport,
                    gate, commands, hud, liveRef, zone, feel,
                )
            } finally {
                // The world is refcounted in Rust and freed through a JVM Cleaner: dropping the
                // reference leaks the heightfield until a GC happens by. Cancellation runs this.
                world.close()
            }
        }
    }

    fun requestExit() {
        val h = hud.value
        if (h.run == RunState.Running && h.distanceM > 1f) confirmExit = true else onExit()
    }

    BackHandler { requestExit() }

    // The graph's own insets, so game mode frames the plot identically. Resolved in composition: a
    // density lookup inside the draw lambda would run sixty times a second for a constant.
    val density = LocalDensity.current
    val leftInsetPx = with(density) { 46.dp.toPx() }
    val rightInsetPx = with(density) { 12.dp.toPx() }
    val topInsetPx = with(density) { 10.dp.toPx() }
    val bottomInsetPx = with(density) { 20.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val gaugeRadiusPx = with(density) { GAUGE_RADIUS.toPx() }
    // Pixels per CAR-LOCAL metre, from the tune's own overall length so a retune cannot silently
    // change the drawn size.
    val carScalePx = with(density) { CAR_DRAW_DP.toPx() } / (tuning.chassisHalfLen * 2f)
    val gaugeInsetPx = with(density) { 8.dp.toPx() }
    val progressThicknessPx = with(density) { PROGRESS_H.toPx() }
    val furnitureColors = MaterialTheme.colorScheme
    // The car's DRAWN size, in pixels per car-local metre. Constant, so it stays legible at any window
    // the user has set; its position remains the solver's own.
    // The camera spans the user's own window (one world metre per minute) and the world's full value
    // height. Written as plain memory, like the pixel sizes, so neither enters the frame path.
    SideEffect {
        viewport.visibleWidthM = spanMinutes * scene.track.map.metresPerMinute
        viewport.worldHeightM = scene.track.map.worldHeight
        // The zoom's destination is derived from these two, on the game thread — see
        // [GameViewport.zoomedWidthM]. Published here because density, insets and the tune are all
        // composition's to know and none of them is the loop's business.
        viewport.carScalePx = carScalePx
        viewport.plotInsetPx = leftInsetPx + rightInsetPx
    }

    // The opening belongs to the LOOP now, not to composition — see `runGameLoop`. It has to: the world
    // zooms before the car appears, and only the loop knows when the span has arrived. An Animatable
    // here could not be kept in step with a hold it cannot observe, and the car spawning mid-zoom was
    // the one frame the zoom exists to avoid — drawn true-scale, a car at the chart's span is a few
    // pixels wide and squashed by the axis anisotropy.
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Read inside the layer block, so the fade invalidates the LAYER and never composition.
                .graphicsLayer { alpha = handOff }
                .onSizeChanged {
                    // Plain memory, so a layout pass never enters the frame path as a recomposition.
                    viewport.widthPx = it.width.toFloat()
                    viewport.heightPx = it.height.toFloat()
                },
        ) {
            // THE draw-phase snapshot read. Its only observer is this draw scope, so the game thread's
            // per-frame write invalidates the draw and nothing else. Read it in composition anywhere —
            // even to build a label — and every frame recomposes instead.
            val frameTick = bus.tick
            // NOTHING until the chart has been handed over, and this is load-bearing rather than tidy.
            //
            // This Canvas is composed and drawing from the moment the scene builds, and there is
            // deliberately no opaque base (game mode is a mode of the panel, so the backdrop shows
            // through). Drawing before the handover therefore painted the game UNDER a live chart: the
            // zeroed frame buffer first (`camWidth` defaults to a 10 m window, magnifying the terrain
            // ~11×), and then, once a frame existed, a second copy of the threshold bands over the
            // chart's own. `runGameLoop` names the first of those in its KDoc; the second was the flash
            // on the hypo and hyper bands. `frameTick` is still read above, and must be — it is what
            // subscribes this draw to the frame bus at all.
            if (!handedOver || frameTick == 0L) return@Canvas
            val f = bus.published

            // The panel keeps its frame while the car drives: same insets as the graph, so the left
            // value axis, both time axes and the grid land exactly where they were a chip-tap ago.
            val plotLeft = leftInsetPx
            val plotRight = size.width - rightInsetPx
            val plotTop = topInsetPx
            val plotBottom = size.height - bottomInsetPx
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            // The panel keeps the GRAPH's own frame: value maps down the height and time across the
            // width, INDEPENDENTLY, exactly as GlucoseGraph does. So the vertical scale is fixed to the
            // world's full value span (the user's configured range) and never follows the car — only
            // time pans. An isotropic camera would have shown ~198 m of a 40 m world at a 6 h window
            // and flattened the terrain to a line.
            val map = scene.track.map
            val pxX = plotW / f.camWidth.coerceAtLeast(1e-3f)
            // NO intro pan. The panel must be identical the frame before and the frame after Drive is
            // tapped; sliding the viewport in was exactly the "visible shift" that broke the illusion.
            // The car's fall is kept — that is the car arriving, not the chart moving.
            val camLeftM = f.camLeft
            // From the FRAME, not from the map: the speed widening opens the value axis too, so the
            // vertical scale changes with it. See [GameZoom.fov].
            val pxY = plotH / f.camHeight.coerceAtLeast(1e-3f)
            // The drop arrives on the frame, already in world metres, so it rides the vertical scale.
            val carLiftPx = f.carLiftM * pxY
            // The vertical camera is a dead-band rescue, zero in the normal case — but it must actually
            // be honoured, or a car above the axis ceiling leaves the panel and never comes back.
            val camBottomM = f.camBottom
            val floorPx = plotBottom + camBottomM * pxY
            val camRightMs = map.tsMsAt(camLeftM + f.camWidth)
            val camLeftMs = map.tsMsAt(camLeftM)
            drawGraphFurniture(
                unit = scene.unit,
                tzOffsetMin = scene.tzOffsetMin,
                plotLeft = plotLeft, plotTop = plotTop, plotRight = plotRight, plotBottom = plotBottom,
                viewStartMs = camLeftMs.toDouble(),
                viewSpanMs = (camRightMs - camLeftMs).toDouble().coerceAtLeast(1.0),
                // The axis follows the vertical camera. It has to: labels pinned to the configured
                // range while the view had scrolled would be describing ground that is not on screen.
                yMin = map.valueAt(camBottomM),
                yMax = map.valueAt(camBottomM + f.camHeight),
                thresholds = thresholds,
                predictedClock = null,
                measurer = measurer,
                cs = furnitureColors,
            )

            // The world is clipped INTO the plot rect, so the car can never drive over the axis
            // labels the way a trace never does.
            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
                translate(left = plotLeft, top = 0f) {
                    drawGameWorld(
                        scene.track, scene.paint, skin,
                        camLeftM, f.camWidth, pxX, floorPx,
                        groundPath, paintPath, chalk,
                        pxPerWorldY = pxY,
                        fillSky = false,
                    )
                    // TRUE scale, both axes: [GameZoom] is what makes that legible. See [drawCar]. Not
                    // drawn at all until the zoom has arrived and the car has been released to fall.
                    if (f.carShown) {
                        translate(top = -carLiftPx) {
                            // Smoke BEFORE the car so the plume sits behind it, and inside the same lift
                            // so it does not stay pinned to the ground while the car is still falling in.
                            drawSmoke(art, f, skin, camLeftM, floorPx, pxX, worldY = pxY)
                            drawCar(art, f, skin, camLeftM, floorPx, pxX, worldY = pxY)
                        }
                    }
                }
            }

            // The run's progress, in the panel's TOP INSET rather than inside the plot: the plot's own top
            // is where the trace's peaks live and where the value axis puts its highest label, and a bar
            // laid over either would be covering the data it is describing.
            drawProgress(
                progress = f.progress,
                left = plotLeft,
                right = plotRight,
                top = ((plotTop - progressThicknessPx) * 0.5f).coerceAtLeast(1f),
                thickness = progressThicknessPx,
                ink = furnitureColors.onSurface,
                accent = furnitureColors.primary,
            )

            // The instrument pair, low and centred between the pedals so neither thumb covers it.
            drawGauges(
                f,
                // Centred on the PANEL, not the plot rect: the plot is inset 46 dp on the left for
                // the value axis and 12 on the right, so centring on it sits the pair visibly right of
                // where the eye expects the middle of the panel to be.
                centreX = size.width * 0.5f,
                bottomY = plotBottom - gaugeInsetPx,
                radius = gaugeRadiusPx,
                measurer = measurer,
                ink = furnitureColors.onSurface,
                accent = furnitureColors.primary,
                warn = furnitureColors.error,
            )
        }

        // Two pedals in the lower corners, translucent so the ground still reads through them. Sized
        // for a thumb at rest rather than a fingertip, and inset from the edges so the gesture
        // navigation bar cannot swallow a press.
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

/** How the run ended, and the two things to do about it. The only chrome the game keeps: everything
 *  else it needs to say, it says with the world. */
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

/**
 * One pedal: a transparent hit target whose pressed state goes straight into [GameControls] — plain
 * memory, so the solver sees the press with no state write at all.
 *
 * The tint has its own `pressed` flag, and it is read ONLY inside the draw lambda below, so a press
 * invalidates this zone's draw and recomposes nothing — the same discipline the frame path holds,
 * applied to the one piece of feedback that has to be immediate rather than at frame cadence.
 */
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
        // A round pedal pad, not a screen-half: it reads as a control you press rather than a region
        // you tap, and it leaves the track visible either side of it.
        val r = size.minDimension * 0.5f
        val c = Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(if (pressed) skin.pedalDown else skin.pedalIdle, r, c)
        drawCircle(skin.pedalInk, r, c, style = Stroke(width = r * 0.06f), alpha = 0.5f)
        // A treadplate for the gas, a bar for the brake — legible without a word of text.
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
