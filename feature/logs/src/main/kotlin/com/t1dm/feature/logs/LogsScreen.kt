package com.t1dm.feature.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.LogState
import com.t1dm.core.model.LoggedEntry
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The Logs panel: every insulin dose and every carbohydrate entry the user has logged, newest first,
 * each labelled with how far it has got towards the server.
 *
 * It owns the two clinical event stores (`logged_dose` / `logged_meal`) the model reconstructs its
 * carb and insulin channels from, and nothing else.
 *
 * The two states are an ACKNOWLEDGEMENT, never an elapsed duration (see [LogState]), and that is what
 * makes the delete affordance safe to gate on them: a [LogState.COMMITTED] row still has its push in the
 * queue and can be withdrawn whole, while a [LogState.DELIVERED] one is on a server with no DELETE
 * endpoint and would be re-hydrated by the next catch-up anyway. So a delivered row simply has no
 * Delete, rather than one that fails.
 *
 * It also carries the MOOD picker, the sole author of `sample.mood` — a scalar of the six-series
 * ingest row rather than a clinical event, which is why it sits above the list instead of in it and
 * writes an overwrite of the current 5-minute bucket rather than a row that could be withdrawn.
 *
 * Pure and stateless in the house mould: it renders the [entries] `:app` joined against the queue and
 * hoists both actions. No `:data`, no `:sync`, no knowledge that an outbox exists.
 */
@Composable
fun LogsScreen(
    entries: List<LoggedEntry> = emptyList(),
    holdMin: Int = 0,
    holdMaxMin: Int = 0,
    /** The most recent mood written, 1..5, or null when none has been. */
    currentMood: Int? = null,
    onSetHoldMin: (Int) -> Unit = {},
    onPickMood: (Int) -> Unit = {},
    onDelete: (LoggedEntry) -> Unit = {},
) {
    // The row a delete has been asked for, held here rather than per-row: the confirmation outlives the
    // row's own composition once the list re-sorts under it.
    var pending by remember { mutableStateOf<LoggedEntry?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item(key = "mood") { MoodPicker(currentMood, onPickMood) }
        item(key = "hold") { HoldSection(holdMin, holdMaxMin, onSetHoldMin) }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing logged yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            // `clientId` is the phone-minted event id and is unique across BOTH tables, so it keys the
            // list without the kind having to be folded in.
            items(entries, key = { it.clientId }) { entry ->
                EntryRow(entry, onDelete = { pending = entry })
            }
        }
    }

    pending?.let { entry ->
        DeleteConfirmDialog(
            entry = entry,
            onConfirm = { pending = null; onDelete(entry) },
            onDismiss = { pending = null },
        )
    }
}

/** Mood 1..5 (worst→best); the value is the integer written to `sample.mood` and pushed on the
 *  six-scalar ingest row. The glyphs ARE the scale, so nothing is labelled but the section itself. */
private val MOODS = listOf(1 to "😞", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "😀")

/**
 * The one user-facing writer of `sample.mood`.
 *
 * A five-stop scale, not five buttons — a detent, like any other single-choice picker. The write it
 * triggers is silent: mood is an overwrite of the current bucket's value rather than an appended
 * clinical row, so it earns no Commit and appears in no list below.
 */
@Composable
private fun MoodPicker(current: Int?, onPick: (Int) -> Unit) {
    val haptics = rememberT1dmHaptics()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Mood", style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MOODS.forEach { (value, glyph) ->
                FilterChip(
                    selected = current == value,
                    onClick = { haptics.perform(HapticEvent.SegmentTick); onPick(value) },
                    label = { Text(glyph, style = MaterialTheme.typography.titleLarge) },
                    shape = CircleShape,
                )
            }
        }
    }
}

/** The slider's grain, in minutes — the same 5-minute quantum every other event time in the app is on. */
private const val HOLD_STEP_MIN = 5

/**
 * The withdrawal window: how long a newly logged row waits before its push is first attempted. The note
 * is not decoration — the hold is invisible everywhere else in the app, and without a word here the
 * knob's consequence (that is exactly how long Delete keeps working) cannot be inferred from it.
 */
@Composable
private fun HoldSection(holdMin: Int, maxMin: Int, onSet: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Hold before sending", style = MaterialTheme.typography.labelMedium)
        Text(
            "How long Delete stays available",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            if (holdMin == 0) "off" else "$holdMin min",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // Fed the QUANTISED value, not Material's continuous Float, so the detent ticks once per stop
        // rather than once per pixel (the same reasoning as the CGM panel's two sliders).
        val detent = rememberHapticDetent()
        if (maxMin >= HOLD_STEP_MIN) {
            Slider(
                value = holdMin.toFloat(),
                onValueChange = {
                    val minutes = ((it / HOLD_STEP_MIN).roundToInt() * HOLD_STEP_MIN).coerceIn(0, maxMin)
                    detent.at(minutes)
                    onSet(minutes)
                },
                valueRange = 0f..maxMin.toFloat(),
                steps = maxMin / HOLD_STEP_MIN - 1,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: LoggedEntry, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatTs(entry.tsMs, entry.tzOffsetMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    amountLabel(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                stateLabel(entry.state),
                style = MaterialTheme.typography.labelSmall,
                // Committed is the state that still has an obligation outstanding, so it is the one
                // that is coloured; delivered is done and reads as quiet.
                color = if (entry.committed) cs.primary else cs.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
            )
            if (entry.committed) {
                val haptics = rememberT1dmHaptics()
                TextButton(onClick = { haptics.perform(HapticEvent.Warn); onDelete() }) {
                    Text("Delete")
                }
            }
        }
    }
}

/**
 * Ask once before dropping a clinical row. Not friction for its own sake: IOB/COB are computed from
 * logged doses only (§3.6-F), so removing one lowers assumed insulin on board and relaxes the ceiling
 * that reads it — fail-closed-consistent, but still a change to what every rail downstream believes.
 */
@Composable
private fun DeleteConfirmDialog(entry: LoggedEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text("Delete this entry?") },
        text = {
            Text("${amountLabel(entry)} · ${formatTs(entry.tsMs, entry.tzOffsetMin)}")
        },
        confirmButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Commit); onConfirm() }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}

/** The row's headline: grams of carbohydrate, or units of insulin with the shape it acts through. */
private fun amountLabel(entry: LoggedEntry): String = when (entry.kind) {
    CurveKind.CARB -> "${fmtAmount(entry.amount)} g carbs"
    CurveKind.INSULIN -> {
        val shape = when (entry.insulin) {
            InsulinKind.BOLUS -> " bolus"
            InsulinKind.BASAL -> " basal"
            null -> ""
        }
        "${fmtAmount(entry.amount)} U$shape"
    }
}

private fun stateLabel(state: LogState): String = when (state) {
    LogState.COMMITTED -> "committed"
    LogState.DELIVERED -> "delivered"
}

/** Integral amounts read as "45", a half unit still as "4.5" (matching the commit receipts). */
private fun fmtAmount(v: Double): String =
    if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")

/** Rendered in the offset the row was WRITTEN at, not the reader's current one: a dose taken abroad
 *  keeps the wall-clock time the user took it at. */
private fun formatTs(ms: Long, tzOffsetMin: Int): String =
    Instant.ofEpochMilli(ms).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).format(TS_FMT)
