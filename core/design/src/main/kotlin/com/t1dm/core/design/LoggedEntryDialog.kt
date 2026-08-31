package com.t1dm.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.ExerciseKind
import com.t1dm.core.model.LoggedEntry
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One vocabulary for every surface that restates a logged row. A value the row does not carry reads
 * [NOT_RECORDED], never a default. [LoggedEntry.detail] is free text — `SPEC/http-api.md` types the
 * wire's `note` as such — so it is labelled as a note, never as the insulin it happens to name.
 */

private const val NOT_RECORDED = "not recorded"

private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")

fun logAmountLabel(entry: LoggedEntry): String = when (entry.kind) {
    CurveKind.CARB -> "${fmtNum(entry.amount)} g carbs"
    CurveKind.INSULIN -> {
        val shape = when (entry.insulin) {
            InsulinKind.BOLUS -> " bolus"
            InsulinKind.BASAL -> " basal"
            null -> ""
        }
        "${fmtNum(entry.amount)} U$shape"
    }
    // Minutes, not the grams they resolve to: duration is what the patient chose and what §5 scales
    // the disposal by. The kind rides here because the headline is the whole row for a replay.
    CurveKind.EXERCISE -> "${fmtNum(entry.amount)} min" + entry.detail?.let { " ${it.lowercase()}" }.orEmpty()
}

fun exerciseKindLabel(kind: ExerciseKind): String = when (kind) {
    ExerciseKind.WALK -> "Walk"
    ExerciseKind.RUN -> "Run"
    ExerciseKind.OTHER -> "Other"
}

/** Unknown names read as [ExerciseKind.OTHER]: `kind` is raw TEXT so a bout a later build wrote
 *  still labels. */
fun exerciseKindLabel(kind: String): String =
    exerciseKindLabel(runCatching { ExerciseKind.valueOf(kind) }.getOrNull() ?: ExerciseKind.OTHER)

/** In the offset the row was WRITTEN at, not the reader's current one. */
fun logTimeLabel(tsMs: Long, tzOffsetMin: Int): String =
    Instant.ofEpochMilli(tsMs).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).format(TS_FMT)

fun logDetailLabel(entry: LoggedEntry): String? = when (entry.kind) {
    CurveKind.CARB -> entry.gi?.let { "GI ${fmtNum(it)}" } ?: logNote(entry)
    CurveKind.INSULIN -> logNote(entry)
    // Already in the headline; repeating it under would read as a second fact.
    CurveKind.EXERCISE -> null
}

private fun logNote(entry: LoggedEntry): String? = entry.detail?.takeIf { it.isNotBlank() }

/** The amount is not among them — it is the block's headline. A meal's index is stated even when
 *  absent; an absent note is simply left out, since nothing was withheld. */
internal fun logEntryFields(entry: LoggedEntry): List<Pair<String, String>> = buildList {
    if (entry.kind == CurveKind.CARB) {
        add("Glycemic index" to (entry.gi?.let { fmtNum(it) } ?: NOT_RECORDED))
    }
    if (entry.kind != CurveKind.EXERCISE) logNote(entry)?.let { add("Note" to it) }
    add("Time" to logTimeLabel(entry.tsMs, entry.tzOffsetMin))
    // Only when true: an unedited row has nothing to disclose.
    if (entry.edited) add("Edited" to logTimeLabel(entry.mutatedAtMs!!, entry.tzOffsetMin))
}

internal fun logEntriesTitle(entries: List<LoggedEntry>): String =
    if (entries.size == 1) logAmountLabel(entries.single()) else "${entries.size} logs"

/**
 * A null [onEdit] or [onDelete] leaves that affordance off rather than offering one that refuses.
 * Either replaces this dialog while it is open, and confirming dismisses the whole stack: the caller
 * captured [entries] at the tap, so a row's fields are stale the moment it is written.
 *
 * Lazy because M3's `text` slot carries no scroll of its own, and at a week-wide zoom one mark can
 * stand for the whole feed.
 */
@Composable
fun LoggedEntryDialog(
    entries: List<LoggedEntry>,
    insulinTypes: List<InsulinType> = emptyList(),
    onEdit: ((LoggedEntry, LogEdit) -> Unit)? = null,
    onDelete: ((LoggedEntry) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    if (entries.isEmpty()) return
    val haptics = LocalT1dmHaptics.current
    val many = entries.size > 1
    var editing by remember { mutableStateOf<LoggedEntry?>(null) }
    var deleting by remember { mutableStateOf<LoggedEntry?>(null) }

    editing?.let { entry ->
        EditLogDialog(
            entry = entry,
            insulinTypes = insulinTypes,
            onConfirm = { edit -> editing = null; onEdit?.invoke(entry, edit); onDismiss() },
            onDismiss = { editing = null },
        )
        return
    }
    deleting?.let { entry ->
        DeleteLogDialog(
            entry = entry,
            onConfirm = { deleting = null; onDelete?.invoke(entry); onDismiss() },
            onDismiss = { deleting = null },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text(logEntriesTitle(entries)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries.size, key = { entries[it].clientId }) { i ->
                    val entry = entries[i]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // With one row the headline is already the dialog's title.
                        if (many) {
                            Text(
                                logAmountLabel(entry),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        logEntryFields(entry).forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (onEdit != null || onDelete != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (onEdit != null) {
                                    TextButton(onClick = {
                                        haptics.perform(HapticEvent.Tap)
                                        editing = entry
                                    }) { Text("Edit") }
                                }
                                if (onDelete != null) {
                                    TextButton(onClick = {
                                        haptics.perform(HapticEvent.Warn)
                                        deleting = entry
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Tap); onDismiss() }) { Text("Close") }
        },
    )
}
