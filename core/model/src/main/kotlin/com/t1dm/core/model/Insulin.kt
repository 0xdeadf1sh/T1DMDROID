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

/**
 * The two disjoint catalogues a logged dose can be written against, unified for the surfaces that
 * offer one to be re-picked. A row keeps only the [label] it was logged under, in
 * `logged_dose.note`, so that string is the only thing a re-pick can be matched against.
 */
sealed interface InsulinChoice {
    val label: String
    val kind: InsulinKind

    /** The native preset catalogue, which the Insulin screen's bolus and basal writes name. */
    data class Preset(val spec: InsulinPresetSpec) : InsulinChoice {
        override val label: String get() = spec.label
        override val kind: InsulinKind
            get() = if (spec.family == InsulinFamily.RapidExp) InsulinKind.BOLUS else InsulinKind.BASAL
    }

    /** An `insulin_type` row, which the type builder's writes name. */
    data class Type(val type: InsulinType) : InsulinChoice {
        override val label: String get() = type.name
        override val kind: InsulinKind get() = type.kind
    }
}
