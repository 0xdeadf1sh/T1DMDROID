package com.t1dm.watch.proto

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus

/**
 * The 5-min glance pushed phone → watch (watch-link.md, locked): current BG + trend + a one-line
 * forecast summary from the **selected** model + the alert band + status bits. **No images.** This
 * is the plaintext that [WatchPushCodec.encode] serialises and the crypto layer seals; the byte
 * layout is frozen in docs/WATCH_BLE.md and golden-tested.
 *
 * The composition root fills this from the repository (BG/trend/age), the alarm engine (band,
 * signal-loss), and the selected model's prediction (forecast fields + summary); [WatchLink] sets
 * [WatchStatus.lowPowerSuspending] before the final seal.
 */
data class WatchPush(
    /** Current BG in mg/dL, or null when there is no reading to show. */
    val bgMgdl: Int?,
    /** Rate of change in 0.1 mg/dL/min (sensor tenths), or null. */
    val trendTenths: Int?,
    /** Age of the last MEASURED reading, milliseconds — the quantified staleness the watch renders. */
    val readingAgeMs: Long,
    /** The alert band the current BG falls in, or null when unknown. */
    val alertBand: AlertBand?,
    /** The selected model's degeneracy verdict, or null when no eligible forecast exists. */
    val forecastStatus: ForecastStatus?,
    /** The selected model's median BG at the horizon end (mg/dL), or null. */
    val fcEndMgdl: Int?,
    /** Forecast horizon length in 5-min steps (e.g. 24 = 120 min). */
    val fcHorizonSteps: Int,
    /** A coarse direction class for the glance chevron. */
    val fcTrend: WatchTrend,
    /** The one-line, human-readable forecast summary from the SELECTED model (≤ [MAX_SUMMARY] bytes). */
    val summary: String,
    val status: WatchStatus,
) {
    companion object {
        const val PAYLOAD_VERSION = 0x01
        const val MAX_SUMMARY = 40
    }
}

/** Coarse forecast direction for the watch chevron; ordinal is the wire value. */
enum class WatchTrend { FLAT, RISING, FALLING, RISING_FAST, FALLING_FAST }

/**
 * The status bitfield the watch reads to colour/annotate the glance and to know the phone's own
 * health. Every bit is a plain-language condition (ux-decisions.md — human-readable everywhere);
 * [lowPowerSuspending] tells the watch the phone has SUSPENDED the 5-min scheduler (watch-link.md)
 * so a subsequently-frozen glance is expected, not a fault.
 */
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
