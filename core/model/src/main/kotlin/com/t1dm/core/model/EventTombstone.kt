package com.t1dm.core.model

/**
 * Carries everything the wire needs to express the deletion, is the filter hydration refuses an
 * id-keyed insert against, and is a term in the event high-water mark. [updatedAt] is authored
 * strictly newer than the row it retires (`SPEC/invariants.md` §7). [actingUntilMs] is a dose's
 * action-curve end, null for a meal.
 */
data class EventTombstone(
    val clientId: String,
    val kind: CurveKind,
    val tsMs: Long,
    val tzOffsetMin: Int,
    val updatedAt: Long,
    val actingUntilMs: Long? = null,
)
