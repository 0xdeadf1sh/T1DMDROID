package com.t1dm.sensors

import com.t1dm.core.common.T1dmDispatchers
import kotlinx.coroutines.flow.flowOn
import java.util.TimeZone

/**
 * Drives the step pipeline (PLAN.private.md §3.5 / Phase 1): collects [StepSource.buckets] and
 * persists each bucket to `sample.steps` via a [StepSampleWriter]. Meant to run inside the
 * `CgmScanService` foreground scope alongside the scan, tick, and alarm.
 *
 * The bucketing itself is cheap and runs on the [default][T1dmDispatchers.default] dispatcher; the
 * writer hops to I/O internally.
 */
class StepRecorder(
    private val source: StepSource,
    private val writer: StepSampleWriter,
    private val dispatchers: T1dmDispatchers,
    private val tzOffsetMinAt: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
) {
    /** Collect and persist step buckets until the calling scope is cancelled. */
    suspend fun run() {
        source.buckets()
            .flowOn(dispatchers.default)
            .collect { bucket ->
                writer.record(bucket.bucketStartMs, tzOffsetMinAt(bucket.bucketStartMs), bucket.steps)
            }
    }
}
