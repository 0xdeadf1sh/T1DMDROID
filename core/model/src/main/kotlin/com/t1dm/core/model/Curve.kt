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

/** durationMin is the Bateman action window; each occurrence expands to Bateman. */
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

/** SPEC/invariants.md §5: [RapidGamma] is a dose-scaled gamma, [BasalBateman] a Bateman. */
enum class InsulinFamily { RapidGamma, BasalBateman }

/** label keys a preset's identity. Rapid fields are 0 on a basal, basal fields 0 on a rapid. */
data class InsulinPresetSpec(
    val family: InsulinFamily,
    val label: String,
    val gammaK: Double,
    /** Minutes, at 5 U. */
    val gammaTheta: Double,
    /** Hours, at 5 U. */
    val diaBaseHours: Double,
    val kaPerHour: Double,
    val kePerHour: Double,
    /** Minutes. */
    val actionMin: Double,
    val citation: String,
)

/** A rapid bolus's gamma for one dose; [theta] and [durationMin] in minutes. */
data class BolusPk(val k: Double, val theta: Double, val durationMin: Double)
