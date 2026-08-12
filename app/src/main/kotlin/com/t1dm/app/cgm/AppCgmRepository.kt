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
class AppCgmRepository(
    private val repository: T1dmRepository,
    /** Enqueues the `PUT /v1/cgm-sources` descriptor push. Nullable so a test double, and the
     *  composition order during construction, need not supply one. */
    private val enqueuer: com.t1dm.sync.OutboxEnqueuer? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CgmRepository {

    override suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ) {
        repository.upsertSource(descriptor, authoritative, lastSeenMs)
        // Push the descriptor so the server can resolve the opaque label its samples already carry.
        // Deduped on the source id, so the coordinator's re-upserts collapse to one queued row.
        enqueuer?.enqueueCgmSource(
            com.t1dm.sync.CgmSourceDto(
                id = descriptor.id.opaque,
                family = descriptor.vendorId,
                model = descriptor.sensorModelId,
                serial = descriptor.serialSuffix,
                updated_at = nowMs(),
            ),
            nowMs = nowMs(),
        )
    }

    override suspend fun setAuthoritative(id: CgmSourceId) = repository.setAuthoritativeSource(id)

    override suspend fun activate(id: CgmSourceId) = repository.activateSource(id)

    override suspend fun deactivate(id: CgmSourceId) = repository.deactivateSource(id)

    override suspend fun loadSources(): List<CgmSourceDescriptor> = repository.observeSources().first()

    override suspend fun authoritativeSourceId(): CgmSourceId? = repository.authoritativeSourceId()

    override suspend fun activeSourceIds(): List<CgmSourceId> = repository.activeSourceIds()

    override suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int) =
        repository.setSourceWarmupWindowMin(id, minutes)

    override suspend fun hide(id: CgmSourceId) = repository.hideSource(id)

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
