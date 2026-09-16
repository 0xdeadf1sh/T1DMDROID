package com.t1dm.core.model

/** Why a replay ended before its last origin. */
enum class BacktestStop { TOO_HOT, MODEL_CHANGED }

enum class BacktestRefusal { BUSY, NOT_LOADED, NO_ARTIFACT, NO_SENSOR, NO_HISTORY, FAILED }

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
        val adapterAttached: Boolean,
        /** Null when every origin was tried. */
        val stopped: BacktestStop?,
        val elapsedMs: Long,
        val finishedAtMs: Long,
    ) : ModelBacktest

    data class Refused(override val days: Int, val refusal: BacktestRefusal) : ModelBacktest
}
