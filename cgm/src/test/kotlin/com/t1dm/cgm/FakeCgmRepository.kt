package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/** Recording [CgmRepository] double: captures the connected source's persistence calls. */
open class FakeCgmRepository : CgmRepository {

    val upsertedReadings = mutableListOf<CgmReading>()
    val rawAdverts = mutableListOf<RawRow>()
    val warmupWindowWrites = mutableListOf<Pair<CgmSourceId, Int>>()
    val hidden = mutableListOf<CgmSourceId>()

    data class RawRow(
        val sourceId: CgmSourceId?,
        val rxWallMs: Long,
        val payload: ByteArray,
        val crcValid: Boolean,
        val minFromStart: Int?,
    )

    override suspend fun upsertReading(reading: CgmReading) {
        upsertedReadings += reading
    }

    override suspend fun insertRawAdvert(
        sourceId: CgmSourceId?,
        rxWallMs: Long,
        rssi: Int?,
        payload: ByteArray,
        crcValid: Boolean,
        minFromStart: Int?,
    ) {
        rawAdverts += RawRow(sourceId, rxWallMs, payload.copyOf(), crcValid, minFromStart)
    }

    val upsertedOrdinals = mutableMapOf<CgmSourceId, Int>()

    override suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int = upsertedOrdinals.getOrPut(descriptor.id) { upsertedOrdinals.size }

    override suspend fun setAuthoritative(id: CgmSourceId) = Unit
    override suspend fun activate(id: CgmSourceId) = Unit
    override suspend fun deactivate(id: CgmSourceId) = Unit
    override suspend fun loadSources(): List<CgmSourceDescriptor> = emptyList()
    override suspend fun authoritativeSourceId(): CgmSourceId? = null
    override suspend fun activeSourceIds(): List<CgmSourceId> = emptyList()

    override suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int) {
        warmupWindowWrites += id to minutes
    }

    override suspend fun hide(id: CgmSourceId) {
        hidden += id
    }

    /** Stored as the port handed them over, not sealed. */
    val sensorSecrets = mutableMapOf<CgmSourceId, ByteArray>()

    val secretWrites = mutableListOf<Pair<CgmSourceId, ByteArray>>()
    val secretsCleared = mutableListOf<CgmSourceId>()

    /** Runs at the instant a secret is written, before the call returns. */
    var onSecretWrite: (() -> Unit)? = null

    /** Thrown before anything is recorded: a write that did not happen. */
    var failSecretWrite: Throwable? = null

    override suspend fun loadSensorSecret(id: CgmSourceId): ByteArray? = sensorSecrets[id]

    override suspend fun saveSensorSecret(id: CgmSourceId, blob: ByteArray) {
        failSecretWrite?.let { throw it }
        sensorSecrets[id] = blob.copyOf()
        secretWrites += id to blob.copyOf()
        onSecretWrite?.invoke()
    }

    private val cursors = HashMap<String, Int>()

    override suspend fun loadSourceCursor(id: CgmSourceId): Int = cursors[id.value] ?: 0

    override suspend fun saveSourceCursor(id: CgmSourceId, cursor: Int) {
        cursors[id.value] = cursor
    }

    override suspend fun clearSensorSecret(id: CgmSourceId) {
        sensorSecrets -= id
        secretsCleared += id
    }
}
