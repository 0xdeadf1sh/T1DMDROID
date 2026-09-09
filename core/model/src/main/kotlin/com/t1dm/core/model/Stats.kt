package com.t1dm.core.model

/** Non-finite/<=0 `bgMgdl` excludes BG-derived metrics; treatment/activity channels still count. */
data class StatSample(
    val tsMs: Long,
    /** MINUTES, east-positive, at [tsMs] (§2); never shifts [tsMs], which is UTC. */
    val tzOffsetMin: Int,
    val bgMgdl: Double,
    val carbsG: Double?,
    val bolusU: Double?,
    val basalU: Double?,
    val steps: Long?,
    val mood: Int?,
)

/** Time-weighted, sums to 1; [inRange] configurable, [veryLow]/[veryHigh] fixed 54/250 mg/dL. */
data class SubBands(
    val veryLow: Double,
    val low: Double,
    val inRange: Double,
    val high: Double,
    val veryHigh: Double,
)

/** [minuteOfDay] is the bin's start, 0..1440. */
data class AgpBin(
    val minuteOfDay: Int,
    val p5: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p95: Double,
)

/** Level-2 cuts in mg/dL. Not configurable, and not valued here: the Rust crate owns them. */
data class ClinicalCuts(val veryLowMgdl: Double, val veryHighMgdl: Double) {
    val isUsable: Boolean get() = veryLowMgdl > 0.0 && veryHighMgdl > veryLowMgdl

    companion object {
        /** Fail-closed when no native library backs the call; deliberately NOT the real numbers. */
        val UNAVAILABLE = ClinicalCuts(0.0, 0.0)
    }
}

/** [dow] 0=Mon..6=Sun, [hour] 0..23, LOCAL; only POPULATED cells exist, absent stays absent. */
data class HeatCell(val dow: Int, val hour: Int, val n: Int, val meanBg: Double, val medianBg: Double) {
    fun value(stat: HeatStat): Double = when (stat) {
        HeatStat.Median -> medianBg
        HeatStat.Mean -> meanBg
    }
}

/** Display-only: both are always computed. */
enum class HeatStat { Median, Mean }

/** [n] counts only the samples that carried a mood score. */
data class MoodSummary(val mean: Double, val n: Int, val min: Int, val max: Int)

/** Sample-count, not time-weighted. [startMin] is 0/360/720/1080. */
data class TodBucket(
    val startMin: Int,
    val n: Int,
    val tir: Double,
    val tbr: Double,
    val tar: Double,
)

/** 20 mg/dL bins over [40,400); the tails clamp into the end bins. */
data class HistBin(val lo: Double, val hi: Double, val count: Int, val frac: Double)

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

/** [hypo]/[eu]/[hyper] are fractions summing to 1 when [grade] is positive. */
data class GradeSplit(val grade: Double, val hypo: Double, val eu: Double, val hyper: Double) {
    companion object {
        val EMPTY = GradeSplit(0.0, 0.0, 0.0, 0.0)
    }
}

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
    /** LOCAL time, populated only, ascending by `(dow, hour)`; [agp]/[tod] share the clock. */
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

/** mg/dL, §3.4. DISTINCT from the alarm thresholds. */
data class TargetRange(val lowMgdl: Int, val highMgdl: Int) {
    companion object {
        val DEFAULT = TargetRange(70, 180)
        const val MIN = 40
        const val MAX = 400
    }
}

data class StatsComposite(
    val window: StatsWindow,
    val targetRange: TargetRange,
    val unitSpace: UnitSpace,
    val local: AdvancedStats,
    val recomputed: Boolean,
)

/** [wire] is the server contract's serde form. */
enum class StatsWindow(val wire: String, val days: Int) {
    D7("7d", 7),
    D30("30d", 30),
    D90("90d", 90);

    val millis: Long get() = days.toLong() * 24L * 3_600_000L

    companion object {
        fun fromWire(s: String): StatsWindow = entries.firstOrNull { it.wire == s } ?: D7
    }
}
