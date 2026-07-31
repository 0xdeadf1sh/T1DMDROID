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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.LoggedEntry
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What a logged row SAYS, in one vocabulary, for every surface that has to restate one.
 *
 * The Logs panel's list, its delete confirmation and the BG panel's marker dialog all describe the
 * same rows, and each used to spell the description itself — three renderings of one fact, which is
 * how "45 g carbs" becomes "45g carbs" on one screen and "45.0 g" on another. The wording lives here
 * once, beside [ConfirmLogDialog]'s restatement of the row about to be *written*, so the sentence a
 * user reads before a log and the sentence they read after it are built by the same code.
 *
 * **Withholding beats inventing.** A value the row does not carry is [NOT_RECORDED] and never a
 * default: a builder meal has a combined appearance curve and no single glycemic index. Printing a
 * plausible number would be a fabrication on a clinical surface, and nothing downstream — no
 * calculator, no rail, no forecast — would ever contradict it.
 *
 * **And a field says no more than its column guarantees.** [LoggedEntry.detail] is the row's own free
 * text: the local writers happen to fill a dose's with the insulin they resolved, but the debug
 * backdate hook writes `backdated` into the same column and `SPEC/http-api.md` types the wire's `note`
 * as free text, so a row authored by another client carries whatever that client wrote. Labelling it
 * "Insulin" would state which insulin was injected on the authority of a column that never promised
 * one — a worse failure than the absent case, because it passes every emptiness guard. It is labelled
 * as what it is, and omitted entirely when the row has none: an absent note is not a withheld
 * measurement, and a row of [NOT_RECORDED] against it would be noise on a dialog that fits what it
 * shows.
 */

/** What a field reads when the row genuinely does not carry it. */
private const val NOT_RECORDED = "not recorded"

private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")

/** The row's headline: grams of carbohydrate, or units of insulin with the shape it acts through. */
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
}

/** Rendered in the offset the row was WRITTEN at, not the reader's current one: a dose taken abroad
 *  keeps the wall-clock time the user took it at. */
fun logTimeLabel(tsMs: Long, tzOffsetMin: Int): String =
    Instant.ofEpochMilli(tsMs).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).format(TS_FMT)

/** The row's second line where a list has one: its glycemic index, its note, or nothing at all. A
 *  meal prefers the index and falls back to the note, so a row that carries only a note still has a
 *  reader — the two live in separate columns and nothing else on any surface prints a meal's. */
fun logDetailLabel(entry: LoggedEntry): String? = when (entry.kind) {
    CurveKind.CARB -> entry.gi?.let { "GI ${fmtNum(it)}" } ?: logNote(entry)
    CurveKind.INSULIN -> logNote(entry)
}

/** The row's note, or null where it has none — blank counts as none. */
private fun logNote(entry: LoggedEntry): String? = entry.detail?.takeIf { it.isNotBlank() }

/**
 * The label→value restatement of a logged row: what it was, and when. Pure, so the wording is
 * unit-testable without a composition.
 *
 * The amount is NOT among them — it is the block's headline — so the fields are exactly what a mark on
 * the BG panel cannot show: the glycemic index a meal was logged under, whatever the row noted, and
 * the instant. The index is stated even when absent, because a reader asks a meal for one; the note is
 * simply left out when there is none, because nothing was withheld.
 */
internal fun logEntryFields(entry: LoggedEntry): List<Pair<String, String>> = buildList {
    if (entry.kind == CurveKind.CARB) {
        add("Glycemic index" to (entry.gi?.let { fmtNum(it) } ?: NOT_RECORDED))
    }
    logNote(entry)?.let { add("Note" to it) }
    add("Time" to logTimeLabel(entry.tsMs, entry.tzOffsetMin))
}

/**
 * The dialog's title. One entry names itself; several are counted, because a mark that stands for a
 * whole evening cannot be titled by any one of them and the count is what the user is owed before
 * reading the list.
 */
internal fun logEntriesTitle(entries: List<LoggedEntry>): String =
    if (entries.size == 1) logAmountLabel(entries.single()) else "${entries.size} logs"

/**
 * What one mark on the BG panel stands for: every log behind it, whether that is one entry, a lane's
 * worth that collided into a single glyph at this zoom, or the carbs and the insulin standing in the
 * two lanes of one column.
 *
 * Read-only. It restates rows and offers nothing to press but Close: the marker layer is a record of
 * what happened, and deleting a log is the Logs panel's affordance, gated on a state this dialog does
 * not show. Styling is the bare M3 [AlertDialog] every dialog in the app uses.
 *
 * **The list scrolls, and lazily.** How many rows one mark stands for is a fact about the ZOOM, not
 * about the day: the clustering distance is a fixed pixel span, so at a week-wide view a single glyph
 * can stand for the whole feed. M3's `text` slot is a `Modifier.weight(1f, fill = false)` box with no
 * scroll of its own, so a plain column past the dialog's height is clipped away with nothing to say it
 * is there — the title would count rows the dialog could not show. A [LazyColumn] answers both halves:
 * every member is reachable, and only the visible few are ever composed, so the tap that opens a
 * hundred rows costs what the tap that opens two does.
 */
@Composable
fun LoggedEntryDialog(entries: List<LoggedEntry>, onDismiss: () -> Unit) {
    if (entries.isEmpty()) return
    val haptics = LocalT1dmHaptics.current
    val many = entries.size > 1
    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text(logEntriesTitle(entries)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries.size, key = { entries[it].clientId }) { i ->
                    val entry = entries[i]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // The headline is the title when there is only one row, so printing it again
                        // would say the same thing twice on the commonest tap of all.
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
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Tap); onDismiss() }) { Text("Close") }
        },
    )
}
