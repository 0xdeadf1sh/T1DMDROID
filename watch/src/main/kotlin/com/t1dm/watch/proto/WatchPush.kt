package com.t1dm.watch.proto

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus

/** The glance, SPEC/watch.md §5.3; [WatchCodec] serialises it, the session seals it. */
data class WatchPush(
    /** mg/dL. */
    val bgMgdl: Int?,
    /** 0.1 mg/dL/min. */
    val trendTenths: Int?,
    /** The measured rate's direction, as the phone draws it; null without a rate. */
    val bgTrend: WatchTrend?,
    /** Age of the last MEASURED reading, ms. */
    val readingAgeMs: Long,
    val alertBand: AlertBand?,
    val forecastStatus: ForecastStatus?,
    /** Median at the horizon end, mg/dL. */
    val fcEndMgdl: Int?,
    /** 5-min steps. */
    val fcHorizonSteps: Int,
    val fcTrend: WatchTrend,
    /** At most [MAX_SUMMARY] bytes. */
    val summary: String,
    val status: WatchStatus,
) {
    companion object {
        const val MAX_SUMMARY = 40
    }
}

/** Ordinal is the wire value. */
enum class WatchTrend { FLAT, RISING, FALLING, RISING_FAST, FALLING_FAST }

/** lowPowerSuspending: phone suspended the 5-min scheduler, a frozen glance is expected. */
data class WatchStatus(
    val stale: Boolean = false,
    val signalLoss: Boolean = false,
    val warmup: Boolean = false,
    val predictedLowCrossing: Boolean = false,
    val predictedHighCrossing: Boolean = false,
    val alarmActive: Boolean = false,
    val forecastUnavailable: Boolean = false,
    val lowPowerSuspending: Boolean = false,
)
