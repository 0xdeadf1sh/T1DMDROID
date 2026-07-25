package com.t1dm.app.di

import com.t1dm.data.PushWithdrawal
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
 * The follow-up line after an Undo. It reports the *local* delete as done (it is — the row is gone
 * inside one transaction) and the *server* copy exactly as truthfully as [PushWithdrawal] permits:
 * a drained push cannot be recalled (the server API has no DELETE) and `CatchUpCoordinator`
 * re-hydrates it by `clientId` on the next WS reconnect, so promising a clean unwind there would be
 * a lie the next sync exposes.
 */
fun undoReceipt(handle: LogHandle, outcome: PushWithdrawal): String {
    val head = when (outcome) {
        PushWithdrawal.NEVER_QUEUED, PushWithdrawal.WITHDRAWN ->
            "Removed ${handle.label} — nothing was sent."
        PushWithdrawal.RACED ->
            "Removed ${handle.label} here — its upload was already in flight and may have landed."
        PushWithdrawal.ALREADY_SENT ->
            "Removed ${handle.label} here — the server copy was already sent and returns on the next sync."
    }
    return (listOf(head) + handle.caveats).joinToString(" ")
}
