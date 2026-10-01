package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingProvenance

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

    data class RateRepair(val prefix: String, val beforeMs: Long, val onceKey: String, val upsertsSoFar: Int)

    val rateRepairs = mutableListOf<RateRepair>()

    override suspend fun divideRatesByTenOnce(sourcePrefix: String, beforeMs: Long, onceKey: String): Int {
        rateRepairs += RateRepair(sourcePrefix, beforeMs, onceKey, upsertedReadings.size)
        return 0
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
    private val highest = HashMap<String, Int>()

    override suspend fun loadHighestDeliveredId(id: CgmSourceId): Int = highest[id.value] ?: 0

    override suspend fun saveHighestDeliveredId(id: CgmSourceId, glucoseId: Int) {
        highest[id.value] = glucoseId
    }

    override suspend fun loadSourceCursor(id: CgmSourceId): Int = cursors[id.value] ?: 0

    override suspend fun saveSourceCursor(id: CgmSourceId, cursor: Int) {
        cursors[id.value] = cursor
    }

    private val addresses = HashMap<String, String>()

    override suspend fun loadSensorAddress(id: CgmSourceId): String? = addresses[id.value]

    override suspend fun saveSensorAddress(id: CgmSourceId, address: String) {
        addresses[id.value] = address
    }

    val lifetimes = HashMap<String, Int>()

    override suspend fun loadSensorLifetimeMin(id: CgmSourceId): Int? = lifetimes[id.value]

    override suspend fun saveSensorLifetimeMin(id: CgmSourceId, minutes: Int) {
        lifetimes[id.value] = minutes
    }

    override suspend fun clearSensorSecret(id: CgmSourceId) {
        sensorSecrets -= id
        secretsCleared += id
    }

    /** The retention floor the fake vouches from; zero says the whole wear. */
    var rawSamplesCompleteSinceMs: Long = 0L

    /** Arrivals the fake will report, per source. */
    val arrivals = mutableMapOf<CgmSourceId, List<Ct5AnchorRepair.Arrival>>()

    val readingsDeletedFor = mutableListOf<CgmSourceId>()

    override suspend fun advertArrivals(id: CgmSourceId): List<Ct5AnchorRepair.Arrival> =
        arrivals[id] ?: emptyList()

    override suspend fun deleteReadingsForSource(id: CgmSourceId): Int {
        readingsDeletedFor += id
        val before = upsertedReadings.size
        upsertedReadings.removeAll { it.sourceId == id }
        return before - upsertedReadings.size
    }

    override suspend fun receivedSampleMinutes(id: CgmSourceId, notBeforeMs: Long): CgmReceivedSamples {
        val since = maxOf(rawSamplesCompleteSinceMs, notBeforeMs)
        return CgmReceivedSamples(
            completeSinceMs = since,
            minutes = upsertedReadings
                .filter { it.sourceId == id && it.provenance == ReadingProvenance.MEASURED && it.rxWallMs >= since }
                .mapNotNullTo(HashSet()) { it.minFromStart },
        )
    }
}
