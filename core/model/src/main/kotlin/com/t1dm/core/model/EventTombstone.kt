package com.t1dm.core.model

/** updatedAt strictly newer than the row retired (§7); actingUntilMs is dose end, null for meal. */
data class EventTombstone(
    val clientId: String,
    val kind: CurveKind,
    val tsMs: Long,
    val tzOffsetMin: Int,
    val updatedAt: Long,
    val actingUntilMs: Long? = null,
)
