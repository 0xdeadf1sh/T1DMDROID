package com.t1dm.core.model

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
    ) : ModelBacktest

    data class Refused(override val days: Int, val refusal: BacktestRefusal) : ModelBacktest
}
