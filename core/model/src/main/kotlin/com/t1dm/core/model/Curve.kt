package com.t1dm.core.model

/**
 * The shared curve/PK engine types crossing the Rust `t1dm-core` seam (Phase 4,
 * §3.3). These mirror the uniffi records one-to-one; `:core:native`
 * projects the generated `uniffi.t1dm_core.*` types onto these so downstream consumers
 * (`:data` CurveEngine/ChannelBuilder, `:calc`, `:inference`) never depend on the binding
 * directly.
 *
 * THE KEY MODEL FACT (model-io-curves.md): carbs feed the model as an **appearance (Ra)**
 * rate (gamma, grams-per-5-min summing to the meal total); insulin feeds it as a **PK
 * ACTION** rate (bolus gamma peaking ~50 min; basal broad Bateman), NOT as delivery/IOB.
 */

/** Which model input channel an event feeds. */
enum class CurveKind { CARB, INSULIN }

/**
 * One resolved event: an absolute [startMs] plus its per-[stepMs] curve. [values] sum to
 * [total] over the event's duration (Ra grams for carbs; PK action-units for insulin).
 * Produced by the curve builders, consumed by bucketize (channel building) and onBoard
 * (IOB/COB). [stepMs] is the 5-min grid cadence (`CurveEngine.STEP_MS`).
 */
data class CurveEvent(
    val startMs: Long,
    val stepMs: Long,
    val kind: CurveKind,
    val total: Double,
    val values: List<Double>,
)

/**
 * One long-acting basal injection in a daily-repeating schedule (MDI). Each occurrence
 * expands to a Bateman [CurveEvent]; the tiled sum is the near-flat background the model
 * always sees. [durationMin] is the DIA (e.g. Lantus ~1440, Tresiba ~2520).
 */
data class BasalDoseSpec(
    val timeOfDayMin: Int,
    val doseU: Double,
    val durationMin: Double,
    val kaPerHour: Double,
    val kePerHour: Double,
)

/**
 * A day-long basal schedule (SPEC §3.6 "day-long basal schedule search"). [tzOffsetMin]
 * maps epoch-ms to the local midnight the [BasalDoseSpec.timeOfDayMin] offsets measure from.
 */
data class BasalSchedule(
    val tzOffsetMin: Int,
    val doses: List<BasalDoseSpec>,
)

/**
 * Which curve family + channel an insulin preset feeds (mirror of the Rust `InsulinFamily`,
 * issue 19). [RapidExp] = the Loop/OpenAPS exponential activity curve; [BasalBateman] = the
 * long-acting Bateman.
 */
enum class InsulinFamily { RapidExp, BasalBateman }

/**
 * One resolved clinical insulin preset (mirror of the Rust `InsulinPresetSpec`, issue 19). These
 * are the OPT-IN, clinically-grounded alternatives to the simulator-matched default curves.
 * Selecting one moves the model's insulin-action channel OFF its training distribution — the
 * calculator/IOB become more clinically faithful while the FORECAST becomes less trustworthy —
 * hence [offDistribution] drives the prominent warning at the picker. [label] is the stable,
 * user-facing identity the app persists a selection by; [peakMin]/[diaMin] (and, for basals,
 * [kaPerHour]/[kePerHour]) parameterise the actual curve. [citation] is public-safe provenance.
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
