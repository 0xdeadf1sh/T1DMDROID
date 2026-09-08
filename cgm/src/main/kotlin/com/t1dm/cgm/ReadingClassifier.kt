package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag

/** Validity/warmup gate (§3.1) over CRC-valid advert: INVALID dropped, WARMUP suppressed, soft. */
class ReadingClassifier(
    warmupWindowMin: Int = CgmConstants.WARMUP_WINDOW_MIN,
    private val validBgRange: IntRange = CgmConstants.VALID_BG_RANGE,
    private val rejectNonNormalStatus: Boolean = false,
) {
    /** Minutes, exclusive: sensorStart+window exactly=NORMAL, 0 disables; UI writes, scan reads. */
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
