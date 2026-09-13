package com.t1dm.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.InsulinKind
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Confirm-then-commit gate (§3.6-G); additive to §3.6-F, not substitute. DEATH auto-confirms. */

/** 5-min event grid, round-to-nearest (§4-#1); literal since :core:design can't reach :data. */
private const val GRID_MS = 300_000L

/** Re-read the wall clock this often, so the restated time is still true when Log is pressed. */
private const val CLOCK_TICK_MS = 10_000L

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** What a pending log will write, carried by value from the screen that raised the dialog. */
sealed interface PendingLog {
    /** [gi] null for multi-food meals. */
    data class Meal(
        val grams: Double,
        val gi: Double?,
        val detail: String? = null,
        val note: String? = null,
    ) : PendingLog

    /** [typeLabel] must be the RESOLVED curve persisted, or dialog restates the wrong row. */
    data class Dose(
        val units: Double,
        val kind: InsulinKind,
        val typeLabel: String,
    ) : PendingLog
}

internal fun confirmTitle(pending: PendingLog): String = when (pending) {
    is PendingLog.Meal -> "Log this meal?"
    is PendingLog.Dose -> when (pending.kind) {
        InsulinKind.BOLUS -> "Log this bolus?"
        InsulinKind.BASAL -> "Log this basal dose?"
    }
}

/** Pure, unit-testable without composition; [nowMs] is ask's wall clock, grid rounds nearest. */
internal fun confirmFields(pending: PendingLog, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> {
    val slot = Math.floorDiv(nowMs + GRID_MS / 2, GRID_MS) * GRID_MS
    val time = "${hhmm(nowMs, zone)} · 5-min slot ${hhmm(slot, zone)}"
    return when (pending) {
        is PendingLog.Meal -> buildList {
            add("Carbs" to "${fmtNum(pending.grams)} g")
            add("Glycemic index" to (pending.gi?.let { fmtGi(it) } ?: "combined curve (multi-food)"))
            pending.detail?.takeIf { it.isNotBlank() }?.let { add("Foods" to it) }
            pending.note?.takeIf { it.isNotBlank() }?.let { add("Note" to it) }
            add("Time" to time)
        }
        is PendingLog.Dose -> listOf(
            "Dose" to "${fmtNum(pending.units)} U ${kindWord(pending.kind)}",
            "Insulin" to pending.typeLabel,
            "Time" to time,
        )
    }
}

private fun kindWord(kind: InsulinKind) = if (kind == InsulinKind.BOLUS) "bolus" else "basal"

private fun hhmm(ms: Long, zone: ZoneId): String =
    HHMM.format(Instant.ofEpochMilli(ms).atZone(zone))

internal fun fmtNum(v: Double): String =
    if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

/** [onConfirm] writes and clears the entry field; never clear before, or Cancel wipes input. */
@Composable
fun ConfirmLogDialog(
    pending: PendingLog,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalT1dmHaptics.current

    // DEATH fails OPEN: friction is stripped along with the rails. The receipt and Undo still post.
    if (LocalDeathMode.current) {
        LaunchedEffect(pending) {
            haptics.perform(HapticEvent.Confirm)
            onConfirm()
        }
        return
    }

    LaunchedEffect(pending) { haptics.perform(HapticEvent.Warn) }

    val nowMs by produceState(System.currentTimeMillis(), pending) {
        while (true) {
            value = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }
    val fields = confirmFields(pending, nowMs)

    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text(confirmTitle(pending)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                fields.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Confirm); onConfirm() }) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}
