package com.t1dm.core.model

/** [BOLUS] (rapid-acting) is a gamma peaking ~50 min; [BASAL] (long-acting) a broad Bateman,
 *  near-flat once tiled. */
enum class InsulinKind { BOLUS, BASAL }

/**
 * Self-describing: it carries the exact curve parameters, so a dose logged against it reconstructs
 * the same PK-action curve even if defaults later change. A [BOLUS] fills [k]/[theta] (gamma), a
 * [BASAL] [kaPerHour]/[kePerHour] (Bateman). [customCurve] is per-5-min buckets summing to 1.0 and
 * overrides the analytic curve; [durationMin] is the DIA.
 */
data class InsulinType(
    val id: Long,
    val name: String,
    val kind: InsulinKind,
    val durationMin: Double,
    val k: Double? = null,
    val theta: Double? = null,
    val kaPerHour: Double? = null,
    val kePerHour: Double? = null,
    val customCurve: List<Double>? = null,
    val builtin: Boolean = false,
)
