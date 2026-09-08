package com.t1dm.watch.proto

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus

/** 5-min glance, phone→watch: [WatchPushCodec] serialises, crypto seals. Layout in WATCH_BLE.md. */
data class WatchPush(
    /** mg/dL. */
    val bgMgdl: Int?,
    /** 0.1 mg/dL/min. */
    val trendTenths: Int?,
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
        const val PAYLOAD_VERSION = 0x01
        const val MAX_SUMMARY = 40
    }
}

/** Ordinal is the wire value. */
enum class WatchTrend { FLAT, RISING, FALLING, RISING_FAST, FALLING_FAST }

/** [lowPowerSuspending]: scheduler suspended, so a frozen glance is expected, not a fault. */
data class WatchStatus(
    val stale: Boolean = false,
    val signalLoss: Boolean = false,
    val warmup: Boolean = false,
    val predictedLowCrossing: Boolean = false,
    val predictedHighCrossing: Boolean = false,
    val alarmActive: Boolean = false,
    val forecastUnavailable: Boolean = false,
    val lowPowerSuspending: Boolean = false,
) {
    fun toBits(): Int {
        var b = 0
        if (lowPowerSuspending) b = b or BIT_LOW_POWER
        if (stale) b = b or BIT_STALE
        if (signalLoss) b = b or BIT_SIGNAL_LOSS
        if (warmup) b = b or BIT_WARMUP
        if (predictedLowCrossing) b = b or BIT_PRED_LOW
        if (predictedHighCrossing) b = b or BIT_PRED_HIGH
        if (alarmActive) b = b or BIT_ALARM
        if (forecastUnavailable) b = b or BIT_FC_UNAVAIL
        return b
    }

    companion object {
        const val BIT_LOW_POWER = 0x01
        const val BIT_STALE = 0x02
        const val BIT_SIGNAL_LOSS = 0x04
        const val BIT_WARMUP = 0x08
        const val BIT_PRED_LOW = 0x10
        const val BIT_PRED_HIGH = 0x20
        const val BIT_ALARM = 0x40
        const val BIT_FC_UNAVAIL = 0x80

        fun fromBits(b: Int) = WatchStatus(
            lowPowerSuspending = b and BIT_LOW_POWER != 0,
            stale = b and BIT_STALE != 0,
            signalLoss = b and BIT_SIGNAL_LOSS != 0,
            warmup = b and BIT_WARMUP != 0,
            predictedLowCrossing = b and BIT_PRED_LOW != 0,
            predictedHighCrossing = b and BIT_PRED_HIGH != 0,
            alarmActive = b and BIT_ALARM != 0,
            forecastUnavailable = b and BIT_FC_UNAVAIL != 0,
        )
    }
}
