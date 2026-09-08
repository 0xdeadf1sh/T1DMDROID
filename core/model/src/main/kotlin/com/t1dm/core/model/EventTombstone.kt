package com.t1dm.core.model

/** Deletion marker; updatedAt strictly newer (SPEC §7); actingUntilMs is dose end, null=meal. */
data class EventTombstone(
    val clientId: String,
    val kind: CurveKind,
    val tsMs: Long,
    val tzOffsetMin: Int,
    val updatedAt: Long,
    val actingUntilMs: Long? = null,
)
