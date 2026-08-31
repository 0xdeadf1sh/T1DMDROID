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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.CurveSparkline
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.exerciseKindLabel as kindLabel
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.logTimeLabel
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.ExerciseSession
import kotlin.math.roundToInt

/** [degraded] must be rendered wherever it is set: a declined permission or a quiet receiver leaves
 *  a bout looking exactly like a walk that went nowhere. */
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
    onDelete: (Long) -> Unit = {},
    /** Grams per 5 min for a bout of this many minutes — the curve the model is handed. */
    previewExerciseCurve: (suspend (Double) -> DoubleArray)? = null,
    /** The bout, and the wall-clock instant its replay starts at. */
    onReplay: (ExerciseSession, Long) -> Unit = { _, _ -> },
    footer: @Composable () -> Unit = {},
) {
    val listState = rememberLazyListState()
    // The whole session, not its index: the list is rebuilt under an open dialog.
    var confirming by remember { mutableStateOf<ExerciseSession?>(null) }
    var replaying by remember { mutableStateOf<ExerciseSession?>(null) }
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
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session,
                    onOpen,
                    onDeleteRequest = { confirming = session },
                    onReplayRequest = { replaying = session },
                )
            }
        }
        item(key = "footer") { footer() }
    }

    replaying?.let { session ->
        ReplayDialog(
            session = session,
            previewExerciseCurve = previewExerciseCurve,
            onConfirm = { startMs -> replaying = null; onReplay(session, startMs) },
            onDismiss = { replaying = null },
        )
    }

    confirming?.let { session ->
        val haptics = rememberT1dmHaptics()
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Delete this bout?") },
            text = { Text("Track is lost") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(HapticEvent.Warn)
                    confirming = null
                    onDelete(session.id)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Keep") }
            },
        )
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

/** Commit on release, not per drag sample: the value is kv-backed. */
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

/**
 * The shift is minutes from now, so a bout can be laid down ahead of the clock and answered by the
 * forecast. Only the START moves: §5 makes the magnitude a function of the bout's own duration.
 */
@Composable
private fun ReplayDialog(
    session: ExerciseSession,
    previewExerciseCurve: (suspend (Double) -> DoubleArray)?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    var shiftText by remember(session.id) { mutableStateOf("0") }
    val shiftMin = shiftText.toLongOrNull()
    val minutes = session.activeSec / 60.0

    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text("Replay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${kindLabel(session.kind)} · ${durationLabel(session.activeSec)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = shiftText,
                    onValueChange = { shiftText = it },
                    label = { Text("shift min") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (previewExerciseCurve != null) {
                    val curve by produceState(DoubleArray(0), previewExerciseCurve, minutes) {
                        value = runCatching { previewExerciseCurve(minutes) }.getOrDefault(DoubleArray(0))
                    }
                    Text("Disposal — g per 5 min", style = MaterialTheme.typography.labelMedium)
                    CurveSparkline(curve, MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = shiftMin != null && minutes > 0.0,
                onClick = {
                    haptics.perform(HapticEvent.Commit)
                    onConfirm(System.currentTimeMillis() + shiftMin!! * 60_000L)
                },
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}

@Composable
private fun SessionRow(
    session: ExerciseSession,
    onOpen: (Long) -> Unit,
    onDeleteRequest: () -> Unit,
    onReplayRequest: () -> Unit,
) {
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
                    // Inside a panel card the ink is the card's, not the scheme's.
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(durationLabel(session.activeSec), style = MaterialTheme.typography.bodyMedium)
                // Not for the bout being recorded: `observeAll` has no `endMs` filter, so the open row
                // sits here beside the live card, and deleting it leaves the recorder writing fixes
                // for a session that is gone.
                if (session.endMs != null) {
                    TextButton(onClick = { haptics.perform(HapticEvent.Tap); onReplayRequest() }) {
                        Text("Replay")
                    }
                    IconButton(
                        onClick = { haptics.perform(HapticEvent.Tap); onDeleteRequest() },
                        // No TTS voice speaks U+2715.
                        modifier = Modifier.semantics {
                            contentDescription = "Delete ${kindLabel(session.kind)} bout"
                        },
                    ) { Text("\u2715", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

/** Locale-free by construction, so the decimal separator cannot move under a non-US locale. */
fun distanceLabel(metres: Double?): String? {
    if (metres == null || !metres.isFinite() || metres <= 0.0) return null
    if (metres < 1_000.0) return "${metres.roundToInt()} m"
    val hundredths = (metres / 10.0).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} km"
}

fun durationLabel(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = (s % 60).toString().padStart(2, '0')
    val mm = m.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
}

internal fun paceLabel(secPerKm: Double?): String? {
    if (secPerKm == null || !secPerKm.isFinite() || secPerKm <= 0.0) return null
    val s = secPerKm.roundToInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')} /km"
}

internal fun liveLine(active: ActiveExercise): String = buildList {
    add(durationLabel((active.elapsedMs / 1000L).toInt()))
    distanceLabel(active.distanceM)?.let { add(it) }
    paceLabel(active.paceSecPerKm)?.let { add(it) }
    active.kcal?.let { add("$it kcal") }
}.joinToString(" · ")

/** The ACSM walk/run equations in `:sensors` do not describe an [ExerciseKind.OTHER] bout. */
internal fun kcalNote(active: ActiveExercise, bodyMassKg: Double?): String? = when {
    active.kcal != null -> null
    active.session.kind == ExerciseKind.OTHER -> "kcal needs walk or run"
    bodyMassKg == null -> "kcal needs body mass"
    else -> null
}

internal fun summaryLine(session: ExerciseSession): String = buildList {
    add(durationLabel(session.activeSec))
    distanceLabel(session.distanceM)?.let { add(it) }
    session.kcal?.let { add("$it kcal") }
}.joinToString(" · ")

// Slider travel, not a clinical bound; the store enforces the only floor that binds.
private const val MASS_MIN_KG = 30f
private const val MASS_MAX_KG = 200f
private const val MASS_DEFAULT_KG = 70.0
