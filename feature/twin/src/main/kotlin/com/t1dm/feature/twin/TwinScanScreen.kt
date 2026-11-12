package com.t1dm.feature.twin

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.random.Random

private enum class ScanPhase { SEARCH, CAPTURE }

/** Front camera → face mesh → staged hand-off; [onCaptured] fires once the stages finish. */
@Composable
fun TwinScanScreen(onCaptured: () -> Unit, modifier: Modifier = Modifier) {
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
    var frozen by remember { mutableStateOf<FaceFrame?>(null) }
    var capturedNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var fired = false
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                frame.longValue = now
                if (phase == ScanPhase.SEARCH) {
                    val f = latest.get()
                    val steady = f != null && f.widthFraction() > MIN_FACE_WIDTH &&
                        abs(f.yawDeg) < MAX_TILT_DEG && abs(f.pitchDeg) < MAX_TILT_DEG
                    lock.floatValue = (lock.floatValue + if (steady) dt / LOCK_S else -dt).coerceIn(0f, 1f)
                    if (lock.floatValue >= 1f && f != null) {
                        frozen = f
                        capturedNs = now
                        phase = ScanPhase.CAPTURE
                        haptics.perform(HapticEvent.Tap)
                    }
                } else if (!fired && (now - capturedNs) / 1e9f > STAGES.size * STAGE_S + 0.6f) {
                    fired = true
                    haptics.perform(HapticEvent.Tap)
                    done()
                }
            }
        }
    }

    val measurer = rememberTextMeasurer(cacheSize = 48)
    val cs = MaterialTheme.colorScheme
    val hud = HudStyle(
        line = cs.primary,
        accent = cs.secondary,
        bg = cs.background,
        small = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant),
        big = MaterialTheme.typography.titleMedium.copy(color = cs.primary),
        mono = MaterialTheme.typography.labelMedium.copy(color = cs.primary),
    )
    Box(modifier.fillMaxSize()) {
        if (phase == ScanPhase.SEARCH) CameraFeed(latest, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val now = frame.longValue
            val t = now / 1e9f
            when (phase) {
                ScanPhase.SEARCH -> drawSearch(hud, measurer, latest.get(), lock.floatValue, t)
                ScanPhase.CAPTURE -> frozen?.let { drawCapture(hud, measurer, it, (now - capturedNs) / 1e9f) }
            }
        }
    }
}

@Composable
private fun CameraFeed(latest: AtomicReference<FaceFrame?>, modifier: Modifier) {
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
            tracker = runCatching { FaceTracker(context, latest) }.onFailure { Log.e(TAG, "tracker init", it) }.getOrNull()
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

private fun DrawScope.drawSearch(s: HudStyle, m: TextMeasurer, f: FaceFrame?, lock: Float, t: Float) {
    drawRect(s.bg.copy(alpha = 0.35f))
    drawScanlines(s)
    val map = f?.let { FaceMap(it, size) }
    val box = map?.bounds(f)?.inflate(size.width * 0.06f)
        ?: Rect(Offset(size.width / 2, size.height * 0.42f), size.width * 0.34f)
    val tight = 1f - 0.08f * lock
    drawBrackets(s.line, box.deflate(box.width * (1f - tight) / 2), 22.dp.toPx())

    if (f != null && map != null) {
        val sweep = box.top + box.height * lock
        val mesh = Path()
        val e = FaceMesh.tesselation
        var k = 0
        while (k < e.size) {
            val a = e[k]
            val b = e[k + 1]
            k += 2
            val ay = map.y(f, a)
            if (lock < 1f && ay > sweep && map.y(f, b) > sweep) continue
            mesh.moveTo(map.x(f, a), ay)
            mesh.lineTo(map.x(f, b), map.y(f, b))
        }
        drawPath(mesh, s.line.copy(alpha = 0.18f + 0.3f * lock), style = Stroke(0.7.dp.toPx()))
        val contour = Path()
        val c = FaceMesh.contours
        k = 0
        while (k < c.size) {
            contour.moveTo(map.x(f, c[k]), map.y(f, c[k]))
            contour.lineTo(map.x(f, c[k + 1]), map.y(f, c[k + 1]))
            k += 2
        }
        drawPath(contour, s.accent.copy(alpha = 0.85f), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
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

    val status = when {
        f == null -> "Align face"
        f.widthFraction() <= MIN_FACE_WIDTH -> "Move closer"
        abs(f.yawDeg) >= MAX_TILT_DEG || abs(f.pitchDeg) >= MAX_TILT_DEG -> "Look straight"
        else -> "Hold still"
    }
    val pad = 16.dp.toPx()
    drawText(m.measure("FACE SCAN", s.small), topLeft = Offset(pad, pad))
    drawText(m.measure(status, s.big), topLeft = Offset(pad, pad + 16.dp.toPx()))
    if (f != null) {
        val read = "YAW %+03.0f°\nPITCH %+03.0f°\nPTS %d".format(Locale.ROOT, f.yawDeg, f.pitchDeg, f.count)
        val l = m.measure(read, s.mono)
        drawText(l, topLeft = Offset(size.width - pad - l.size.width, pad))
    }
    val barW = size.width - pad * 2
    val barY = size.height - pad - 4.dp.toPx()
    drawLine(s.line.copy(alpha = 0.2f), Offset(pad, barY), Offset(pad + barW, barY), 3.dp.toPx())
    drawLine(s.line, Offset(pad, barY), Offset(pad + barW * lock, barY), 3.dp.toPx())
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
private const val MIN_FACE_WIDTH = 0.28f
private const val MAX_TILT_DEG = 15f
private const val TAG = "TwinScan"
