package com.t1dm.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.LoggedEntry

/**
 * [amount] is grams, units or minutes by [LoggedEntry.kind]. [gi] 0..100, null clearing the recorded
 * index; [insulin] null leaves the dose's stored PK curve untouched, since a type is re-resolved
 * only when one is picked and re-resolving discards a hand-drawn curve.
 */
data class LogEdit(
    val amount: Double,
    val gi: Double?,
    val insulin: InsulinType?,
    val tsMs: Long,
)

/** 0..100; `CurveEngine.Presets.carbGammaForGi` clamps to it, so a value outside is a typo. */
private val GI_RANGE = 0.0..100.0

/** Blank is a cleared index, not a rejected field: a meal may legitimately carry none. */
internal fun giFieldOrNull(text: String): Double? = text.takeIf { it.isNotBlank() }?.toDoubleOrNull()

internal fun giFieldValid(text: String): Boolean =
    text.isBlank() || giFieldOrNull(text)?.let { it in GI_RANGE } == true

/**
 * The shift is minutes relative to the stored instant; the repository snaps the result back onto the
 * five-minute grid. A retype rewrites the note as well as the curve — the note is where the insulin's
 * name is kept.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditLogDialog(
    entry: LoggedEntry,
    insulinTypes: List<InsulinType> = emptyList(),
    onConfirm: (LogEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalT1dmHaptics.current
    var amountText by remember(entry.clientId) { mutableStateOf(fmtNum(entry.amount)) }
    var giText by remember(entry.clientId) { mutableStateOf(entry.gi?.let { fmtNum(it) }.orEmpty()) }
    var shiftText by remember(entry.clientId) { mutableStateOf("0") }
    // Null until a chip is tapped, so opening the dialog to change an amount cannot silently
    // re-resolve the curve the row is carrying.
    var picked by remember(entry.clientId) { mutableStateOf<InsulinType?>(null) }

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
                        onValueChange = { giText = it },
                        isError = !giValid,
                        label = { Text("GI") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                if (entry.kind == CurveKind.INSULIN && insulinTypes.isNotEmpty()) {
                    val pickedType = picked
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        insulinTypes.forEach { type ->
                            FilterChip(
                                // With nothing picked, the note names the type the dose was logged
                                // against — the row stores no id to select by.
                                selected = if (pickedType != null) pickedType.id == type.id
                                else type.name == entry.detail,
                                onClick = {
                                    haptics.perform(HapticEvent.SegmentTick)
                                    picked = type
                                },
                                label = { Text(type.name) },
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
