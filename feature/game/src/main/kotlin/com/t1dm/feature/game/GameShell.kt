package com.t1dm.feature.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.game.WorldMap
import com.t1dm.ui.graph.GraphInsets
import com.t1dm.ui.graph.GraphLabelCache
import com.t1dm.ui.graph.PredictedClock
import com.t1dm.ui.graph.drawGraphFurniture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hand-off fade, paired by hand with the dashboard's cross-fade. */
private const val HAND_OFF_MS = 220

/** Fits the top inset's base 10 dp band without touching the plot edge or the clock axis. */
private val PROGRESS_H = 5.dp

/** The alarm interlock's two verbs plus teardown; each game's own feel layer implements them. */
internal interface GameSenses {
    /** An alarm is up, or the screen went away: hand the actuator and the audio focus back. */
    fun release()

    fun resume()

    fun close()
}

/** The graph furniture behind either world; rebuilt only when the scene is. */
internal class GameChrome(
    val map: WorldMap,
    val unit: UnitSpace,
    val tzOffsetMin: Int,
    val thresholds: AlertThresholds?,
    /** Pinned for the run: a forecast arriving mid-run would re-lay the inset under a body. */
    val predictedClock: PredictedClock?,
    val kovatchevF: ((Double) -> Double)?,
)

/** World→screen for one frame, inside the plot's translate; mutated in draw, never allocated. */
internal class WorldProjection {
    var camLeftM = 0f
    var camWidthM = 0f

    /** Anisotropic by design: value maps the height, time the width, independently. */
    var pxPerXM = 0f
    var pxPerYM = 0f

    /** Screen y of world y = 0, already carrying the camera's bottom. */
    var floorPx = 0f

    /** PANEL space, unlike the rest: `drawWorld` runs translated by [plotLeft], the overlay not. */
    var plotLeft = 0f
    var plotRight = 0f
    var plotTop = 0f
    var plotBottom = 0f
}

/** Lifecycle, alarm interlock, hand-off fade, viewport and furniture — all a minigame shares. */
@Composable
internal fun <T : WorldFrame> GameShell(
    bus: FrameBus<T>,
    viewport: GameViewport,
    gate: GamePauseGate,
    senses: GameSenses,
    chrome: GameChrome,
    spanMinutes: Float,
    /** Pixels per world metre the art is authored at, or 0 when [settledWidthM] sets the scale. */
    artScalePx: Float,
    settledWidthM: Float,
    alarmRaised: Boolean,
    gameDispatcher: CoroutineDispatcher,
    onFirstFrame: () -> Unit,
    /** A pointer that never gets its up event must not leave a control pinned. */
    onBackground: () -> Unit,
    loop: suspend (handOff: suspend () -> Unit) -> Unit,
    canvasModifier: Modifier = Modifier,
    drawWorld: DrawScope.(T, WorldProjection) -> Unit,
    drawOverlay: DrawScope.(T, WorldProjection) -> Unit = { _, _ -> },
    content: @Composable BoxScope.() -> Unit,
) {
    // The loop outlives any recomposition that swaps the lambda.
    val firstFrame by rememberUpdatedState(onFirstFrame)

    /** Flipped in the same Main-thread write as the hand-off, so both land in one recomposition. */
    var handedOver by remember { mutableStateOf(false) }

    /** Complementary to the dashboard's cross-fade: translucent fills add, not cross-fade. */
    val motionOn = LocalAnimationsEnabled.current
    val handOff by animateFloatAsState(
        targetValue = if (handedOver) 1f else 0f,
        animationSpec = tween(if (motionOn) HAND_OFF_MS else 0),
        label = "gameHandOff",
    )

    LaunchedEffect(alarmRaised) {
        if (alarmRaised || gate.holds.has(GameHold.Background)) senses.release() else senses.resume()
    }

    // The frame clock stops only on window DETACH, so a dialog leaves the world running under it.
    val owner = LocalLifecycleOwner.current
    val alarmNow by rememberUpdatedState(alarmRaised)
    val backgroundNow by rememberUpdatedState(onBackground)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { o, _ ->
            val backgrounded = !o.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            gate.set(GameHold.Background, backgrounded)
            if (backgrounded) {
                backgroundNow()
                // A game left on the nav stack must not keep another app ducked.
                senses.release()
            } else if (!alarmNow) {
                senses.resume()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            gate.set(GameHold.Background, true)
            senses.close()
        }
    }

    LaunchedEffect(Unit) {
        withContext(gameDispatcher) {
            // ONE Main-thread write for both, or the bands draw twice. See [handedOver].
            loop { withContext(Dispatchers.Main) { firstFrame(); handedOver = true } }
        }
    }

    // In composition, never in the draw lambda: sixty density lookups a second for a constant.
    val density = LocalDensity.current
    val hasClock = chrome.predictedClock != null
    val leftInsetPx = with(density) { GraphInsets.Left.toPx() }
    val rightInsetPx = with(density) { GraphInsets.Right.toPx() }
    val topInsetPx = with(density) { GraphInsets.top(hasClock).toPx() }
    val bottomInsetPx = with(density) { GraphInsets.Bottom.toPx() }
    val modelAxisPx = with(density) { if (hasClock) GraphInsets.ModelAxis.toPx() else 0f }
    // Past the furniture's per-frame set (>20 strings); every miss is a text layout at 60 Hz.
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelCache = remember { GraphLabelCache() }
    val progressThicknessPx = with(density) { PROGRESS_H.toPx() }
    val furnitureColors = MaterialTheme.colorScheme
    val projection = remember { WorldProjection() }
    // Plain memory, so neither enters the frame path.
    SideEffect {
        viewport.visibleWidthM = spanMinutes * chrome.map.metresPerMinute
        viewport.worldHeightM = chrome.map.worldHeight
        viewport.carScalePx = artScalePx
        viewport.settledWidthM = settledWidthM
        viewport.plotInsetPx = leftInsetPx + rightInsetPx
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            canvasModifier
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
            // Nothing until hand-off; no opaque base until then; frameTick read subscribes to bus.
            if (!handedOver || frameTick == 0L) return@Canvas
            val f = bus.published

            val plotLeft = leftInsetPx
            val plotRight = size.width - rightInsetPx
            val plotTop = topInsetPx
            val plotBottom = size.height - bottomInsetPx
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            val map = chrome.map
            val pxX = plotW / f.camWidth.coerceAtLeast(1e-3f)
            // From the frame, not the map: speed widening opens the value axis too (GameZoom.fov).
            val pxY = plotH / f.camHeight.coerceAtLeast(1e-3f)
            // Dead-band rescue, zero normally; unhonoured, a body above the ceiling stays gone.
            val camBottomM = f.camBottom
            val camLeftMs = map.tsMsAt(f.camLeft)
            val camRightMs = map.tsMsAt(f.camLeft + f.camWidth)
            drawGraphFurniture(
                unit = chrome.unit,
                kovatchevF = chrome.kovatchevF,
                tzOffsetMin = chrome.tzOffsetMin,
                plotLeft = plotLeft, plotTop = plotTop, plotRight = plotRight, plotBottom = plotBottom,
                viewStartMs = camLeftMs.toDouble(),
                viewSpanMs = (camRightMs - camLeftMs).toDouble().coerceAtLeast(1.0),
                yMin = map.valueAt(camBottomM),
                yMax = map.valueAt(camBottomM + f.camHeight),
                thresholds = chrome.thresholds,
                predictedClock = chrome.predictedClock,
                measurer = measurer,
                cs = furnitureColors,
                labels = labelCache,
            )

            projection.camLeftM = f.camLeft
            projection.camWidthM = f.camWidth
            projection.pxPerXM = pxX
            projection.pxPerYM = pxY
            projection.floorPx = plotBottom + camBottomM * pxY
            projection.plotLeft = plotLeft
            projection.plotRight = plotRight
            projection.plotTop = plotTop
            projection.plotBottom = plotBottom

            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
                translate(left = plotLeft, top = 0f) { drawWorld(f, projection) }
            }

            // In the top inset, not the plot: the plot's top holds the peaks and the highest label.
            drawProgress(
                progress = f.progress,
                left = plotLeft,
                right = plotRight,
                top = ((plotTop - modelAxisPx - progressThicknessPx) * 0.5f).coerceAtLeast(1f),
                thickness = progressThicknessPx,
                ink = furnitureColors.onSurface,
                accent = furnitureColors.primary,
            )

            drawOverlay(f, projection)
        }

        content()
    }
}

@Composable
internal fun GameLoading() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun GameRefusal(title: String, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onExit,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onExit) { Text("Back") } },
    )
}
