package com.t1dm.core.model

/**
 * Domain mirrors of the Rust `t1dm-core::stats` records (Phase 6). Kept free of
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
    /** The client's UTC offset in MINUTES, east-positive, at [tsMs] (`SPEC/invariants.md` §2). It never
     *  shifts [tsMs], which is always UTC — it is what lets [AdvancedStats.heatmap] key on the
     *  patient's own calendar, and it is the only field of this record that reads it. */
    val tzOffsetMin: Int,
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

/**
 * The fixed clinical level-2 cuts, in mg/dL: below [veryLowMgdl] is level-2 hypoglycaemia, above
 * [veryHighMgdl] level-2 hyperglycaemia. Unlike [TargetRange] they are not configurable, and unlike
 * it they are not defined here — the Rust crate owns them, because that is where the band fractions
 * they partition are actually cut.
 */
data class ClinicalCuts(val veryLowMgdl: Double, val veryHighMgdl: Double) {
    /** Both cuts positive and ordered — the only state in which a scale may be anchored on them. */
    val isUsable: Boolean get() = veryLowMgdl > 0.0 && veryHighMgdl > veryLowMgdl

    companion object {
        /** The fail-closed answer when no native library backs the call. Deliberately NOT the real
         *  numbers: a stub that guessed them would be the second copy this type exists to prevent. */
        val UNAVAILABLE = ClinicalCuts(0.0, 0.0)
    }
}

/**
 * One populated cell of the day-of-week × hour-of-day glucose grid.
 *
 * [dow] is 0 = Monday … 6 = Sunday and [hour] is 0..23, both in the patient's LOCAL time, resolved
 * per sample from that sample's own [StatSample.tzOffsetMin]. [meanBg] and [medianBg] are plain
 * sample-count reductions over the cell's valid-BG samples; [medianBg] is the same type-7 percentile
 * the AGP ribbon's `p50` is. Both arrive from one pass, so choosing between them is a repaint.
 *
 * Only POPULATED cells exist. An absent `(dow, hour)` means no reading was taken then, and must
 * render as absent — never as a value, and in particular never as an in-range one.
 */
data class HeatCell(val dow: Int, val hour: Int, val n: Int, val meanBg: Double, val medianBg: Double) {
    /** The cell's value under [stat] — the single place the choice is resolved. */
    fun value(stat: HeatStat): Double = when (stat) {
        HeatStat.Median -> medianBg
        HeatStat.Mean -> meanBg
    }
}

/** Which summary a [HeatCell] is rendered by. Display-only: both are always computed. */
enum class HeatStat { Median, Mean }

/** Mood summary over the samples that carried a mood score. */
data class MoodSummary(val mean: Double, val n: Int, val min: Int, val max: Int)

/** Sample-count TIR/TBR/TAR within a fixed 6-hour diurnal bucket ([startMin] = 0/360/720/1080). */
data class TodBucket(
    val startMin: Int,
    val n: Int,
    val tir: Double,
    val tbr: Double,
    val tar: Double,
)

/** One 20 mg/dL glucose-distribution histogram bin over [40,400) (tails clamp into the ends). */
data class HistBin(val lo: Double, val hi: Double, val count: Int, val frac: Double)

/** Aggregate of the hypo/hyper excursion episodes (count/duration/mean-extreme/worst-extreme). */
data class EpisodeSummary(
    val count: Int,
    val totalDurationMs: Long,
    val meanDurationMs: Double,
    val meanExtreme: Double,
    val worstExtreme: Double,
) {
    companion object {
        val EMPTY = EpisodeSummary(0, 0L, 0.0, 0.0, 0.0)
    }
}

/** GRADE mean score plus its hypo/eu/hyper attribution (fractions summing to 1 when positive). */
data class GradeSplit(val grade: Double, val hypo: Double, val eu: Double, val hyper: Double) {
    companion object {
        val EMPTY = GradeSplit(0.0, 0.0, 0.0, 0.0)
    }
}

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
    // ── Variability & risk extensions (Phase 7D) ──
    val modd: Double,
    val conga1: Double,
    val conga2: Double,
    val conga4: Double,
    val jIndex: Double,
    val mValue: Double,
    val adrr: Double,
    val dtdSd: Double,
    val grade: GradeSplit,
    val tod: List<TodBucket>,
    val histogram: List<HistBin>,
    val hypoEpisodes: EpisodeSummary,
    val hyperEpisodes: EpisodeSummary,
    /** Mean and median glucose per (day-of-week, hour) cell in LOCAL time — populated cells only, ascending by
     *  `(dow, hour)`. The one aggregation in this block keyed on the patient's calendar; [agp] and
     *  [tod] are keyed on UTC. */
    val heatmap: List<HeatCell>,
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
            modd = 0.0, conga1 = 0.0, conga2 = 0.0, conga4 = 0.0,
            jIndex = 0.0, mValue = 0.0, adrr = 0.0, dtdSd = 0.0,
            grade = GradeSplit.EMPTY, tod = emptyList(), histogram = emptyList(),
            hypoEpisodes = EpisodeSummary.EMPTY, hyperEpisodes = EpisodeSummary.EMPTY,
            heatmap = emptyList(),
        )
    }
}

/**
 * The global, user-configurable stats target range (SPEC §3.4 /). Drives
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

/** The 7/30/90-day stats windows (server contract: serde `"7d"/"30d"/"90d"`; T1DMSERVER stats contract). */
enum class StatsWindow(val wire: String, val days: Int) {
    D7("7d", 7),
    D30("30d", 30),
    D90("90d", 90);

    val millis: Long get() = days.toLong() * 24L * 3_600_000L

    companion object {
        fun fromWire(s: String): StatsWindow = entries.firstOrNull { it.wire == s } ?: D7
    }
}
