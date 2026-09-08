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

/** [amount]: grams/units/min; nulls: [gi] clears index, [insulin] keeps curve, [note] CARB-only */
data class LogEdit(
    val amount: Double,
    val gi: Double?,
    val insulin: InsulinChoice?,
    val tsMs: Long,
    val note: String? = null,
)

/** 0..100; `CurveEngine.Presets.carbGammaForGi` clamps to it, so a value outside is a typo. */
private val GI_RANGE = 0..100

/** Whole: slider steps whole (fractional reads "GI 54.3"); blank = cleared, not rejected. */
internal fun giFieldOrNull(text: String): Double? =
    text.takeIf { it.isNotBlank() }?.toIntOrNull()?.toDouble()

internal fun giFieldValid(text: String): Boolean =
    text.isBlank() || text.toIntOrNull()?.let { it in GI_RANGE } == true

/** Of the row's own kind, order preserved; retyping bolus→basal is a different dose, not edit. */
internal fun offeredInsulins(entry: LoggedEntry, insulins: List<InsulinChoice>): List<InsulinChoice> =
    insulins.filter { it.kind == entry.insulin }

/** Which chip opens selected, or -1; row keeps only the LABEL (catalogues share no id). */
internal fun loggedInsulinIndex(entry: LoggedEntry, offered: List<InsulinChoice>): Int =
    offered.indexOfFirst { it.label == entry.detail }

/** Shift in minutes from stored instant, snapped to 5-min grid; retype rewrites note too. */
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
    // Null until tapped, so opening to edit an amount can't silently re-resolve the curve.
    var picked by remember(entry.clientId) { mutableStateOf<InsulinChoice?>(null) }
    val offered = offeredInsulins(entry, insulins)
    val loggedIndex = loggedInsulinIndex(entry, offered)

    // Bout magnitude = duration × carb-equivalent (own duration); a replay moves time only.
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
                    // Opens on row's insulin; else wide catalogue scrolls past the selected chip.
                    LaunchedEffect(entry.clientId, loggedIndex) {
                        if (loggedIndex >= 0) chipScroll.scrollToItem(loggedIndex)
                    }
                    // Scrolls not wraps: text slot has no scroll; overflow row is unreachable.
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

/** §3.6-F: deleting an OLDER dose silently relaxes iobCeiling; this dialog is the only guard. */
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
