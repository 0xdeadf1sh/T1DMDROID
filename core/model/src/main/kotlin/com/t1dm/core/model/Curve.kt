package com.t1dm.core.model

/**
 * Carbs feed the model as an appearance (Ra) rate — grams per 5 min summing to the meal total;
 * insulin as a PK ACTION rate, not delivery or IOB; exercise as a carbohydrate-EQUIVALENT disposal
 * rate, positive, the sign living in the equation that subtracts it.
 */

enum class CurveKind { CARB, INSULIN, EXERCISE }

/**
 * [values] sum to [total] over the event: Ra grams for carbs, PK action-units for insulin.
 * [stepMs] is the 5-min grid cadence (`CurveEngine.STEP_MS`).
 */
data class CurveEvent(
    val startMs: Long,
    val stepMs: Long,
    val kind: CurveKind,
    val total: Double,
    val values: List<Double>,
)

/** [durationMin] is the DIA (Lantus ~1440, Tresiba ~2520); each occurrence expands to a Bateman
 *  [CurveEvent], and the tiled sum is the background the model always sees. */
data class BasalDoseSpec(
    val timeOfDayMin: Int,
    val doseU: Double,
    val durationMin: Double,
    val kaPerHour: Double,
    val kePerHour: Double,
)

/** [tzOffsetMin] maps epoch-ms to the local midnight [BasalDoseSpec.timeOfDayMin] measures from. */
data class BasalSchedule(
    val tzOffsetMin: Int,
    val doses: List<BasalDoseSpec>,
)

/** [RapidExp] is the Loop/OpenAPS exponential activity curve. */
enum class InsulinFamily { RapidExp, BasalBateman }

/**
 * [label] is the identity everything keys a preset by — the Rust `preset` enum is not projected
 * across the seam. [offDistribution] marks a curve off the model's training distribution; nothing
 * reads it, deliberately. [citation] is public-safe provenance, rendered verbatim.
 */
data class InsulinPresetSpec(
    val family: InsulinFamily,
    val label: String,
    val peakMin: Double,
    val diaMin: Double,
    val kaPerHour: Double,
    val kePerHour: Double,
    val offDistribution: Boolean,
    val citation: String,
)
