package com.t1dm.core.model

/** Not an identity: rows can share a slot+channel, so taps are answered by list POSITION. */
data class LogMarker(
    val tsMs: Long,
    val kind: CurveKind,
)

/** [amount]: grams of carb, units of insulin, or minutes of exercise — meaning is set by [kind]. */
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
