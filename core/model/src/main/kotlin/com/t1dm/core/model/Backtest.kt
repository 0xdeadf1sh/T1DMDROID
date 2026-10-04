package com.t1dm.core.model

import kotlin.math.abs

/** Why a replay ended before its last origin. */
enum class BacktestStop { TOO_HOT, MODEL_CHANGED }

enum class BacktestRefusal { BUSY, NOT_LOADED, NO_ARTIFACT, NO_SENSOR, NO_HISTORY, FAILED }

/** A sensor a backtest may replay; [label] has the name-privacy setting applied. */
data class BacktestSensor(val id: String, val label: String, val authoritative: Boolean, val newestMs: Long)

/** Past days replayed through one model as it runs now; stored nowhere, read by no classifier. */
sealed interface ModelBacktest {
    val days: Int

    data class Running(override val days: Int, val done: Int, val total: Int) : ModelBacktest

    data class Done(
        override val days: Int,
        val metrics: ModelMetrics,
        /** Origins that yielded a forecast, of [nOrigins] tried. */
        val nForecasts: Int,
        val nOrigins: Int,
        /** [nForecasts] by source id, in the order the sensors were chosen. */
        val forecastsBySource: Map<String, Int>,
        val adapterAttached: Boolean,
        /** Null when every origin was tried. */
        val stopped: BacktestStop?,
        val elapsedMs: Long,
        val finishedAtMs: Long,
        val examples: BacktestExamples,
    ) : ModelBacktest

    data class Refused(override val days: Int, val refusal: BacktestRefusal) : ModelBacktest
}

/** Drawn before each example's origin; plus the scored span, the minimum gap between two picks. */
const val BACKTEST_EXAMPLE_CONTEXT_MS: Long = 12L * 3_600_000L

const val BACKTEST_EXAMPLES_PER_LIST: Int = 4

/** mg/dL over the scored steps: [maeMgdl] the median line's, [persistMaeMgdl] lastBg held flat. */
data class BacktestExample(
    val sourceId: String,
    val cycleTsMs: Long,
    val stepMs: Long,
    val window: ForecastWindow,
    val maeMgdl: Double,
    val persistMaeMgdl: Double,
)

/** [good]: largest MAE gain over persistence, gain > 0. [bad]: largest MAE. Best first. */
data class BacktestExamples(val good: List<BacktestExample>, val bad: List<BacktestExample>) {
    companion object {
        val EMPTY = BacktestExamples(emptyList(), emptyList())
    }
}

/** Greedy, best first; a pick nearer an earlier one than context + scored span shares its panel. */
fun pickBacktestExamples(set: ForecastWindowSet, contextMs: Long, perList: Int): BacktestExamples {
    val n = set.windows.size
    if (n == 0 || set.forecasts.size != n || perList <= 0) return BacktestExamples.EMPTY
    val mae = DoubleArray(n)
    val persist = DoubleArray(n)
    for (k in 0 until n) {
        val w = set.windows[k]
        var e = 0.0
        var p = 0.0
        for (i in w.realizedBg.indices) {
            e += abs(w.medianBg[i] - w.realizedBg[i])
            p += abs(w.lastBg - w.realizedBg[i])
        }
        mae[k] = e / w.realizedBg.size
        persist[k] = p / w.realizedBg.size
    }
    val scored = (0 until n).filter { mae[it].isFinite() && persist[it].isFinite() }

    fun pick(order: List<Int>): List<BacktestExample> {
        val out = ArrayList<BacktestExample>(perList)
        for (k in order) {
            val f = set.forecasts[k]
            val w = set.windows[k]
            val spacingMs = contextMs + w.realizedBg.size * f.stepMs
            if (out.any { abs(it.cycleTsMs - f.cycleTsMs) < spacingMs }) continue
            out += BacktestExample(f.sourceId.orEmpty(), f.cycleTsMs, f.stepMs, w, mae[k], persist[k])
            if (out.size == perList) break
        }
        return out
    }

    return BacktestExamples(
        good = pick(scored.filter { persist[it] > mae[it] }.sortedByDescending { persist[it] - mae[it] }),
        bad = pick(scored.sortedByDescending { mae[it] }),
    )
}
