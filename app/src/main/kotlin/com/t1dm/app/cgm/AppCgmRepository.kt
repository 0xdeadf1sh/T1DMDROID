package com.t1dm.app.cgm

import com.t1dm.cgm.CgmRepository
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.CgmAdvertRawEntity
import kotlinx.coroutines.flow.first

/**
 * Binds the storage-agnostic `:cgm` [CgmRepository] port onto the Room-backed [T1dmRepository]
 * in `:data` (the freeze deliberately left `@Database` + the repository to the Data owner, so the
 * CGM pipeline declared only a domain-typed port). This is the single adapter the composition root
 * wires; the pipeline never sees a Room entity.
 */
class AppCgmRepository(private val repository: T1dmRepository) : CgmRepository {

    override suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        active: Boolean,
        lastSeenMs: Long,
    ) = repository.upsertSource(descriptor, active, lastSeenMs)

    override suspend fun setActive(id: CgmSourceId) = repository.setActiveSource(id)

    override suspend fun loadSources(): List<CgmSourceDescriptor> = repository.observeSources().first()

    override suspend fun activeSourceId(): CgmSourceId? = repository.activeSourceId()

    override suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int) =
        repository.setSourceWarmupWindowMin(id, minutes)

    override suspend fun upsertReading(reading: CgmReading) = repository.upsertReading(reading)

    override suspend fun insertRawAdvert(
        sourceId: CgmSourceId?,
        rxWallMs: Long,
        rssi: Int?,
        payload: ByteArray,
        crcValid: Boolean,
        minFromStart: Int?,
    ) {
        repository.recordRawAdvert(
            CgmAdvertRawEntity(
                sourceId = sourceId?.value,
                rxWallMs = rxWallMs,
                rssi = rssi,
                payload = payload,
                crcValid = crcValid,
                minFromStart = minFromStart,
            ),
        )
    }
}
