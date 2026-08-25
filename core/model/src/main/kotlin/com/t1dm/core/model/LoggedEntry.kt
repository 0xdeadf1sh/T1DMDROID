package com.t1dm.core.model

/**
 * What a time-axis marker needs, and no more: the drawing layer is handed no amounts, no curve
 * parameters and no row ids. A marker is deliberately not an identity — two rows can share a 5-min
 * slot and a channel — so a tap is answered with POSITIONS in the list the panel was given.
 */
data class LogMarker(
    val tsMs: Long,
    val kind: CurveKind,
)

/**
 * [amount] is grams of carbohydrate or units of insulin, per [kind]; [gi] is null for a dose and for
 * a multi-food builder meal, which has no single index. [rowId] is the `logged_meal`/`logged_dose`
 * rowid; [clientId] is unique across both tables, hence the list key. [updatedAtMs] is the wire
 * ordering key, and [mutatedAtMs] null if the row was never edited.
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
