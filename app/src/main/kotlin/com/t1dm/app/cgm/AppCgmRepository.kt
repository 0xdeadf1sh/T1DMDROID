package com.t1dm.app.cgm

import com.t1dm.cgm.CgmRepository
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.CgmAdvertRawEntity
import kotlinx.coroutines.flow.first

/** `serial` is never sent, by any setting: it identifies a real device, and the server would hold it
 *  permanently (`SPEC/http-api.md`, CGM source). `family` and `model` name a product, not a device.
 *  Never keyed off a display setting: a local display choice must not move data off the device. */
internal fun cgmSourceDto(
    descriptor: CgmSourceDescriptor,
    nowMs: Long,
): com.t1dm.sync.CgmSourceDto = com.t1dm.sync.CgmSourceDto(
    id = descriptor.id.opaque,
    family = descriptor.vendorId,
    model = descriptor.sensorModelId,
    serial = null,
    updated_at = nowMs,
)

class AppCgmRepository(
    private val repository: T1dmRepository,
    /** Null when none is wired: construction order, and test doubles. */
    private val enqueuer: com.t1dm.sync.OutboxEnqueuer? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Its own Keystore alias, never the watch's. */
    private val cipher: CgmSensorKeyCipher = CgmSensorKeyCipher(),
) : CgmRepository {

    override suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int {
        val ordinal = repository.upsertSource(descriptor, authoritative, lastSeenMs)
        // Deduped on the source id, so the coordinator's re-upserts collapse to one queued row.
        val now = nowMs()
        enqueuer?.enqueueCgmSource(cgmSourceDto(descriptor, nowMs = now), nowMs = now)
        return ordinal
    }

    override suspend fun setAuthoritative(id: CgmSourceId) = repository.setAuthoritativeSource(id)

    override suspend fun activate(id: CgmSourceId) = repository.activateSource(id)

    override suspend fun deactivate(id: CgmSourceId) = repository.deactivateSource(id)

    override suspend fun loadSources(): List<CgmSourceDescriptor> {
        // No-op unless a row reached the table unnumbered; the label cannot name such a sensor.
        repository.assignMissingSourceOrdinals()
        return repository.observeSources().first()
    }

    override suspend fun authoritativeSourceId(): CgmSourceId? = repository.authoritativeSourceId()

    override suspend fun activeSourceIds(): List<CgmSourceId> = repository.activeSourceIds()

    override suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int) =
        repository.setSourceWarmupWindowMin(id, minutes)

    override suspend fun hide(id: CgmSourceId) = repository.hideSource(id)

    override suspend fun upsertReading(reading: CgmReading) = repository.upsertReading(reading)

    /** An unreadable row returns null and the bytes stay on disk: it is the only copy of material that
     *  cannot be recovered from the sensor, so a failed open must never become a delete. */
    override suspend fun loadSensorSecret(id: CgmSourceId): ByteArray? =
        repository.sensorSecret(id)?.let(cipher::open)

    override suspend fun saveSensorSecret(id: CgmSourceId, blob: ByteArray) =
        repository.putSensorSecret(id, cipher.seal(blob), nowMs())

    override suspend fun clearSensorSecret(id: CgmSourceId) = repository.deleteSensorSecret(id)

    // Plain kv, not the sealed store: recoverable progress, not key material.
    override suspend fun loadSourceCursor(id: CgmSourceId): Int =
        repository.getKv(cursorKey(id))?.toIntOrNull() ?: 0

    override suspend fun saveSourceCursor(id: CgmSourceId, cursor: Int) =
        repository.putKv(cursorKey(id), cursor.toString(), nowMs())

    private fun cursorKey(id: CgmSourceId) = "cgm.cursor.${id.value}"

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
