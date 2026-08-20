package com.t1dm.app.cgm

import com.t1dm.cgm.CgmRepository
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.CgmAdvertRawEntity
import kotlinx.coroutines.flow.first

/**
 * The `PUT /v1/cgm-sources` body for one sensor.
 *
 * **`serial` is never sent, by any setting.** It is the number printed on the sensor — for one family it
 * is also the advertised name — so a client that sends it puts a real device identifier into the server's
 * storage, its backups and its operator console, permanently and with no way to recall it. Omitting it is
 * not a contract change and loses nothing but the console's ability to name the physical sensor
 * (`SPEC/http-api.md`, CGM source). `family` and `model` name a product rather than a device and are sent,
 * so the console can still say what kind of sensor a series came from.
 *
 * Deliberately NOT a function of the sensor-name display setting, which it once was. That setting decides
 * whether a name is drawn on this phone's own screen; making the upload depend on it meant reading a name
 * locally started transmitting the serial within one re-upsert, unprompted and irreversibly, from a switch
 * whose label promised only a display change. A local display choice must not move data off the device.
 *
 * Extracted from the adapter so the withholding is a property of a pure function and can be held to it.
 */
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
    /** Seals the per-sensor secrets at rest. Its own Keystore alias, never the watch's — see
     *  [CgmSensorKeyCipher]. */
    private val cipher: CgmSensorKeyCipher = CgmSensorKeyCipher(),
) : CgmRepository {

    override suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int {
        val ordinal = repository.upsertSource(descriptor, authoritative, lastSeenMs)
        // Push the descriptor so the server can resolve the opaque label its samples already carry.
        // Deduped on the source id, so the coordinator's re-upserts collapse to one queued row. What the
        // body may and may not carry is [cgmSourceDto]'s to decide, and it is not a setting.
        val now = nowMs()
        enqueuer?.enqueueCgmSource(cgmSourceDto(descriptor, nowMs = now), nowMs = now)
        return ordinal
    }

    override suspend fun setAuthoritative(id: CgmSourceId) = repository.setAuthoritativeSource(id)

    override suspend fun activate(id: CgmSourceId) = repository.activateSource(id)

    override suspend fun deactivate(id: CgmSourceId) = repository.deactivateSource(id)

    override suspend fun loadSources(): List<CgmSourceDescriptor> {
        // Number anything that reached the table without an ordinal before the coordinator reads the
        // list: a sensor the label cannot name is worth one query on the hydrate path to catch. No-op
        // otherwise — every writer numbers its own rows.
        repository.assignMissingSourceOrdinals()
        return repository.observeSources().first()
    }

    override suspend fun authoritativeSourceId(): CgmSourceId? = repository.authoritativeSourceId()

    override suspend fun activeSourceIds(): List<CgmSourceId> = repository.activeSourceIds()

    override suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int) =
        repository.setSourceWarmupWindowMin(id, minutes)

    override suspend fun hide(id: CgmSourceId) = repository.hideSource(id)

    override suspend fun upsertReading(reading: CgmReading) = repository.upsertReading(reading)

    /**
     * Sealed on the way in and opened on the way out, so the stored bytes are unreadable off this
     * install. This adapter is the only place with a Keystore handle, which is why the sealing lives here
     * and not in `:cgm` or `:data`.
     *
     * An unreadable row returns null rather than throwing, and the bytes stay on disk: it is the only copy
     * of material that cannot be recovered from the sensor, so a failed open must never become a delete.
     */
    override suspend fun loadSensorSecret(id: CgmSourceId): ByteArray? =
        repository.sensorSecret(id)?.let(cipher::open)

    override suspend fun saveSensorSecret(id: CgmSourceId, blob: ByteArray) =
        repository.putSensorSecret(id, cipher.seal(blob), nowMs())

    override suspend fun clearSensorSecret(id: CgmSourceId) = repository.deleteSensorSecret(id)

    // Plain kv, not the sealed store: this is recoverable progress, not key material — see the port.
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
