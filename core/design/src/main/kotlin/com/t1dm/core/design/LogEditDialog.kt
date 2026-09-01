package com.t1dm.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinChoice
import com.t1dm.core.model.LoggedEntry

/**
 * [amount] is grams, units or minutes by [LoggedEntry.kind]. [gi] 0..100, null clearing the recorded
 * index; [insulin] null leaves the dose's stored PK curve untouched, since a type is re-resolved
 * only when one is picked and re-resolving discards a hand-drawn curve. [note] is read for a
 * [CurveKind.CARB] row alone — a dose's note is the insulin the writer resolved, not the patient's
 * text, and an edit must not overwrite it.
 */
data class LogEdit(
    val amount: Double,
    val gi: Double?,
    val insulin: InsulinChoice?,
    val tsMs: Long,
    val note: String? = null,
)

/** 0..100; `CurveEngine.Presets.carbGammaForGi` clamps to it, so a value outside is a typo. */
private val GI_RANGE = 0..100

/** Whole: the entry slider steps in whole points, and a fractional index reads back as "GI 54.3".
 *  Blank is a cleared index, not a rejected field: a meal may legitimately carry none. */
internal fun giFieldOrNull(text: String): Double? =
    text.takeIf { it.isNotBlank() }?.toIntOrNull()?.toDouble()

internal fun giFieldValid(text: String): Boolean =
    text.isBlank() || text.toIntOrNull()?.let { it in GI_RANGE } == true

/** Of the row's own kind, in the order given: retyping a bolus into a basal is a different dose,
 *  not an edit. */
internal fun offeredInsulins(entry: LoggedEntry, insulins: List<InsulinChoice>): List<InsulinChoice> =
    insulins.filter { it.kind == entry.insulin }

/** Which chip opens selected, or -1. The row keeps only the LABEL it was logged under, and the two
 *  catalogues share no id, so the label is the whole of the match. */
internal fun loggedInsulinIndex(entry: LoggedEntry, offered: List<InsulinChoice>): Int =
    offered.indexOfFirst { it.label == entry.detail }

/**
 * The shift is minutes relative to the stored instant; the repository snaps the result back onto the
 * five-minute grid. A retype rewrites the note as well as the curve — the note is where the insulin's
 * name is kept.
 */
@Composable
fun EditLogDialog(
    entry: LoggedEntry,
    insulins: List<InsulinChoice> = emptyList(),
    onConfirm: (LogEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalT1dmHaptics.current
    var amountText by remember(entry.clientId) { mutableStateOf(fmtNum(entry.amount)) }
    var giText by remember(entry.clientId) { mutableStateOf(entry.gi?.let { fmtGi(it) }.orEmpty()) }
    var noteText by remember(entry.clientId) { mutableStateOf(entry.detail.orEmpty()) }
    var shiftText by remember(entry.clientId) { mutableStateOf("0") }
    // Null until a chip is tapped, so opening the dialog to change an amount cannot silently
    // re-resolve the curve the row is carrying.
    var picked by remember(entry.clientId) { mutableStateOf<InsulinChoice?>(null) }
    val offered = offeredInsulins(entry, insulins)
    val loggedIndex = loggedInsulinIndex(entry, offered)

    // A bout's magnitude is its duration times the carb-equivalent, and the duration is the bout's
    // own — so a replay moves in time and in nothing else.
    val timeOnly = entry.kind == CurveKind.EXERCISE
    val amount = if (timeOnly) entry.amount else amountText.toDoubleOrNull()
    val gi = giFieldOrNull(giText)
    val giValid = giFieldValid(giText)
    val shiftMin = shiftText.toLongOrNull()
    val valid = amount != null && amount > 0.0 && shiftMin != null && giValid

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
                if (entry.kind == CurveKind.CARB) {
                    OutlinedTextField(
                        value = giText,
                        onValueChange = { giText = it.filter(Char::isDigit) },
                        isError = !giValid,
                        label = { Text("GI") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Note") },
                        singleLine = true,
                    )
                }
                if (entry.kind == CurveKind.INSULIN && offered.isNotEmpty()) {
                    val pickedChoice = picked
                    val chipScroll = rememberLazyListState()
                    // Opens on the row's own insulin. A catalogue wider than the dialog otherwise
                    // starts scrolled past the selected chip, reading as nothing selected at all.
                    LaunchedEffect(entry.clientId, loggedIndex) {
                        if (loggedIndex >= 0) chipScroll.scrollToItem(loggedIndex)
                    }
                    // Scrolls rather than wraps: the dialog's text slot carries no scroll of its own,
                    // so a wrapped row past its height is clipped away unreachable.
                    LazyRow(state = chipScroll, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(offered.size) { i ->
                            val choice = offered[i]
                            FilterChip(
                                selected = if (pickedChoice != null) pickedChoice == choice
                                else i == loggedIndex,
                                onClick = {
                                    haptics.perform(HapticEvent.SegmentTick)
                                    picked = choice
                                },
                                label = { Text(choice.label, maxLines = 1) },
                            )
                        }
                    }
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
                    onConfirm(
                        LogEdit(
                            amount = amount!!,
                            gi = gi,
                            insulin = picked,
                            tsMs = entry.tsMs + shiftMin!! * 60_000L,
                            note = noteText.trim().takeIf { it.isNotEmpty() },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}

/** §3.6-F: deleting an OLDER dose lowers assumed IOB with the log-gap mark unmoved, silently
 *  relaxing `Rails.iobCeiling`. Nothing downstream catches that; this dialog is the only guard. */
@Composable
fun DeleteLogDialog(entry: LoggedEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalT1dmHaptics.current
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
