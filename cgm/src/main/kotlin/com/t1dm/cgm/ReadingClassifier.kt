package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag

/**
 * The validity and warm-up gate (§3.1), over an already CRC-validated [DecodedAdvert]. `INVALID` is
 * dropped by the pipeline; `WARMUP` still carries its value but is suppressed from inference and
 * alarms. `status` is deliberately not a hard gate: CGM.md §3.1's golden advert is valid at `1`.
 */
class ReadingClassifier(
    warmupWindowMin: Int = CgmConstants.WARMUP_WINDOW_MIN,
    private val validBgRange: IntRange = CgmConstants.VALID_BG_RANGE,
    private val rejectNonNormalStatus: Boolean = false,
) {
    /**
     * Minutes, and on this branch the whole of the warm-up evidence. The comparison is EXCLUSIVE, so
     * the reading at exactly `sensorStart + warmupWindowMin` is `NORMAL` and `0` disables warm-up.
     * Retuned in place because [CgmPipeline] is stateful; written from the UI, read on the scan thread.
     */
    @Volatile
    var warmupWindowMin: Int = warmupWindowMin.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE)
        set(value) { field = value.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE) }

    fun classify(d: DecodedAdvert): ReadingFlag = when {
        !d.valid -> ReadingFlag.INVALID
        d.glucoseMgdl !in validBgRange -> ReadingFlag.INVALID
        rejectNonNormalStatus && d.status != 0 -> ReadingFlag.INVALID
        d.minFromStart < warmupWindowMin -> ReadingFlag.WARMUP
        else -> ReadingFlag.NORMAL
    }
}
