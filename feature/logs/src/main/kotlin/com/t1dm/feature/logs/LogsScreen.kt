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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.t1dm.core.model.CurveKind
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.logAmountLabel
import com.t1dm.core.design.logDetailLabel
import com.t1dm.core.design.logTimeLabel
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.LoggedEntry
import kotlin.math.roundToInt

/**
 * The Logs panel: every insulin dose and every carbohydrate entry the user has logged, newest first.
 *
 * It owns the two clinical event stores (`logged_dose` / `logged_meal`) the model reconstructs its
 * carb and insulin channels from, and nothing else.
 *
 * **Every row can be edited and deleted, unconditionally.** There are no longer two delivery states
 * gating that: a deletion travels as a tombstone on the same upsert the create rode, ordered against
 * it by `updated_at`, so it can no longer be overtaken by a catch-up re-hydrating the row. What the
 * hold still buys is narrower and worth saying plainly — it spares a log undone immediately the
 * round trip of a create followed by its own deletion.
 *
 * A row that has been edited says so. Every figure the user is looking at was computed from its
 * current values, and the same withholding-beats-inventing rule that governs the rest of the app
 * applies to a number whose inputs were changed after the fact.
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
    /** Commit an edit: the row, its new amount (grams or units), and its new instant. */
    onEdit: (LoggedEntry, Double, Long) -> Unit = { _, _, _ -> },
) {
    // The row a delete has been asked for, held here rather than per-row: the confirmation outlives the
    // row's own composition once the list re-sorts under it.
    var pending by remember { mutableStateOf<LoggedEntry?>(null) }
    var editing by remember { mutableStateOf<LoggedEntry?>(null) }

    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).fadingEdges(listState),
        state = listState,
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
                EntryRow(entry, onDelete = { pending = entry }, onEdit = { editing = entry })
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

    editing?.let { entry ->
        EditEntryDialog(
            entry = entry,
            onConfirm = { amount, tsMs -> editing = null; onEdit(entry, amount, tsMs) },
            onDismiss = { editing = null },
        )
    }
}

/**
 * Rewrite a logged row's amount and time.
 *
 * Amount and time only. Everything else about a row — an insulin type, a meal's glycaemic index —
 * changes the curve's SHAPE, and a shape edit is a different question from a magnitude one: the
 * former needs the same resolver the logging path runs and the pickers that go with it. Correcting
 * a mistyped dose is the case this exists for.
 *
 * The time is offered in minutes relative to what is stored, so the field is a correction rather
 * than a re-entry. The repository snaps it back onto the five-minute grid.
 */
@Composable
private fun EditEntryDialog(
    entry: LoggedEntry,
    onConfirm: (Double, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    var amountText by remember(entry.clientId) { mutableStateOf(fmtAmount(entry.amount)) }
    var shiftText by remember(entry.clientId) { mutableStateOf("0") }
    val amount = amountText.toDoubleOrNull()
    val shiftMin = shiftText.toLongOrNull()
    val valid = amount != null && amount > 0.0 && shiftMin != null

    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text("Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    logTimeLabel(entry.tsMs, entry.tzOffsetMin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(if (entry.kind == CurveKind.CARB) "g" else "U") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = shiftText,
                    onValueChange = { shiftText = it },
                    label = { Text("shift min") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    haptics.perform(HapticEvent.Commit)
                    onConfirm(amount!!, entry.tsMs + shiftMin!! * 60_000L)
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}

/** Receipt/dialog numerals: integral amounts read as "45", a half unit still as "4.5". */
private fun fmtAmount(v: Double): String =
    if (v == Math.rint(v) && v.isFinite()) v.toLong().toString() else "%.1f".format(v)

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
 * The send hold: how long a newly logged row waits before its push is first attempted.
 *
 * The note stays, but what it says has changed. Delete now works on every row at any age, so the
 * hold no longer governs it — what it still buys is sparing a log undone immediately the round trip
 * of a create followed by its own deletion.
 */
@Composable
private fun HoldSection(holdMin: Int, maxMin: Int, onSet: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Hold before sending", style = MaterialTheme.typography.labelMedium)
        Text(
            "Spares an immediate undo a round trip",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            if (holdMin == 0) "off" else "$holdMin min",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // Fed the QUANTISED value, not Material's continuous Float, so the detent ticks once per stop
        // rather than once per pixel (the same reasoning as the two duration sliders in Settings → CGM).
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
private fun EntryRow(entry: LoggedEntry, onDelete: () -> Unit, onEdit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        // The panel's OWN ink, read inside the card so it is the card's. `panelCardColors` guarantees
        // this one clears AA against the container; `cs.onSurface` is the unguarded role it may have
        // had to reject, and a row deriving its secondary lines from that would be guarded on one line
        // and unguarded on the next — at opposite polarities.
        val ink = LocalContentColor.current
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    logTimeLabel(entry.tsMs, entry.tzOffsetMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.6f),
                )
                Text(
                    logAmountLabel(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                logDetailLabel(entry)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.edited) {
                Text(
                    "edited",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
            val haptics = rememberT1dmHaptics()
            TextButton(onClick = { haptics.perform(HapticEvent.Tap); onEdit() }) { Text("Edit") }
            TextButton(onClick = { haptics.perform(HapticEvent.Warn); onDelete() }) { Text("Delete") }
        }
    }
}

/**
 * Ask once before dropping a clinical row.
 *
 * Not friction for its own sake. IOB is computed from logged doses only (§3.6-F), so deleting the
 * NEWEST dose lowers assumed insulin and moves the log-gap mark backward, which tightens the rail
 * that reads it — but deleting an older one lowers assumed IOB with the mark unmoved, which RELAXES
 * `Rails.iobCeiling` in silence. Nothing downstream catches that, so this dialog is the only place
 * the direction is questioned at all.
 */
@Composable
private fun DeleteConfirmDialog(entry: LoggedEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text("Delete this entry?") },
        text = {
            Text("${logAmountLabel(entry)} · ${logTimeLabel(entry.tsMs, entry.tzOffsetMin)}")
        },
        confirmButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Commit); onConfirm() }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}

