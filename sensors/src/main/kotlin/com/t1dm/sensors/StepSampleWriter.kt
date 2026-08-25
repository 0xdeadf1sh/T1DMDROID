package com.t1dm.sensors

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.SampleDao
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.withContext

interface StepSampleWriter {
    /** Set `sample.steps` for the grid row at [bucketStartMs]; LWW. */
    suspend fun record(bucketStartMs: Long, tzOffsetMin: Int, steps: Int)
}

/** [SampleDao] offers only a whole-row upsert, so this reads-modifies-writes to keep the row's other
 *  series and bump `updatedAt` for LWW. Not atomic against a concurrent `sample` projector. */
class RoomStepSampleWriter(
    private val sampleDao: SampleDao,
    private val dispatchers: T1dmDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) : StepSampleWriter {

    override suspend fun record(bucketStartMs: Long, tzOffsetMin: Int, steps: Int) {
        withContext(dispatchers.io) {
            val now = clock()
            val existing = sampleDao.byTs(bucketStartMs)
            val row = existing?.copy(steps = steps, updatedAt = now)
                ?: SampleEntity(
                    ts = bucketStartMs,
                    tzOffsetMin = tzOffsetMin,
                    bgMgdl = null,
                    bgSource = null,
                    bgProvenance = null,
                    bgFlag = null,
                    steps = steps,
                    mood = null,
                    hr = null,
                    sleep = null,
                    exercise = null,
                    updatedAt = now,
                )
            sampleDao.upsert(row)
        }
    }
}
