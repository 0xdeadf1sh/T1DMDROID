package com.t1dm.feature.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.logTimeLabel
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import kotlin.math.roundToInt

/**
 * The Exercise panel: the bout being recorded (or the controls to start one), the body mass its energy
 * figure cannot be computed without, and every bout already recorded, newest first.
 *
 * Pure and stateless in the house mould — it renders what `:app` collected off the port and hoists
 * every write. It knows nothing of the location service, of Room, or of the wire.
 *
 * [degraded] is the one honesty channel and must be rendered wherever it is set: a location permission
 * the user declined, a provider switched off, or a receiver that has gone quiet all leave a bout
 * looking exactly like a walk that went nowhere.
 */
@Composable
fun ExerciseScreen(
    sessions: List<ExerciseSession> = emptyList(),
    active: ActiveExercise? = null,
    degraded: String? = null,
    bodyMassKg: Double? = null,
    onSetBodyMassKg: (Double) -> Unit = {},
    onStart: (ExerciseKind) -> Unit = {},
    onStop: () -> Unit = {},
    onOpen: (Long) -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).fadingEdges(listState),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item(key = "control") {
            if (active != null) LiveCard(active, degraded, bodyMassKg, onStop)
            else StartCard(degraded, onStart)
        }
        item(key = "mass") { BodyMassRow(bodyMassKg, onSetBodyMassKg) }
        if (sessions.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing recorded yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            items(sessions, key = { it.id }) { session -> SessionRow(session, onOpen) }
        }
        item(key = "footer") { footer() }
    }
}

@Composable
private fun LiveCard(
    active: ActiveExercise,
    degraded: String?,
    bodyMassKg: Double?,
    onStop: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Card(colors = panelCardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(kindLabel(active.session.kind), style = MaterialTheme.typography.titleSmall)
            Text(liveLine(active), style = MaterialTheme.typography.bodyMedium)
            kcalNote(active, bodyMassKg)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
            degraded?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { haptics.perform(HapticEvent.Tap); onStop() }) { Text("Stop") }
        }
    }
}

@Composable
private fun StartCard(degraded: String?, onStart: (ExerciseKind) -> Unit) {
    val haptics = rememberT1dmHaptics()
    var kind by remember { mutableStateOf(ExerciseKind.WALK) }
    Card(colors = panelCardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseKind.entries.forEach { k ->
                    FilterChip(
                        selected = k == kind,
                        onClick = { haptics.perform(HapticEvent.SegmentTick); kind = k },
                        label = { Text(kindLabel(k)) },
                    )
                }
            }
            degraded?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { haptics.perform(HapticEvent.Tap); onStart(kind) }) { Text("Start") }
        }
    }
}

/**
 * Body mass, the one input the energy figure cannot derive.
 *
 * Panel-owned rather than a Settings knob, and deliberately outside the shareable config export —
 * the store's own note says why. The commit is on release, not on every drag sample: the value is
 * kv-backed, and writing per pointer move is a database round trip per pixel.
 */
@Composable
private fun BodyMassRow(bodyMassKg: Double?, onSet: (Double) -> Unit) {
    var draft by remember(bodyMassKg) { mutableFloatStateOf((bodyMassKg ?: MASS_DEFAULT_KG).toFloat()) }
    var touched by remember(bodyMassKg) { mutableStateOf(bodyMassKg != null) }
    val detent = rememberHapticDetent()
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Body mass " + if (touched) "${draft.roundToInt()} kg" else "n/a",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = draft,
            onValueChange = { detent.at(it.roundToInt()); draft = it; touched = true },
            onValueChangeFinished = { onSet(draft.roundToInt().toDouble()) },
            valueRange = MASS_MIN_KG..MASS_MAX_KG,
            steps = (MASS_MAX_KG - MASS_MIN_KG).toInt() - 1,
        )
    }
}

@Composable
private fun SessionRow(session: ExerciseSession, onOpen: (Long) -> Unit) {
    val haptics = rememberT1dmHaptics()
    Card(
        onClick = { haptics.perform(HapticEvent.NavSwitch); onOpen(session.id) },
        colors = panelCardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(kindLabel(session.kind), style = MaterialTheme.typography.titleSmall)
                Text(
                    logTimeLabel(session.startMs, session.tzOffsetMin),
                    style = MaterialTheme.typography.bodySmall,
                    // Inside a panel card the ink is the card's, not the scheme's — `panelCardColors`
                    // measures a legible one against the painted surface.
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
            Text(durationLabel(session.activeSec), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun kindLabel(kind: ExerciseKind): String = when (kind) {
    ExerciseKind.WALK -> "Walk"
    ExerciseKind.RUN -> "Run"
    ExerciseKind.OTHER -> "Other"
}

/**
 * Metres as the panel and the foreground-service notification both say them, or null when the bout
 * measured no distance at all.
 *
 * Public, and the only place this is formatted: the service notification says the same thing about
 * the same bout as the card behind it, and two formatters would eventually disagree about the grain.
 * Locale-free by construction (integer arithmetic, not `String.format`) so the decimal separator
 * cannot move under a non-US default locale.
 */
fun distanceLabel(metres: Double?): String? {
    if (metres == null || !metres.isFinite() || metres <= 0.0) return null
    if (metres < 1_000.0) return "${metres.roundToInt()} m"
    val hundredths = (metres / 10.0).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} km"
}

/** `h:mm:ss` past an hour, `m:ss` below it — the shape a stopwatch reads in. */
fun durationLabel(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = (s % 60).toString().padStart(2, '0')
    val mm = m.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
}

/** `5:31 /km`, or null where the bout has not covered enough ground for a pace to mean anything —
 *  the recorder withholds it below its own distance floor rather than dividing scatter by seconds. */
internal fun paceLabel(secPerKm: Double?): String? {
    if (secPerKm == null || !secPerKm.isFinite() || secPerKm <= 0.0) return null
    val s = secPerKm.roundToInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')} /km"
}

/** The running bout in one line — elapsed, distance, pace, energy — with each part omitted rather
 *  than shown empty. Pure, so the wording is testable without a composition. */
internal fun liveLine(active: ActiveExercise): String = buildList {
    add(durationLabel((active.elapsedMs / 1000L).toInt()))
    distanceLabel(active.distanceM)?.let { add(it) }
    paceLabel(active.paceSecPerKm)?.let { add(it) }
    active.kcal?.let { add("$it kcal") }
}.joinToString(" · ")

/**
 * Why the running bout carries no kcal, or null when it carries one.
 *
 * Only the two causes the user can do something about, and each named rather than lumped: an
 * [ExerciseKind.OTHER] bout is one the ACSM walking/running equations in `:sensors` do not describe
 * at all, and a missing body mass is the row directly below. A bout that has simply not covered any ground
 * yet says nothing — it will, in a minute, and blaming that on the mass would send the user to the
 * wrong knob.
 */
internal fun kcalNote(active: ActiveExercise, bodyMassKg: Double?): String? = when {
    active.kcal != null -> null
    active.session.kind == ExerciseKind.OTHER -> "kcal needs walk or run"
    bodyMassKg == null -> "kcal needs body mass"
    else -> null
}

/** What a bout already recorded amounts to, for the review's header. */
internal fun summaryLine(session: ExerciseSession): String = buildList {
    add(durationLabel(session.activeSec))
    distanceLabel(session.distanceM)?.let { add(it) }
    session.kcal?.let { add("$it kcal") }
}.joinToString(" · ")

// The slider's own travel, not a rule: the store enforces the only floor that binds, and it is lower
// than this. Nothing here may be read as a clinical bound.
private const val MASS_MIN_KG = 30f
private const val MASS_MAX_KG = 200f
private const val MASS_DEFAULT_KG = 70.0
