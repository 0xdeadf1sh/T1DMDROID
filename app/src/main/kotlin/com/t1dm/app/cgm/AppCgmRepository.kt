package com.t1dm.app.cgm

import com.t1dm.cgm.CgmReceivedSamples
import com.t1dm.cgm.Ct5AnchorRepair
import com.t1dm.cgm.CgmRepository
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.CgmAdvertRawEntity
import kotlinx.coroutines.flow.first

class AppCgmRepository(
    private val repository: T1dmRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Its own Keystore alias, never the watch's. */
    private val cipher: CgmSensorKeyCipher = CgmSensorKeyCipher(),
) : CgmRepository {

    override suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int = repository.upsertSource(descriptor, authoritative, lastSeenMs)

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

    override suspend fun divideRatesByTenOnce(sourcePrefix: String, beforeMs: Long, onceKey: String): Int =
        repository.divideRatesByTenOnce(sourcePrefix, beforeMs, onceKey, nowMs())

    /** Unreadable returns null, bytes stay on disk: only copy, not recoverable from the sensor. */
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

    override suspend fun loadHighestDeliveredId(id: CgmSourceId): Int =
        repository.getKv(highestKey(id))?.toIntOrNull() ?: 0

    override suspend fun saveHighestDeliveredId(id: CgmSourceId, glucoseId: Int) =
        repository.putKv(highestKey(id), glucoseId.toString(), nowMs())

    private fun highestKey(id: CgmSourceId) = "cgm.highestId.${id.value}"

    override suspend fun loadSensorAddress(id: CgmSourceId): String? = repository.getKv(addressKey(id))

    override suspend fun saveSensorAddress(id: CgmSourceId, address: String) =
        repository.putKv(addressKey(id), address, nowMs())

    private fun addressKey(id: CgmSourceId) = "cgm.address.${id.value}"

    override suspend fun loadSensorLifetimeMin(id: CgmSourceId): Int? =
        repository.getKv(lifetimeKey(id))?.toIntOrNull()

    override suspend fun saveSensorLifetimeMin(id: CgmSourceId, minutes: Int) =
        repository.putKv(lifetimeKey(id), minutes.toString(), nowMs())

    private fun lifetimeKey(id: CgmSourceId) = "cgm.lifetimeMin.${id.value}"

    override suspend fun advertArrivals(id: CgmSourceId): List<Ct5AnchorRepair.Arrival> =
        repository.advertArrivals(id).map { (rx, min) -> Ct5AnchorRepair.Arrival(rx, min) }

    override suspend fun deleteReadingsForSource(id: CgmSourceId): Int =
        repository.deleteReadingsForSource(id)

    override suspend fun receivedSampleMinutes(id: CgmSourceId, notBeforeMs: Long): CgmReceivedSamples {
        val since = maxOf(repository.rawSamplesCompleteSince(nowMs()), notBeforeMs)
        return CgmReceivedSamples(since, repository.receivedSampleMinutes(id, since).toSet())
    }

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
