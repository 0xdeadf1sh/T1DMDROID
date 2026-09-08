package com.t1dm.core.model

/** Just what drawing needs: no amounts/curve/row id; a tap answers with list POSITIONS. */
data class LogMarker(
    val tsMs: Long,
    val kind: CurveKind,
)

data class LoggedEntry(
    val rowId: Long,                   // logged_meal/logged_dose/logged_exercise rowid
    val clientId: String,              // unique across all three tables; the list key
    val kind: CurveKind,
    val insulin: InsulinKind?,
    val tsMs: Long,
    val tzOffsetMin: Int,
    val amount: Double,                // carb g / insulin U / exercise min (§5: duration only)
    val gi: Double?,                   // null for a dose, or a multi-food meal (no single index)
    val detail: String?,
    val updatedAtMs: Long,             // wire ordering key
    val mutatedAtMs: Long?,            // null if the row was never edited
) {
    val edited: Boolean get() = mutatedAtMs != null

    val marker: LogMarker get() = LogMarker(tsMs, kind)
}
