package com.t1dm.app.di

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The commit half of the confirm-then-commit contract: a [LogHandle] is everything needed to *show*
 * that a meal/dose row was written and to take it back again.
 *
 * It exists because the five write surfaces used to be write-and-forget — every `AppContainer` entry
 * point returned `Unit`, discarding both the persisted entity and the outbox rowid, so nothing
 * downstream could name the row it had just created. The acknowledgement lives at the `:app` seam
 * rather than in the feature modules deliberately: those stay stateless and callback-driven, with no
 * `SnackbarHostState`, no `:data` types, and no knowledge that an outbox exists.
 */
enum class LoggedEventKind { MEAL, DOSE }

/**
 * @param rowId    the `logged_meal` / `logged_dose` rowid the repository writer returned.
 * @param clientId the phone-minted event id (§3.2) — the server's idempotency key, and what a WS
 *                 catch-up would re-hydrate the event by if the push has already drained.
 * @param tsMs     the PERSISTED (grid-snapped) event time, not the wall clock at the press.
 * @param outboxId the enqueued push's rowid, or null when this path enqueues nothing at all.
 * @param dedupKey the enqueued push's dedupKey, carried as the guard against a recycled [outboxId].
 * @param label    a human restatement of exactly what was written ("45 g (GI 60)", "4 U bolus · …").
 * @param caveats  things an Undo provably cannot take back, appended verbatim to the undo receipt.
 */
data class LogHandle(
    val kind: LoggedEventKind,
    val rowId: Long,
    val clientId: String,
    val tsMs: Long,
    val outboxId: Long?,
    val dedupKey: String?,
    val label: String,
    val caveats: List<String> = emptyList(),
)

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun hhmm(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    HHMM.format(Instant.ofEpochMilli(ms).atZone(zone))

/** The confirmation line: what was written and the grid slot it landed in. */
fun logReceipt(handle: LogHandle, zone: ZoneId = ZoneId.systemDefault()): String =
    "Logged ${handle.label} at ${hhmm(handle.tsMs, zone)}"

/**
 * The follow-up line after an Undo.
 *
 * There is one case now, not four. A deletion travels as a tombstone on the same upsert the create
 * rode, ordered against it by `updated_at`, so it lands whatever the push had already done — there
 * is no longer a "may have landed" or an "already sent, not deleted" to report, and no receipt
 * hedging about a server copy that comes back.
 *
 * A Logs-panel delete says nothing at all: the row leaving the list IS the feedback, and a snackbar
 * restating it would be the reassurance the house style cuts.
 */
fun undoReceipt(handle: LogHandle): String =
    (listOf("Removed ${handle.label}.") + handle.caveats).joinToString(" ")
