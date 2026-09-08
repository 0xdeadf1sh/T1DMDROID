package com.t1dm.core.model

/** BOLUS (rapid) is gamma peaking ~50 min; BASAL (long) a broad Bateman, near-flat once tiled. */
enum class InsulinKind { BOLUS, BASAL }

/** Self-describing PK params, so a logged dose reconstructs its curve even if defaults change. */
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

/** Two disjoint catalogues a dose can be written against; a row keeps only the logged label. */
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
