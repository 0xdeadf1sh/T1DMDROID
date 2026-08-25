package com.t1dm.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberT1dmHaptics
import kotlinx.coroutines.launch
import kotlin.math.PI

/** [DangerBanner] is suppressed while death mode is engaged, so this page draws its own warning. */
@Composable
fun DeathModeScreen(active: Boolean, onActivate: () -> Unit, onDeactivate: () -> Unit) {
    val toll = remember { FuneralToll() }
    DisposableEffect(Unit) { onDispose { toll.release() } }

    // Set at signature, before the persisted flag propagates back as `active`; either suffices.
    var sealedLocally by remember { mutableStateOf(false) }
    val sealed = active || sealedLocally

    var stage by remember { mutableStateOf(Rite.WARNING) }
    var tearing by remember { mutableStateOf(false) }
    LaunchedEffect(sealed) { if (!sealed) { stage = Rite.WARNING; tearing = false } }

    val animationsOn = LocalAnimationsEnabled.current

    val view = when {
        tearing -> DeathView.TEARING
        sealed -> DeathView.SEALED
        stage == Rite.TOLLING -> DeathView.TOLLING
        stage == Rite.CONTRACT -> DeathView.CONTRACT
        else -> DeathView.WARNING
    }

    // No background: the per-theme backdrop shows through at the user's opacity.
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = view,
            transitionSpec = {
                if (animationsOn) {
                    ContentTransform(
                        targetContentEnter = fadeIn(tween(600, easing = EaseInOut)) +
                            slideInVertically(tween(600, easing = EaseInOut)) { it / 14 } +
                            scaleIn(tween(600, easing = EaseInOut), initialScale = 0.97f),
                        initialContentExit = fadeOut(tween(500, easing = EaseInOut)) +
                            slideOutVertically(tween(500, easing = EaseInOut)) { -it / 14 } +
                            scaleOut(tween(500, easing = EaseInOut), targetScale = 1.03f),
                        sizeTransform = SizeTransform(clip = false),
                    )
                } else {
                    ContentTransform(
                        targetContentEnter = fadeIn(snap()),
                        initialContentExit = fadeOut(snap()),
                        sizeTransform = SizeTransform { _, _ -> snap() },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "rite",
        ) { pane ->
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .fadingEdges(scroll)
                    .verticalScroll(scroll)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (pane) {
                    DeathView.WARNING -> WarningStanza(onBegin = { stage = Rite.TOLLING })
                    DeathView.TOLLING -> TollingRite(toll = toll, onComplete = { stage = Rite.CONTRACT })
                    DeathView.CONTRACT -> CovenantRite(
                        onSign = {
                            sealedLocally = true
                            onActivate()
                        },
                    )
                    DeathView.SEALED -> SealedRite(
                        onRescind = {
                            // The tear plays first; onDeactivate fires only once the halves fall away.
                            if (animationsOn) {
                                tearing = true
                            } else {
                                sealedLocally = false
                                onDeactivate()
                            }
                        },
                    )
                    DeathView.TEARING -> TearingRite(
                        onTornAway = {
                            sealedLocally = false
                            onDeactivate()
                        },
                    )
                }
            }
        }
    }
}

private enum class Rite { WARNING, TOLLING, CONTRACT }
private enum class DeathView { WARNING, TOLLING, CONTRACT, SEALED, TEARING }

@Composable
private fun WarningStanza(onBegin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberT1dmHaptics()
    val phase = idlePhase(5000, "skull")
    Spacer(Modifier.height(8.dp))
    Canvas(
        Modifier
            .fillMaxWidth(0.5f)
            .aspectRatio(1f),
    ) {
        drawSkull(phase, cs.primary, cs.error, cs.onSurface)
    }
    Verse(
        listOf(
            "Beyond this threshold every safeguard falls silent.",
            "No alarm will wake you; no rail will hold you back.",
            "The watch that stood between you and the dark is drawn.",
            "Cross only if you would surrender it, entire.",
        ),
        accent = cs.error,
    )
    Spacer(Modifier.height(4.dp))
    GraveButton("Begin", onClick = { haptics.perform(HapticEvent.Warn); onBegin() })
}

private const val MIN_TOLL_INTERVAL_MS = 1200L

@Composable
private fun TollingRite(toll: FuneralToll, onComplete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptics = rememberT1dmHaptics()
    var count by remember { mutableIntStateOf(0) }
    var lastTollMs by remember { mutableLongStateOf(0L) }
    val swing = remember { androidx.compose.animation.core.Animatable(0f) }

    // Let the bell still before the covenant is revealed.
    LaunchedEffect(count) {
        if (count >= 3) {
            kotlinx.coroutines.delay(900)
            onComplete()
        }
    }

    Verse(
        listOf(
            "Ring the bell thrice, and slowly —",
            "each toll a vow you cannot unsay.",
        ),
        accent = cs.error,
    )

    Canvas(
        Modifier
            .fillMaxWidth(0.62f)
            .aspectRatio(0.82f)
            .pointerInput(Unit) {
                detectTapGestures {
                    val now = System.currentTimeMillis()
                    // Soft-reject hurried taps so the rite stays slow.
                    if (count >= 3 || now - lastTollMs < MIN_TOLL_INTERVAL_MS) {
                        haptics.perform(HapticEvent.Reject)
                        return@detectTapGestures
                    }
                    lastTollMs = now
                    count += 1
                    haptics.perform(HapticEvent.Commit)
                    toll.toll()
                    val dir = if (count % 2 == 0) -1f else 1f
                    scope.launch {
                        swing.snapTo(20f * dir)
                        swing.animateTo(0f, spring(dampingRatio = 0.26f, stiffness = Spring.StiffnessLow))
                    }
                }
            },
    ) {
        drawBell(swing.value, primary = cs.primary, accent = cs.error, ink = cs.onSurface)
    }

    Text(
        tollNumeral(count),
        style = MaterialTheme.typography.displaySmall,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        color = cs.error,
    )
    Text(
        "$count of three",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurface.copy(alpha = 0.6f),
    )
}

private fun tollNumeral(count: Int): String = when (count) {
    0 -> "·"
    1 -> "I"
    2 -> "II"
    else -> "III"
}

@Composable
private fun CovenantRite(onSign: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberT1dmHaptics()
    Text(
        "Release of Liability",
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        color = cs.onSurface,
        textAlign = TextAlign.Center,
    )
    Text(
        "ملاك الموت",
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = cs.error,
        textAlign = TextAlign.Center,
    )
    Verse(
        listOf(
            "I, the Living, of failing flesh and open eyes,",
            "unbind the angel ملاك الموت from every stay.",
            "Let him come at the hour he alone appoints,",
            "heralded by no alarm, and take what was but lent.",
            "The bells that stood their watch are stilled. I consent.",
        ),
        accent = cs.onSurface,
    )

    Text(
        "Sign below.",
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurface.copy(alpha = 0.7f),
    )
    val strokes = remember { mutableStateListOf<Path>() }
    var current by remember { mutableStateOf<Path?>(null) }
    var inkVersion by remember { mutableIntStateOf(0) }
    val hasInk = inkVersion > 0

    val inkColor = cs.error
    val padLine = cs.onSurface.copy(alpha = 0.28f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(cs.surface)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        haptics.perform(HapticEvent.StrokeStart)
                        current = Path().apply { moveTo(off.x, off.y) }
                        inkVersion += 1
                    },
                    onDrag = { change, _ ->
                        current?.lineTo(change.position.x, change.position.y)
                        inkVersion += 1
                    },
                    onDragEnd = {
                        haptics.perform(HapticEvent.StrokeEnd)
                        current?.let { strokes.add(it) }
                        current = null
                    },
                )
            },
    ) {
        drawLine(
            padLine,
            Offset(size.width * 0.06f, size.height * 0.74f),
            Offset(size.width * 0.94f, size.height * 0.74f),
            strokeWidth = 2f,
        )
        // Reading `inkVersion` is what registers the ink mutations for redraw.
        if (inkVersion >= 0) {
            val pen = Stroke(width = 5f, cap = StrokeCap.Round)
            strokes.forEach { drawPath(it, inkColor, style = pen) }
            current?.let { drawPath(it, inkColor, style = pen) }
        }
    }

    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                haptics.perform(HapticEvent.Reject)
                strokes.clear()
                current = null
                inkVersion = 0
            },
        ) {
            Text("Clear", color = cs.onSurface.copy(alpha = 0.6f))
        }
        GraveButton(
            "Sign the covenant",
            enabled = hasInk,
            onClick = { haptics.perform(HapticEvent.Commit); onSign() },
        )
    }
}

@Composable
private fun SealedRite(onRescind: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberT1dmHaptics()
    val phase = idlePhase(6000, "reaper")

    Spacer(Modifier.height(4.dp))
    Canvas(
        Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(0.78f),
    ) {
        drawReaper(phase, primary = cs.primary, accent = cs.error, ink = cs.onSurface)
    }

    Text(
        "The bell tolls for thee.",
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        color = cs.error,
        textAlign = TextAlign.Center,
    )
    Verse(
        listOf(
            "The covenant with ملاك الموت is in force.",
            "The alarms that once stood watch are stilled;",
            "no rail remains between you and the hour he wills.",
        ),
        accent = cs.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { haptics.perform(HapticEvent.Reject); onRescind() },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
    ) {
        Text("Rescind the covenant")
    }
    Text(
        "To rescind is to restore every alarm and every rail — the watch resumes.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurface.copy(alpha = 0.55f),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
}

/** [onTornAway] fires only once the rift completes. */
@Composable
private fun TearingRite(onTornAway: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        // Every transform below is a function of `tear.value`, so the halves part in step.
        tear.animateTo(1f, tween(2200, easing = CubicBezierEasing(0.7f, 0f, 0.85f, 0.35f)))
        onTornAway()
    }

    Spacer(Modifier.height(4.dp))
    Canvas(
        Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(0.78f)
            .clipToBounds()
            .graphicsLayer { alpha = 1f - tear.value * 0.6f },
    ) {
        val t = tear.value
        val w = size.width
        val h = size.height

        val steps = 6
        val pts = (0..steps).map { i ->
            val f = i / steps.toFloat()
            val baseX = w * 0.58f + (w * 0.42f - w * 0.58f) * f
            val jag = if (i == 0 || i == steps) 0f else if (i % 2 == 0) w * 0.05f else -w * 0.05f
            Offset(baseX + jag, h * f)
        }
        val leftPiece = Path().apply {
            moveTo(0f, 0f)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(0f, h)
            close()
        }
        val rightPiece = Path().apply {
            moveTo(w, 0f)
            pts.forEach { lineTo(it.x, it.y) }
            lineTo(w, h)
            close()
        }

        // The clip travels with the transform so each shard stays whole as it falls.
        withTransform({
            rotate(-24f * t, pivot = Offset(w * 0.30f, h * 0.5f))
            translate(-w * 0.55f * t, h * 0.7f * t)
            clipPath(leftPiece)
        }) {
            drawContract(0f, primary = cs.primary, accent = cs.error, ink = cs.onSurface)
        }
        withTransform({
            rotate(22f * t, pivot = Offset(w * 0.70f, h * 0.5f))
            translate(w * 0.55f * t, h * 0.7f * t)
            clipPath(rightPiece)
        }) {
            drawContract(0f, primary = cs.primary, accent = cs.error, ink = cs.onSurface)
        }

        val seam = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(seam, cs.error.copy(alpha = (1f - t) * 0.6f), style = Stroke(width = 3f))
    }

    Text(
        "The covenant is torn.",
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        color = cs.error.copy(alpha = 1f - tear.value),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Verse(lines: List<String>, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth(0.14f)
            .height(2.dp)
            .background(accent.copy(alpha = 0.6f)),
    )
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GraveButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = cs.error,
            contentColor = if (LocalT1dmSemantics.current.dark) Color.Black else Color.White,
        ),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/** Phase in [0, 2π), or a frozen 0 when motion is disabled. */
@Composable
private fun idlePhase(durationMs: Int, label: String): Float =
    if (LocalAnimationsEnabled.current) {
        val transition = rememberInfiniteTransition(label = label)
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(durationMs)),
            label = "${label}Phase",
        ).value
    } else 0f

// Withheld from the index in the public flavor by SettingsIndex.visible; the override is compiled
// out there.

private val deathModeRite = SettingsKnob(
    id = "death_mode.rite",
    screen = SettingsScreenKey.DEATH_MODE,
    section = "The end",
    label = "Death mode",
    subtitle = "Silence every alarm, drop every safety rail, and still the warnings — until rescinded",
    synonyms = listOf(
        "death", "death mode", "fail open", "silence", "mute all", "disable alarms",
        "disable safety", "rails off", "override", "covenant", "rite", "reaper", "danger",
        "no warnings", "suppress",
    ),
    anchored = false,
)

internal val settingsDeathModeKnobs = listOf(deathModeRite)
