package com.t1dm.alerts

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

internal const val MIN = 60_000L

/** Builds a grid-stamped [CgmReading] for injected-sequence tests. Defaults describe a normal,
 *  MEASURED, flat reading; each field is overridable to exercise a specific pathway. */
internal fun reading(
    bgMgdl: Int?,
    rxWallMs: Long = 0L,
    provenance: ReadingProvenance = ReadingProvenance.MEASURED,
    flag: ReadingFlag = ReadingFlag.NORMAL,
    trendTenthsPerMin: Int? = 0,
    tsMs: Long = (rxWallMs / 300_000L) * 300_000L,
    rssi: Int? = -60,
): CgmReading = CgmReading(
    sourceId = CgmSourceId("aidexx:test"),
    tsMs = tsMs,
    bgMgdl = bgMgdl,
    trendTenthsPerMin = trendTenthsPerMin,
    minFromStart = 120,
    quality = 100,
    provenance = provenance,
    flag = flag,
    tzOffsetMin = 0,
    rxWallMs = rxWallMs,
    rssi = rssi,
)

/** Records the sink calls the [AlarmController] makes, for deterministic wiring assertions. */
internal class FakeNotifier : AlarmNotifier {
    var emitCount = 0
    var clearCount = 0
    var reAlertCount = 0
    var lastEmit: AlarmState? = null

    override fun emit(state: AlarmState) {
        emitCount++
        lastEmit = state
    }

    override fun reAlert(state: AlarmState) {
        reAlertCount++
    }

    override fun clear() {
        clearCount++
    }
}
