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

/**
 * The confirm-then-commit gate every logged meal and every logged dose passes through (§3.6-G). A carb or
 * insulin row is a *clinical journal entry*: it is what IOB/COB is computed from (§3.6-F — "IOB from
 * logged doses only"), so a fat-fingered 40 U instead of 4 U silently poisons every rail that reads it
 * until it is noticed. The gate is deliberately dumb — it restates the row about to be written, in the
 * writer's own terms, and asks once.
 *
 * It is **friction, not a rail**. It scores nothing, blocks nothing, and must never be mistaken for
 * one of the §3.6 fail-closed guards: on the calculator surface it sits *after* the §3.6-F
 * acknowledge/mandatory-confirmation checkboxes and is additive to them, never a substitute (those
 * gate the Accept button's `enabled`; this gates what the press does). Because it is friction rather
 * than protection, DEATH mode — the deliberate fail-OPEN override — passes straight through it: the
 * dialog auto-confirms and renders nothing. The commit receipt (and its Undo) is not a warning and
 * survives DEATH untouched.
 *
 * Styling is bare M3 [AlertDialog] with no colour/shape/elevation overrides, matching the two
 * pre-existing dialogs in the app (`RollConfirmDialog`, the model-removal confirm): sentence-case
 * title ending in `?`, a field table in `text`, a verb-named confirm button, "Cancel" to dismiss.
 */

/** The 5-min event grid every logged row is snapped onto, round-to-nearest (§4-#1). Restated here as
 *  a literal rather than depended upon: `:core:design` must not reach into `:data` for `GRID_MS`. */
private const val GRID_MS = 300_000L

/** Re-read the wall clock this often while the dialog is up, so the restated time (and the grid slot
 *  derived from it) is still true at the instant Log is pressed rather than when it was raised. */
private const val CLOCK_TICK_MS = 10_000L

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What a pending log will write. Carried by value from the screen that raised the dialog, so the
 * feature modules stay stateless and callback-driven — nothing here knows about Room, the outbox, or
 * the `:app` writers.
 */
sealed interface PendingLog {
    /**
     * A carb entry. [gi] is null for a multi-food builder meal, whose appearance (Ra) curve is the
     * resolved combination of its components rather than one glycemic index. [detail] is the
     * component breakdown, shown so the confirmation restates the *whole* row. [photoAttached] must
     * reflect a photo that will be uploaded alongside — the upload is a direct POST with no delete
     * endpoint, so the receipt has to be honest that undoing the meal does not recall it.
     */
    data class Meal(
        val grams: Double,
        val gi: Double?,
        val detail: String? = null,
        val photoAttached: Boolean = false,
    ) : PendingLog

    /**
     * An insulin entry. [typeLabel] must be the **resolved** curve the writer will persist — the
     * catalogue preset chosen on the insulin panel, or the custom type picked in the builder — and
     * the writer must honour it, or the dialog restates a row that is not the one written. That gap
     * was real once: the panel offered a preset the writer discarded in favour of a Settings
     * selection, and this line was the only place the two could be seen to disagree.
     */
    data class Dose(
        val units: Double,
        val kind: InsulinKind,
        val typeLabel: String,
    ) : PendingLog
}

/** Sentence-case, `?`-terminated, naming the thing being written. */
internal fun confirmTitle(pending: PendingLog): String = when (pending) {
    is PendingLog.Meal -> "Log this meal?"
    is PendingLog.Dose -> when (pending.kind) {
        InsulinKind.BOLUS -> "Log this bolus?"
        InsulinKind.BASAL -> "Log this basal dose?"
    }
}

/**
 * The label→value restatement of the row, in the order it reads best aloud. Pure, so the wording is
 * unit-testable without a composition; [nowMs] is the wall clock at the moment of the ask and the
 * grid line is derived from it with the same round-to-nearest snap the repository writer applies.
 */
internal fun confirmFields(pending: PendingLog, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> {
    val slot = Math.floorDiv(nowMs + GRID_MS / 2, GRID_MS) * GRID_MS
    val time = "${hhmm(nowMs, zone)} · 5-min slot ${hhmm(slot, zone)}"
    return when (pending) {
        is PendingLog.Meal -> buildList {
            add("Carbs" to "${fmtNum(pending.grams)} g")
            add("Glycemic index" to (pending.gi?.let { fmtNum(it) } ?: "combined curve (multi-food)"))
            pending.detail?.takeIf { it.isNotBlank() }?.let { add("Foods" to it) }
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

/** Integral values read as "45", not "45.0"; a half unit still reads as "4.5". */
internal fun fmtNum(v: Double): String =
    if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

/**
 * Raise the confirmation for [pending]. [onConfirm] performs the write (and is where the caller
 * clears its entry field — never before, or a Cancel would silently wipe what was typed);
 * [onDismiss] abandons it.
 *
 * Haptics follow the vocabulary in [T1dmHaptics]: [HapticEvent.Warn] on raise (this is the app
 * asking the human to look at something consequential), [HapticEvent.Confirm] on accept,
 * [HapticEvent.Reject] on cancel or a scrim/back dismissal. The heavier [HapticEvent.Commit] belongs
 * to the receipt, once the row is actually persisted.
 */
@Composable
fun ConfirmLogDialog(
    pending: PendingLog,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalT1dmHaptics.current

    // DEATH fails OPEN by design: it strips warnings and rails wholesale. A confirmation prompt is
    // friction rather than protection, so it is stripped with them — the press writes straight
    // through. The receipt + Undo still post, being a record rather than a warning.
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
