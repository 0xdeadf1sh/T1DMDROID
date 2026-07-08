package com.t1dm.core.model

/**
 * Which PK-action shape an insulin type produces ([model-io-curves]). A [BOLUS] (rapid-acting,
 * e.g. Novorapid/aspart) is a gamma peaking ~50 min; a [BASAL] (long-acting, e.g. Lantus/Tresiba)
 * is a broad Bateman, near-flat once tiled. This mirrors the storage `DoseKind` but lives in the
 * dependency-free model layer so the insulin builder never reaches into `:data`.
 */
enum class InsulinKind { BOLUS, BASAL }

/**
 * A configured insulin type for the builder (PLAN.private.md Phase 4, `:feature:insulin`): the
 * quick presets (Novorapid gamma; Lantus/Tresiba Bateman) plus any user-defined custom type. It is
 * **self-describing** — it carries the exact curve parameters, so a dose logged against it
 * reconstructs the same PK-action curve even if defaults later change.
 *
 * A [BOLUS] fills [k]/[theta] (gamma); a [BASAL] fills [kaPerHour]/[kePerHour] (Bateman).
 * [customCurve], when set, is a user-authored **normalized action shape** (per-5-min buckets
 * summing to 1.0) that OVERRIDES the analytic curve; scaling by the dose (units) gives the PK
 * curve. [durationMin] is the DIA. [builtin] types are the seeded presets (not deletable).
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
