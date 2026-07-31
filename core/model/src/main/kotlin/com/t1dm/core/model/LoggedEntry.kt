package com.t1dm.core.model

/**
 * How far a logged carb/insulin row has got on its way to the server.
 *
 * There are exactly two states, and the boundary between them is an ACKNOWLEDGEMENT, never an
 * elapsed duration: `QueueDrainer` DELETEs an outbox row on a 2xx and the queue has no SENT state,
 * so the presence of a row under the event's dedup key is the only "not yet accepted" signal the
 * phone has. A log therefore stays [COMMITTED] for as long as the phone is offline, however long
 * that is, and no timer may promote it.
 *
 * The converse reading — absence ⇒ [DELIVERED] — is the honest one but not a proof. `QueueDrainer`
 * also deletes a row on a permanent 4xx, and the hard size cap can evict one (MEAL/DOSE are exempt
 * from age-eviction and are the last kinds the cap reaches, so this needs a queue that has overrun
 * with nothing regenerable left in it). Both leave exactly what a successful send leaves. The same
 * ambiguity is already what `PushWithdrawal.ALREADY_SENT` rests on in `:data`.
 */
enum class LogState { COMMITTED, DELIVERED }

/**
 * One logged event reduced to what a time-axis marker needs: WHEN, WHICH channel, and whether it is
 * still withdrawable. The graph draws markers; it must not be handed the amounts, the curve
 * parameters or the row ids it would then be free to render or mutate.
 *
 * That holds even though a mark can now be tapped for what it stands for. The panel answers a tap with
 * POSITIONS in the list it was given, and its caller — which reduced [LoggedEntry] to this in the first
 * place — resolves them back; nothing that could be rendered or acted on crosses into the drawing
 * layer. A marker is deliberately not an identity: two rows can share a 5-min slot and a channel, so
 * one could not name a row even if it were asked to.
 *
 * [kind] is the model's own channel vocabulary ([CurveKind.CARB] / [CurveKind.INSULIN]) rather than a
 * second carb-or-insulin enum, so a marker and the [CurveEvent] it stands for are labelled the same.
 */
data class LogMarker(
    val tsMs: Long,
    val kind: CurveKind,
    val state: LogState,
)

/**
 * One row of the Logs panel: an insulin dose or a carbohydrate entry the user logged, with the
 * server-acknowledgement state that decides whether it may still be deleted.
 *
 * A value type in the dependency-free model layer, like every other feature-screen read model: the
 * panel sees no Room entity, no outbox, and no dedup key. `:app` joins the two event tables against
 * the queue (the dedup-key format lives in `:sync`, which owns it) and hands the result down.
 *
 * @param rowId    the `logged_meal` / `logged_dose` rowid — what a delete names.
 * @param clientId the phone-minted event id (§3.2); unique across both tables, hence the list key.
 * @param insulin  BOLUS or BASAL for [CurveKind.INSULIN]; null for a carb entry.
 * @param amount   grams of carbohydrate, or units of insulin, per [kind].
 * @param gi       the meal's glycemic index, or null — for a dose, and for a multi-food builder meal,
 *                 whose appearance curve is the resolved combination of its components and which
 *                 therefore has no single index. A NUMBER rather than a rendered phrase: a reader that
 *                 has to say "not recorded" must be able to tell an absent index from a present one,
 *                 and one that formats it must not have to parse a string back apart to do so.
 * @param detail   the row's own note — the resolved insulin type for a dose — or null.
 */
data class LoggedEntry(
    val rowId: Long,
    val clientId: String,
    val kind: CurveKind,
    val insulin: InsulinKind?,
    val tsMs: Long,
    val tzOffsetMin: Int,
    val amount: Double,
    val gi: Double?,
    val detail: String?,
    val state: LogState,
) {
    val committed: Boolean get() = state == LogState.COMMITTED

    val marker: LogMarker get() = LogMarker(tsMs, kind, state)
}
