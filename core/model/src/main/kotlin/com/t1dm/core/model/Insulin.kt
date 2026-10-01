package com.t1dm.core.model

/** [BOLUS] is a dose-scaled gamma; [BASAL] a broad Bateman, near-flat once tiled. */
enum class InsulinKind { BOLUS, BASAL }

/** Carries curve params so a dose survives default changes; [customCurve] buckets sum to 1.0. */
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

/** Unifies the two dose catalogues for re-pick UIs; only `logged_dose.note`'s [label] can match. */
sealed interface InsulinChoice {
    val label: String
    val kind: InsulinKind

    /** The native preset catalogue, which the Insulin screen's bolus and basal writes name. */
    data class Preset(val spec: InsulinPresetSpec) : InsulinChoice {
        override val label: String get() = spec.label
        override val kind: InsulinKind
            get() = if (spec.family == InsulinFamily.RapidGamma) InsulinKind.BOLUS else InsulinKind.BASAL
    }

    /** An `insulin_type` row, which the type builder's writes name. */
    data class Type(val type: InsulinType) : InsulinChoice {
        override val label: String get() = type.name
        override val kind: InsulinKind get() = type.kind
    }
}
