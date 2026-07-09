package com.t1dm.core.model

/**
 * Domain mirrors of the Rust `t1dm-core::stats` records (PLAN.private.md Phase 6). Kept free of
 * the uniffi binding so every consumer speaks these `:core:model` types; `:core:native` projects
 * to/from the generated records at the seam (as it does for the curve/forecast surfaces).
 *
 * The Rust is the numeric authority; these carry no logic beyond an [AdvancedStats.EMPTY] sentinel
 * that mirrors the crate's fail-closed `AdvancedStats::empty()` (all zeros, no NaN, channels absent).
 */

/** One grid sample fed to `advancedStats`. `bgMgdl` ≤ 0 (or non-finite) is excluded from every
 *  BG-derived metric by the Rust but its treatment/activity channels still count toward the totals. */
data class StatSample(
    val tsMs: Long,
    val bgMgdl: Double,
    val carbsG: Double?,
    val bolusU: Double?,
    val basalU: Double?,
    val steps: Long?,
    val mood: Int?,
)

/** Time-weighted clinical band fractions (sum to 1 over a non-empty series). `inRange` uses the
 *  configurable target edges; `veryLow`/`veryHigh` are the fixed 54/250 mg/dL clinical cuts. */
data class SubBands(
    val veryLow: Double,
    val low: Double,
    val inRange: Double,
    val high: Double,
    val veryHigh: Double,
)

/** One AGP time-of-day bin's BG percentile ribbon. [minuteOfDay] is the bin's start (0..1440). */
data class AgpBin(
    val minuteOfDay: Int,
    val p5: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p95: Double,
)

/** Mood summary over the samples that carried a mood score. */
data class MoodSummary(val mean: Double, val n: Int, val min: Int, val max: Int)

/** The full locally-recomputed advanced-stats block (Rust `AdvancedStats`). */
data class AdvancedStats(
    val nSamples: Int,
    val spanMs: Long,
    val tir: Double,
    val tbr: Double,
    val tar: Double,
    val subBands: SubBands,
    val lbgi: Double,
    val hbgi: Double,
    val mage: Double,
    val meanBg: Double,
    val sd: Double,
    val cv: Double,
    val gmi: Double,
    val totalCarbs: Double,
    val totalBolus: Double,
    val totalBasal: Double,
    val meanDailyCarbs: Double,
    val tdd: Double,
    val bolusBasalRatio: Double,
    val meanSteps: Double?,
    val mood: MoodSummary?,
    val agp: List<AgpBin>,
) {
    val isEmpty: Boolean get() = nSamples == 0

    companion object {
        val EMPTY = AdvancedStats(
            nSamples = 0,
            spanMs = 0,
            tir = 0.0, tbr = 0.0, tar = 0.0,
            subBands = SubBands(0.0, 0.0, 0.0, 0.0, 0.0),
            lbgi = 0.0, hbgi = 0.0, mage = 0.0,
            meanBg = 0.0, sd = 0.0, cv = 0.0, gmi = 0.0,
            totalCarbs = 0.0, totalBolus = 0.0, totalBasal = 0.0,
            meanDailyCarbs = 0.0, tdd = 0.0, bolusBasalRatio = 0.0,
            meanSteps = null, mood = null, agp = emptyList(),
        )
    }
}

/**
 * The global, user-configurable stats target range (PLAN §3.4 / ux-decisions.md). Drives
 * TIR/TBR/TAR and is DISTINCT from the alarm thresholds. Default 70-180 mg/dL (the ADA/consensus
 * time-in-range window).
 */
data class TargetRange(val lowMgdl: Int, val highMgdl: Int) {
    companion object {
        val DEFAULT = TargetRange(70, 180)
        const val MIN = 40
        const val MAX = 400
    }
}

/** A count + total-duration event stat (server hypo/hyper episodes). */
data class EventStat(val count: Int, val durationMs: Long)

/**
 * The server's daily-cached shared stats block (neutral mirror of `:sync`'s `StatsDto`, so
 * `:feature:stats` need not depend on `:sync`). `:app` maps the wire DTO onto this. The server
 * computes the O(1) shared fields; the richer AGP/LBGI/HBGI/MAGE/sub-bands come from the local
 * [AdvancedStats] recompute. `mean_bg`/`gmi`/`cv`/`sd` are the cross-check against the local block.
 */
data class ServerStats(
    val window: StatsWindow,
    val tir: Double,
    val timeBelow: Double,
    val timeAbove: Double,
    val meanBg: Double,
    val gmi: Double,
    val cv: Double,
    val sd: Double,
    val hypoEvents: EventStat,
    val hyperEvents: EventStat,
    val meanDailyCarbs: Double,
    val tdd: Double,
    val bolusBasalRatio: Double,
    val meanHr: Double,
    val bgHrCorr: Double,
    val nSamples: Int,
)

/**
 * The unified stats view (PLAN "Phase 6 — Stats"): the server cached block (default fast path,
 * O(1)) UNIONED with the locally-recomputed Rust [AdvancedStats] (the AGP ribbon, clinical
 * sub-bands, LBGI/HBGI, MAGE, and the authoritative cross-check). [server] is null when the server
 * is unreachable / unconfigured, with [serverReason] carrying the plain-language why; [local] is
 * always present (it is [AdvancedStats.EMPTY] when the window is too sparse). [recomputed] flags a
 * manual Recompute (server forced fresh + local re-derived).
 */
data class StatsComposite(
    val window: StatsWindow,
    val targetRange: TargetRange,
    val unitSpace: UnitSpace,
    val server: ServerStats?,
    val serverReason: String?,
    val local: AdvancedStats,
    val recomputed: Boolean,
)

/** The 7/30/90-day stats windows (server contract: serde `"7d"/"30d"/"90d"`; PLAN §5). */
enum class StatsWindow(val wire: String, val days: Int) {
    D7("7d", 7),
    D30("30d", 30),
    D90("90d", 90);

    val millis: Long get() = days.toLong() * 24L * 3_600_000L

    companion object {
        fun fromWire(s: String): StatsWindow = entries.firstOrNull { it.wire == s } ?: D7
    }
}
