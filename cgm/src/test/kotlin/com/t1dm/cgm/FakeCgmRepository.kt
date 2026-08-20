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

    /** Every source ever upserted, in the order the ordinals were handed out. */
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

    /** The sealed secrets, unsealed — the double stores what the port was handed. */
    val sensorSecrets = mutableMapOf<CgmSourceId, ByteArray>()

    /** Every write, in order, so a test can assert WHEN a secret was persisted relative to a frame. */
    val secretWrites = mutableListOf<Pair<CgmSourceId, ByteArray>>()
    val secretsCleared = mutableListOf<CgmSourceId>()

    /**
     * Run at the instant a secret is written, before the call returns.
     *
     * The seam that makes "persisted BEFORE the irreversible frame" assertable: a test samples how many
     * frames had been written at this moment, which is the only way to observe an ordering that happens
     * inside one suspend call.
     */
    var onSecretWrite: (() -> Unit)? = null

    /**
     * Set to make the store REFUSE, as the real one can: sealing runs the Keystore and the write runs
     * SQLite, so a key-generation failure, an absent StrongBox or a full disk all arrive as a throw.
     * Nothing is recorded when it does — this is a write that did not happen.
     */
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
