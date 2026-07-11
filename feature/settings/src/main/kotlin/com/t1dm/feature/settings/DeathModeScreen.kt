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
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import kotlinx.coroutines.launch
import kotlin.math.PI

/**
 * The DEATH-mode activation rite — the page that silences every alarm and draws back every safety
 * rail, irrevocably, until it is rescinded. Not a settings toggle but a funeral rite: a dreadful
 * warning, three slow tolls of a synthesised bell, and a signed covenant with the angel of death
 * ملاك الموت, after which the screen shows a sealed view — an animated angel and the line "The bell
 * tolls for thee" — bearing a single grave affordance to rescind.
 *
 * Every colour is read from the active theme so the dread renders in each palette's own key: cold
 * neon on Tron, blood-red on Umbrella, a soft pink-macabre on Hello Kitty. The page draws its own
 * warning text directly — [DangerBanner] is suppressed while death mode is engaged, so it cannot be
 * relied on here.
 */
@Composable
fun DeathModeScreen(active: Boolean, onActivate: () -> Unit, onDeactivate: () -> Unit) {
    val toll = remember { FuneralToll() }
    DisposableEffect(Unit) { onDispose { toll.release() } }

    // The covenant becomes sealed the moment the signature is submitted, before the persisted flag
    // propagates back as `active`; either suffices to show the sealed view.
    var sealedLocally by remember { mutableStateOf(false) }
    val sealed = active || sealedLocally

    var stage by remember { mutableStateOf(Rite.WARNING) }
    // A transient state that plays the covenant rending apart before the rite is truly rescinded.
    var tearing by remember { mutableStateOf(false) }
    // Each return to the un-engaged state re-opens the rite at its first stanza.
    LaunchedEffect(sealed) { if (!sealed) { stage = Rite.WARNING; tearing = false } }

    val animationsOn = LocalAnimationsEnabled.current
    val cs = MaterialTheme.colorScheme

    val view = when {
        tearing -> DeathView.TEARING
        sealed -> DeathView.SEALED
        stage == Rite.TOLLING -> DeathView.TOLLING
        stage == Rite.CONTRACT -> DeathView.CONTRACT
        else -> DeathView.WARNING
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background),
    ) {
        AnimatedContent(
            targetState = view,
            transitionSpec = {
                if (animationsOn) {
                    // A slow, funereal cross-dissolve: fade, a faint downward drift, a breath of scale.
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
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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

// ── (a) the warning ────────────────────────────────────────────────────────────────────────────

@Composable
private fun WarningStanza(onBegin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
    GraveButton("Begin", onClick = onBegin)
}

// ── (b) the tolling ────────────────────────────────────────────────────────────────────────────

private const val MIN_TOLL_INTERVAL_MS = 1200L

@Composable
private fun TollingRite(toll: FuneralToll, onComplete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(0) }
    var lastTollMs by remember { mutableLongStateOf(0L) }
    val swing = remember { androidx.compose.animation.core.Animatable(0f) }

    // After the third vow, let the bell still before the covenant is revealed.
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
                    // Soft-reject hurried taps so the rite stays slow and solemn.
                    if (count >= 3 || now - lastTollMs < MIN_TOLL_INTERVAL_MS) return@detectTapGestures
                    lastTollMs = now
                    count += 1
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

// ── (c) the covenant ───────────────────────────────────────────────────────────────────────────

@Composable
private fun CovenantRite(onSign: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
                        current = Path().apply { moveTo(off.x, off.y) }
                        inkVersion += 1
                    },
                    onDrag = { change, _ ->
                        current?.lineTo(change.position.x, change.position.y)
                        inkVersion += 1
                    },
                    onDragEnd = {
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
        // Reading `inkVersion` here registers the ink mutations that force a redraw.
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
                strokes.clear()
                current = null
                inkVersion = 0
            },
        ) {
            Text("Clear", color = cs.onSurface.copy(alpha = 0.6f))
        }
        GraveButton("Sign the covenant", enabled = hasInk, onClick = onSign)
    }
}

// ── (d) the sealed rite ──────────────────────────────────────────────────────────────────────────

@Composable
private fun SealedRite(onRescind: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
        onClick = onRescind,
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

// ── (e) the tearing ─────────────────────────────────────────────────────────────────────────────

/** The rescission made visible: the sealed covenant rends along a jagged diagonal, and its two
 *  halves rotate, slide and fall away before the watch is restored. When the rift completes, and
 *  only then, [onTornAway] deactivates the rite. */
@Composable
private fun TearingRite(onTornAway: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        // A slow, resisting rend — the covenant holds, then gives near the end, so the tearing reads
        // as deliberate rather than brisk. Every transform below is a function of `tear.value`, so the
        // halves part in step across the whole span and reach their rest exactly as the rift completes.
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

        // A rough zigzag rip running top to bottom, biased across the diagonal.
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

        // Each half is the same contract paper, clipped to one side of the rift and carried off as a
        // rigid shard — the clip travels with the transform so the piece stays whole while it falls.
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

        // A raw seam of red glows in the rift for the instant before the halves part.
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

// ── shared pieces ────────────────────────────────────────────────────────────────────────────────

/** A short poetic stanza, centred, with a thin accent rule above it. */
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

// ── the idle clock ───────────────────────────────────────────────────────────────────────────────

/** A continuous idle-motion phase in [0, 2π) for breathing/sway/flicker, or a frozen 0 when motion
 *  is disabled. */
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
