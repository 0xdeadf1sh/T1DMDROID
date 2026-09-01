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

/** The confirm-then-commit gate every logged meal and dose passes through (§3.6-G). Friction, not a
 *  rail: additive to the §3.6-F checkboxes, never a substitute. DEATH mode passes straight through —
 *  the dialog auto-confirms and renders nothing. */

/** The 5-min event grid, round-to-nearest (§4-#1). A literal here: `:core:design` must not reach
 *  into `:data` for `GRID_MS`. */
private const val GRID_MS = 300_000L

/** Re-read the wall clock this often, so the restated time is still true when Log is pressed. */
private const val CLOCK_TICK_MS = 10_000L

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** What a pending log will write, carried by value from the screen that raised the dialog. */
sealed interface PendingLog {
    /** [gi] is null for a multi-food builder meal, whose Ra curve combines its components. [detail]
     *  is the components themselves; [note] the patient's own text, which the row stores and syncs.
     *  [photoAttached] must reflect a photo that will upload: there is no delete endpoint, so
     *  undoing the meal does not recall it. */
    data class Meal(
        val grams: Double,
        val gi: Double?,
        val detail: String? = null,
        val note: String? = null,
        val photoAttached: Boolean = false,
    ) : PendingLog

    /** [typeLabel] must be the RESOLVED curve the writer will persist, and the writer must honour
     *  it, or the dialog restates a row that is not the one written. */
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

/** Pure, so the wording is unit-testable without a composition. [nowMs] is the wall clock at the
 *  moment of the ask; the grid line uses the writer's own round-to-nearest snap. */
internal fun confirmFields(pending: PendingLog, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> {
    val slot = Math.floorDiv(nowMs + GRID_MS / 2, GRID_MS) * GRID_MS
    val time = "${hhmm(nowMs, zone)} · 5-min slot ${hhmm(slot, zone)}"
    return when (pending) {
        is PendingLog.Meal -> buildList {
            add("Carbs" to "${fmtNum(pending.grams)} g")
            add("Glycemic index" to (pending.gi?.let { fmtGi(it) } ?: "combined curve (multi-food)"))
            pending.detail?.takeIf { it.isNotBlank() }?.let { add("Foods" to it) }
            pending.note?.takeIf { it.isNotBlank() }?.let { add("Note" to it) }
            add("Photo" to if (pending.photoAttached) "attached — uploads with the meal" else "none")
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

/** [onConfirm] performs the write, and is where the caller clears its entry field — never before,
 *  or a Cancel would silently wipe what was typed. */
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
