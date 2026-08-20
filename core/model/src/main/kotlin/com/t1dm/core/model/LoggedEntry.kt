package com.t1dm.core.model

/**
 * One logged event reduced to what a time-axis marker needs: WHEN and WHICH channel. The graph
 * draws markers; it must not be handed the amounts, the curve parameters or the row ids it would
 * then be free to render or mutate.
 *
 * There is deliberately no delivery state here any more. A mark used to carry one, and the claim it
 * made was honest-but-unproven: the outbox has no SENT state, so an absent queue row means "sent",
 * "permanently rejected" or "size-evicted" alike. Nothing replaces it — a delete no longer depends
 * on whether the push has drained, so the distinction bought the reader nothing and cost a
 * continuously animating frame loop. The queue's own depth and age are on the Network panel, which
 * is where a statement about the server belongs.
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
)

/**
 * One row of the Logs panel: an insulin dose or a carbohydrate entry the user logged.
 *
 * Every row is editable and deletable, unconditionally: a deletion travels as a tombstone on the
 * same upsert the create rode, so it is ordered against the create by `updatedAt` and cannot be
 * overtaken by a redelivery. There is therefore no state left for this type to carry about whether
 * a delete is still available.
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
 * @param updatedAtMs the row's current authoring stamp, bumped by every edit; the wire ordering key.
 * @param mutatedAtMs when the row was last edited, or null if it never has been. A surface showing a
 *                    figure derived from this row must be able to say the numbers were changed after
 *                    the fact.
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
    val updatedAtMs: Long,
    val mutatedAtMs: Long?,
) {
    val edited: Boolean get() = mutatedAtMs != null

    val marker: LogMarker get() = LogMarker(tsMs, kind)
}
