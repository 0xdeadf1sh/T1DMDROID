package com.t1dm.sensors

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.db.SampleDao
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.withContext

/**
 * Persistence seam for step buckets. Kept as an interface (free of any `:data` type) so [StepRecorder]
 * and its tests never bind to Room; the app wires [RoomStepSampleWriter] at the composition root.
 */
interface StepSampleWriter {
    /** Set `sample.steps` for the grid row at [bucketStartMs] (LWW, SPEC.private.md §3.5). */
    suspend fun record(bucketStartMs: Long, tzOffsetMin: Int, steps: Int)
}

/**
 * Writes step totals into the wide `sample` projection (SPEC.private.md §3.5). Since Room v1 keeps
 * no separate steps event store, the counter feeds `sample.steps` directly. The frozen [SampleDao]
 * offers only a whole-row upsert, so this does a read-modify-write, preserving the row's other
 * series and bumping `updatedAt` for the last-writer-wins merge. All I/O on the [io][T1dmDispatchers.io]
 * dispatcher.
 *
 * Note: the read-modify-write is not atomic against a concurrent `sample` projector; a column-scoped
 * `UPDATE … SET steps` in a `@Transaction` belongs to the `:data` owner and supersedes this later.
 */
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
