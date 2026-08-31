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

@Composable
fun LogsScreen(
    entries: List<LoggedEntry> = emptyList(),
    holdMin: Int = 0,
    holdMaxMin: Int = 0,
    /** 1..5; null when none has been written. */
    currentMood: Int? = null,
    onSetHoldMin: (Int) -> Unit = {},
    onPickMood: (Int) -> Unit = {},
    onDelete: (LoggedEntry) -> Unit = {},
    /** Amount in grams or units by kind; the Long is an epoch-ms instant. */
    onEdit: (LoggedEntry, Double, Long) -> Unit = { _, _, _ -> },
) {
    // Held here, not per-row: the confirmation outlives the row once the list re-sorts under it.
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
            // `clientId` is unique across BOTH tables, so the kind need not be folded into the key.
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

/** The shift is minutes relative to the stored instant; the repository snaps the result back onto
 *  the five-minute grid. */
@Composable
private fun EditEntryDialog(
    entry: LoggedEntry,
    onConfirm: (Double, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    var amountText by remember(entry.clientId) { mutableStateOf(fmtAmount(entry.amount)) }
    var shiftText by remember(entry.clientId) { mutableStateOf("0") }
    // A bout's magnitude is its duration times the carb-equivalent, and the duration is the bout's
    // own — so a replay moves in time and in nothing else.
    val timeOnly = entry.kind == CurveKind.EXERCISE
    val amount = if (timeOnly) entry.amount else amountText.toDoubleOrNull()
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
                if (!timeOnly) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(if (entry.kind == CurveKind.CARB) "g" else "U") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
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

private fun fmtAmount(v: Double): String =
    if (v == Math.rint(v) && v.isFinite()) v.toLong().toString() else "%.1f".format(v)

/** 1..5, worst→best; the value written to `sample.mood`. */
private val MOODS = listOf(1 to "😞", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "😀")

/** The write is silent: mood overwrites the current bucket rather than appending a clinical row. */
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

/** Minutes; the app's five-minute event quantum. */
private const val HOLD_STEP_MIN = 5

/** How long a newly logged row waits before its push is first attempted. */
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
        // Fed the quantised value, not Material's continuous Float, so it ticks once per stop.
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
        // `panelCardColors` guarantees this ink clears AA against the container; `cs.onSurface` is
        // the unguarded role it may have had to reject.
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

/** §3.6-F: deleting an OLDER dose lowers assumed IOB with the log-gap mark unmoved, silently
 *  relaxing `Rails.iobCeiling`. Nothing downstream catches that; this dialog is the only guard. */
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

