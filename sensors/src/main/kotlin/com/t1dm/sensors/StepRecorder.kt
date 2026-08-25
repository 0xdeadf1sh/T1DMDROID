package com.t1dm.sensors

import com.t1dm.core.common.T1dmDispatchers
import kotlinx.coroutines.flow.flowOn
import java.util.TimeZone

/** Runs in `CgmScanService`'s foreground scope, until that scope is cancelled. */
class StepRecorder(
    private val source: StepSource,
    private val writer: StepSampleWriter,
    private val dispatchers: T1dmDispatchers,
    private val tzOffsetMinAt: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
) {
    suspend fun run() {
        source.buckets()
            .flowOn(dispatchers.default)
            .collect { bucket ->
                writer.record(bucket.bucketStartMs, tzOffsetMinAt(bucket.bucketStartMs), bucket.steps)
            }
    }
}
