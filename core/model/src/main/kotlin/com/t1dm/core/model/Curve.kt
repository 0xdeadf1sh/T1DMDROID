package com.t1dm.core.model

/** Carbs=Ra appearance g/5min; insulin=PK action, not IOB; exercise=positive disposal rate. */

enum class CurveKind { CARB, INSULIN, EXERCISE }

/** [values] sum to [total] (Ra g for carbs, PK units for insulin); [stepMs] is 5-min cadence. */
data class CurveEvent(
    val startMs: Long,
    val stepMs: Long,
    val kind: CurveKind,
    val total: Double,
    val values: List<Double>,
)

/** [durationMin] is DIA (Lantus ~1440, Tresiba ~2520); expands to Bateman [CurveEvent], tiled. */
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

/** [label] is preset identity (Rust enum not projected); [offDistribution] unread, deliberate. */
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
