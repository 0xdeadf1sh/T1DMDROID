package com.t1dm.feature.twin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.util.Size as PxSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.rememberT1dmHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private enum class ScanPhase { SEARCH, CAPTURE }

/** Front camera → checks → baked frontal face; [onCaptured] gets null if baking failed. */
@Composable
fun TwinScanScreen(onCaptured: (face: ByteArray?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera off", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
        return
    }

    val latest = remember { AtomicReference<FaceFrame?>(null) }
    val haptics = rememberT1dmHaptics()
    val done by rememberUpdatedState(onCaptured)
    var phase by remember { mutableStateOf(ScanPhase.SEARCH) }
    val lock = remember { mutableFloatStateOf(0f) }
    val frame = remember { mutableLongStateOf(0L) }
    val faceSince = remember { mutableLongStateOf(0L) }
    val modelFailed = remember { AtomicBoolean(false) }
    var frozen by remember { mutableStateOf<FaceFrame?>(null) }
    var capturedNs by remember { mutableLongStateOf(0L) }
    val keepPixels = remember { AtomicBoolean(false) }
    var baked by remember { mutableStateOf(false) }
    var face by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var fired = false
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                frame.longValue = now
                when (phase) {
                    ScanPhase.SEARCH -> {
                        val f = latest.get()
                        if (f == null) faceSince.longValue = 0L else if (faceSince.longValue == 0L) faceSince.longValue = now
                        val steady = verdict(f, modelFailed.get()).ready
                        lock.floatValue = (lock.floatValue + if (steady) dt / LOCK_S else -dt).coerceIn(0f, 1f)
                        keepPixels.set(lock.floatValue > 0f)
                        if (lock.floatValue >= 1f && f?.pixels != null) {
                            keepPixels.set(false)
                            frozen = f
                            capturedNs = now
                            phase = ScanPhase.CAPTURE
                            haptics.perform(HapticEvent.Tap)
                            scope.launch {
                                face = withContext(Dispatchers.Default) {
                                    runCatching { bakeFace(context, listOf(f)) }.onFailure { Log.e(TAG, "face bake", it) }.getOrNull()
                                }
                                baked = true
                            }
                        }
                    }
                    ScanPhase.CAPTURE -> if (!fired && baked && (now - capturedNs) / 1e9f > STAGES.size * STAGE_S + 0.6f) {
                        fired = true
                        haptics.perform(HapticEvent.Tap)
                        done(face)
                    }
                }
            }
        }
    }

    val measurer = rememberTextMeasurer(cacheSize = 48)
    val cs = MaterialTheme.colorScheme
    val hud = HudStyle(
        line = cs.primary,
        accent = cs.secondary,
        ok = LocalT1dmSemantics.current.inRange,
        bad = cs.error,
        bg = cs.background,
        small = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant),
        big = MaterialTheme.typography.titleMedium.copy(color = cs.primary),
        mono = MaterialTheme.typography.labelMedium.copy(color = cs.primary),
    )
    Box(modifier.fillMaxSize()) {
        if (phase != ScanPhase.CAPTURE) CameraFeed(latest, modelFailed, keepPixels, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val now = frame.longValue
            val t = now / 1e9f
            when (phase) {
                ScanPhase.SEARCH -> {
                    val f = latest.get()
                    val age = if (faceSince.longValue == 0L) 0f else (now - faceSince.longValue) / 1e9f
                    drawSearch(hud, measurer, f, verdict(f, modelFailed.get()), lock.floatValue, t, age)
                }
                ScanPhase.CAPTURE -> frozen?.let { drawCapture(hud, measurer, it, (now - capturedNs) / 1e9f) }
            }
        }
    }
}

/** The locked frontal shot → face texture as lossy WebP with alpha. */
private fun bakeFace(context: Context, frames: List<FaceFrame>): ByteArray {
    val body = loadBody(context)
    val shots = frames.mapNotNull { f -> f.pixels?.let { FaceShot(it, f.width, f.height, f.points) } }
    val argb = FaceBake(body.canonUv, body.canonTris).bake(shots)
    val bitmap = Bitmap.createBitmap(argb, FaceBake.SIZE, FaceBake.SIZE, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    }
}

@Composable
private fun CameraFeed(
    latest: AtomicReference<FaceFrame?>,
    modelFailed: AtomicBoolean,
    keepPixels: AtomicBoolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        var tracker: FaceTracker? = null
        executor.execute {
            tracker = runCatching { FaceTracker(context, latest, keepPixels) }
                .onFailure {
                    Log.e(TAG, "tracker init", it)
                    modelFailed.set(true)
                }
                .getOrNull()
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var cases: Array<UseCase> = emptyArray()
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val p = future.get()
            provider = p
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(PxSize(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { a -> a.setAnalyzer(executor) { proxy -> tracker?.analyze(proxy) ?: proxy.close() } }
            cases = arrayOf(preview, analysis)
            runCatching { p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, *cases) }
                .onFailure { Log.e(TAG, "camera bind", it) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbind(*cases)
            executor.execute { tracker?.close() }
            executor.shutdown()
            latest.set(null)
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private class HudStyle(
    val line: Color,
    val accent: Color,
    val ok: Color,
    val bad: Color,
    val bg: Color,
    val small: TextStyle,
    val big: TextStyle,
    val mono: TextStyle,
)

/** Front camera preview is mirrored, the landmarks are not: x flips. Matches FILL_CENTER. */
private class FaceMap(f: FaceFrame, view: Size) {
    private val s = maxOf(view.width / f.width, view.height / f.height)
    private val ox = (view.width - f.width * s) / 2
    private val oy = (view.height - f.height * s) / 2
    private val w = f.width * s
    private val h = f.height * s
    fun x(f: FaceFrame, i: Int) = (1f - f.x(i)) * w + ox
    fun y(f: FaceFrame, i: Int) = f.y(i) * h + oy
    fun bounds(f: FaceFrame): Rect {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until f.count) {
            val px = x(f, i)
            val py = y(f, i)
            if (px < l) l = px
            if (px > r) r = px
            if (py < t) t = py
            if (py > b) b = py
        }
        return Rect(l, t, r, b)
    }
}

private enum class Check(val label: String) {
    FACE("Face found"),
    DISTANCE("Distance"),
    LIGHT("Light"),
    VIEW("Nothing covering"),
    FOCUS("Sharp"),
    ANGLE("Facing camera"),
}

private enum class CheckState { OK, FAIL, WAIT }

/** Checks run in order; the first failure names the prompt and the later ones wait on it. */
private class Verdict(
    val states: Map<Check, CheckState>,
    val failed: Check?,
    val prompt: String,
    val meter: Meter?,
    val issue: FaceIssue?,
) {
    val ready: Boolean get() = states.values.all { it == CheckState.OK }
}

private fun verdict(f: FaceFrame?, modelFailed: Boolean): Verdict {
    val q = f?.metrics
    val issue = q?.issue()
    val states = LinkedHashMap<Check, CheckState>()
    var failed: Check? = null
    var prompt = "Hold still"
    var meter: Meter? = null
    fun check(c: Check, ok: Boolean?, why: String, m: Meter?) {
        val state = when {
            failed != null || ok == null -> CheckState.WAIT
            ok -> CheckState.OK
            else -> CheckState.FAIL
        }
        states[c] = state
        if (state == CheckState.FAIL) {
            failed = c
            prompt = why
            meter = m
        }
    }
    val tilt = f?.let { maxOf(abs(it.yawDeg), abs(it.pitchDeg)) }
    fun issueIn(g: FaceIssue.Group) = issue?.takeIf { it.group == g }
    check(Check.FACE, f != null, if (modelFailed) "Face model failed to load" else "No face · look at the screen", null)
    check(
        Check.DISTANCE, f?.let { it.widthFraction() > MIN_FACE_WIDTH }, "Move closer",
        f?.let { Meter(it.widthFraction(), MIN_FACE_WIDTH, 0.6f, higherIsBetter = true) },
    )
    for ((c, g) in listOf(Check.LIGHT to FaceIssue.Group.LIGHT, Check.VIEW to FaceIssue.Group.VIEW, Check.FOCUS to FaceIssue.Group.FOCUS)) {
        val bad = issueIn(g)
        check(c, q?.let { bad == null }, bad?.prompt.orEmpty(), bad?.let { q?.meter(it) })
    }
    check(
        Check.ANGLE, tilt?.let { it < MAX_TILT_DEG }, "Look straight at the camera",
        tilt?.let { Meter(it, MAX_TILT_DEG, 45f, higherIsBetter = false) },
    )
    return Verdict(states, failed, prompt, meter, issue.takeIf { failed in IMAGE_CHECKS })
}

private val IMAGE_CHECKS = setOf(Check.LIGHT, Check.VIEW, Check.FOCUS)

/** Landmarks the [issue] points at, or null when it concerns the whole face. */
private fun hotRegion(issue: FaceIssue, f: FaceFrame): ((Int) -> Boolean)? {
    val h = f.y(FaceFrame.CHIN) - f.y(FaceFrame.FOREHEAD)
    val bridge = f.y(FaceQuality.BRIDGE)
    val noseX = f.x(FaceFrame.NOSE)
    return when (issue) {
        FaceIssue.GLASSES -> { i -> abs(f.y(i) - bridge) < 0.1f * h }
        FaceIssue.COVERED -> { i -> f.y(i) > f.y(FaceFrame.NOSE) + 0.03f * h }
        FaceIssue.FOREHEAD -> { i -> f.y(i) < bridge - 0.1f * h }
        FaceIssue.UNEVEN -> {
            val side = if ((f.metrics?.sideBalance ?: 0f) < 0f) FaceQuality.CHEEK_IN_L else FaceQuality.CHEEK_IN_R
            val sign = f.x(side) - noseX
            { i -> (f.x(i) - noseX) * sign > 0f }
        }
        else -> null
    }
}

/** Stable 0..1 per edge pair, so the mesh fills in scattered rather than in index order. */
private fun edgeRank(pair: Int): Float = ((pair * -1640531535) ushr 8 and 0xFFFF) / 65536f

private fun DrawScope.drawSearch(s: HudStyle, m: TextMeasurer, f: FaceFrame?, v: Verdict, lock: Float, t: Float, age: Float) {
    drawRect(s.bg.copy(alpha = 0.35f))
    drawScanlines(s)
    val map = f?.let { FaceMap(it, size) }
    val box = map?.bounds(f)?.inflate(size.width * 0.06f)
        ?: Rect(Offset(size.width / 2, size.height * 0.42f), size.width * 0.34f)
    val tight = 1f - 0.08f * lock
    val frameColor = when {
        v.ready -> s.ok
        f != null -> s.bad
        else -> s.line
    }
    drawBrackets(frameColor, box.deflate(box.width * (1f - tight) / 2), 22.dp.toPx())

    if (f != null && map != null) {
        val sweep = box.top + box.height * lock
        val reveal = (age / REVEAL_S).coerceIn(0f, 1f)
        val hot = v.issue?.let { hotRegion(it, f) }
        val wholeFaceBad = v.issue != null && hot == null
        val calm = Path()
        val bad = Path()
        val front = Path()
        val e = FaceMesh.tesselation
        var k = 0
        while (k < e.size) {
            val a = e[k]
            val b = e[k + 1]
            val rank = edgeRank(k / 2)
            k += 2
            if (rank > reveal) continue
            val path = when {
                reveal < 1f && rank > reveal - 0.15f -> front
                wholeFaceBad || (hot != null && hot(a) && hot(b)) -> bad
                else -> calm
            }
            path.moveTo(map.x(f, a), map.y(f, a))
            path.lineTo(map.x(f, b), map.y(f, b))
        }
        val pulse = 0.5f + 0.5f * sin(t * 6f).let { it * it }
        drawPath(calm, (if (v.ready) s.ok else s.line).copy(alpha = 0.3f + 0.4f * lock), style = Stroke(0.8.dp.toPx()))
        drawPath(bad, s.bad.copy(alpha = pulse), style = Stroke(1.4.dp.toPx()))
        drawPath(front, s.accent, style = Stroke(1.6.dp.toPx()))
        val contour = Path()
        val c = FaceMesh.contours
        k = 0
        while (k < c.size) {
            contour.moveTo(map.x(f, c[k]), map.y(f, c[k]))
            contour.lineTo(map.x(f, c[k + 1]), map.y(f, c[k + 1]))
            k += 2
        }
        drawPath(contour, s.accent.copy(alpha = 0.85f * reveal), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
        if (lock > 0f) {
            drawLine(s.line, Offset(box.left, sweep), Offset(box.right, sweep), 2.dp.toPx())
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, s.line.copy(alpha = 0.18f)), sweep - 40.dp.toPx(), sweep),
                Offset(box.left, sweep - 40.dp.toPx()), Size(box.width, 40.dp.toPx()),
            )
        }
    } else {
        val y = box.top + box.height * (0.5f + 0.5f * kotlin.math.sin(t * 2.2f))
        drawLine(s.line.copy(alpha = 0.6f), Offset(box.left, y), Offset(box.right, y), 1.5.dp.toPx())
    }

    val pad = 16.dp.toPx()
    drawText(m.measure("FACE SCAN", s.small), topLeft = Offset(pad, pad))
    val promptColor = when {
        v.ready -> s.ok
        f == null -> s.line
        else -> s.bad
    }
    val prompt = m.measure(
        if (v.ready) "Hold still · %d%%".format(Locale.ROOT, (lock * 100).toInt()) else v.prompt,
        s.big.copy(color = promptColor),
        constraints = Constraints(maxWidth = (size.width - pad * 2).toInt()),
    )
    var y = pad + 16.dp.toPx()
    drawText(prompt, topLeft = Offset(pad, y))
    y += prompt.size.height + 8.dp.toPx()
    v.meter?.let { drawMeter(s, m, it, pad, y, size.width * 0.42f) }

    val barW = size.width - pad * 2
    val barY = size.height - pad - 4.dp.toPx()
    val rows = Check.entries.map { c ->
        val state = v.states[c] ?: CheckState.WAIT
        val (mark, color) = when (state) {
            CheckState.OK -> "✓" to s.ok
            CheckState.FAIL -> "✕" to s.bad
            CheckState.WAIT -> "–" to s.small.color.copy(alpha = 0.6f)
        }
        m.measure("$mark  ${c.label}", s.mono.copy(color = color))
    }
    var ry = barY - 10.dp.toPx() - rows.sumOf { it.size.height }
    for (r in rows) {
        drawText(r, topLeft = Offset(pad, ry))
        ry += r.size.height
    }
    drawLine(s.line.copy(alpha = 0.2f), Offset(pad, barY), Offset(pad + barW, barY), 3.dp.toPx())
    drawLine(s.ok, Offset(pad, barY), Offset(pad + barW * lock, barY), 3.dp.toPx())
}

/** Bar 0..max: fill at the value, a tick at the limit, then "value · need ≥ limit". */
private fun DrawScope.drawMeter(s: HudStyle, m: TextMeasurer, meter: Meter, x: Float, y: Float, w: Float) {
    val color = if (meter.passes) s.ok else s.bad
    val cy = y + 6.dp.toPx()
    val fill = (meter.value / meter.max).coerceIn(0f, 1f)
    val tick = x + w * (meter.limit / meter.max).coerceIn(0f, 1f)
    drawLine(s.line.copy(alpha = 0.25f), Offset(x, cy), Offset(x + w, cy), 6.dp.toPx(), StrokeCap.Round)
    drawLine(color, Offset(x, cy), Offset(x + w * fill, cy), 6.dp.toPx(), StrokeCap.Round)
    drawLine(s.big.color, Offset(tick, cy - 8.dp.toPx()), Offset(tick, cy + 8.dp.toPx()), 2.dp.toPx())
    fun fmt(v: Float) = if (meter.max <= 1f) "%.2f".format(Locale.ROOT, v) else "%.0f".format(Locale.ROOT, v)
    val label = m.measure(
        "${fmt(meter.value)} · need ${if (meter.higherIsBetter) "≥" else "≤"} ${fmt(meter.limit)}",
        s.mono.copy(color = color),
    )
    drawText(label, topLeft = Offset(x + w + 10.dp.toPx(), cy - label.size.height / 2))
}

private fun DrawScope.drawCapture(s: HudStyle, m: TextMeasurer, f: FaceFrame, t: Float) {
    drawRect(s.bg)
    drawScanlines(s)
    val map = FaceMap(f, size)
    val box = map.bounds(f)
    val cx = box.center.x
    val cy = box.center.y
    val burst = (t / 1.6f).coerceIn(0f, 1f)
    val dots = 1f - burst
    val r = 1.4.dp.toPx()
    for (i in 0 until f.count) {
        val px = map.x(f, i)
        val py = map.y(f, i)
        val rnd = Random(i)
        val drift = burst * burst * size.height * (0.15f + rnd.nextFloat() * 0.5f)
        val dx = (px - cx) / box.width
        val x = px + dx * drift
        val y = py - drift
        drawCircle(s.line.copy(alpha = (0.3f + 0.7f * dots) * (1f - burst * 0.8f)), r, Offset(x, y))
    }

    val pad = 16.dp.toPx()
    val seed = (t * 12).toInt()
    val hex = (0 until 6).joinToString("\n") { row ->
        val rnd = Random(seed * 31 + row)
        (0 until 8).joinToString(" ") { "%04x".format(rnd.nextInt(0x10000)) }
    }
    val hexL = m.measure(hex, s.small)
    drawText(hexL, topLeft = Offset(pad, size.height - pad - hexL.size.height), alpha = 0.6f)

    var y = size.height * 0.62f
    for ((i, stage) in STAGES.withIndex()) {
        val start = i * STAGE_S
        if (t < start) break
        val ok = t > start + STAGE_S * 0.8f
        val l = m.measure((if (ok) "✓ " else "▸ ") + stage, if (ok) s.mono else s.big.copy(fontSize = s.mono.fontSize))
        drawText(l, topLeft = Offset(pad, y))
        y += l.size.height + 4.dp.toPx()
    }
    drawText(m.measure("FACE SCAN", s.small), topLeft = Offset(pad, pad))
    drawText(m.measure("Mock pipeline · nothing leaves the phone", s.small), topLeft = Offset(pad, pad + 16.dp.toPx()))
}

private fun DrawScope.drawScanlines(s: HudStyle) {
    val pitch = 4.dp.toPx()
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent, 0.5f to Color.Transparent,
            0.5f to s.bg.copy(alpha = 0.25f), 1f to s.bg.copy(alpha = 0.25f),
            startY = 0f, endY = pitch, tileMode = TileMode.Repeated,
        ),
    )
}

private fun DrawScope.drawBrackets(c: Color, r: Rect, len: Float) {
    val w = 2.dp.toPx()
    fun corner(x: Float, y: Float, sx: Float, sy: Float) {
        drawLine(c, Offset(x, y), Offset(x + sx * len, y), w)
        drawLine(c, Offset(x, y), Offset(x, y + sy * len), w)
    }
    corner(r.left, r.top, 1f, 1f)
    corner(r.right, r.top, -1f, 1f)
    corner(r.left, r.bottom, 1f, -1f)
    corner(r.right, r.bottom, -1f, -1f)
}

private val STAGES = listOf(
    "Mesh 478 pts",
    "Features · mock",
    "Encrypt · mock",
    "Server · mock",
    "Decrypt · mock",
)
private const val STAGE_S = 0.55f
private const val LOCK_S = 1.6f
private const val REVEAL_S = 0.5f
private const val WEBP_QUALITY = 90
private const val MIN_FACE_WIDTH = 0.28f
private const val MAX_TILT_DEG = 15f
private const val TAG = "TwinScan"
