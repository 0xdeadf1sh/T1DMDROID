package com.t1dm.sensors

import com.t1dm.data.T1dmRepository

interface StepSampleWriter {
    /** Set `sample.steps` for the grid row at [bucketStartMs]; LWW. */
    suspend fun record(bucketStartMs: Long, tzOffsetMin: Int, steps: Int)
}

/** Through T1dmRepository.recordSteps: merged into the row inside its write transaction. */
class RepositoryStepSampleWriter(
    private val repository: T1dmRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : StepSampleWriter {

    override suspend fun record(bucketStartMs: Long, tzOffsetMin: Int, steps: Int) {
        repository.recordSteps(bucketStartMs, tzOffsetMin, steps, clock())
    }
}
