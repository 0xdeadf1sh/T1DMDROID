package com.t1dm.data

import androidx.room.withTransaction
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.CgmAdvertRawEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.DoseEventEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.HwTelemetryEntity
import com.t1dm.data.db.KvEntity
import com.t1dm.data.db.OutboxEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The single write/read gateway over [AppDatabase] (PLAN.private.md §3.5). Every mutation runs on
 * [T1dmDispatchers.io]; contributing-event writes atomically re-project the wide `sample` row and
 * thinly enqueue an `INGEST` outbox item (drained in Phase 3). Reads surface [Flow]s.
 *
 * Invariants enforced here rather than in the schema:
 *  - grid alignment: every `sample`/`cgm_reading` timestamp satisfies `ts % GRID_MS == 0`;
 *  - grid upsert-in-place: `(sourceId, tsMs)` is the reading key, so a MEASURED value overwrites a
 *    prior INTERPOLATED one at the same slot (PLAN §3.1);
 *  - projection provenance: INVALID readings never reach `sample`; the sample carries the bg
 *    provenance/flag so downstream (alarm, graph) can honour §3.6-A.
 */
class T1dmRepository(
    private val db: AppDatabase,
    private val dispatchers: T1dmDispatchers,
) {
    private val io get() = dispatchers.io

    private val sources get() = db.cgmSourceDao()
    private val readings get() = db.cgmReadingDao()
    private val samples get() = db.sampleDao()
    private val doses get() = db.doseEventDao()
    private val advertsRaw get() = db.cgmAdvertRawDao()
    private val outbox get() = db.outboxDao()
    private val kv get() = db.kvDao()
    private val telemetry get() = db.hwTelemetryDao()

    // ─── CGM sources ────────────────────────────────────────────────────────────────────────

    fun observeSources(): Flow<List<CgmSourceDescriptor>> =
        sources.observeAll().map { list -> list.map { it.toDescriptor() } }

    fun observeActiveSource(): Flow<CgmSourceDescriptor?> =
        sources.observeActive().map { it?.toDescriptor() }

    /** Register or update a source, preserving its original `addedAtMs` across updates. */
    suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        active: Boolean,
        nowMs: Long,
    ) = withContext(io) {
        db.withTransaction {
            val existing = sources.byId(descriptor.id.value)
            sources.upsert(
                CgmSourceEntity(
                    sourceId = descriptor.id.value,
                    vendorId = descriptor.vendorId,
                    displayName = descriptor.displayName,
                    serialSuffix = descriptor.serialSuffix,
                    active = active,
                    warmupWindowMin = descriptor.warmupWindowMin,
                    addedAtMs = existing?.addedAtMs ?: nowMs,
                    lastSeenMs = nowMs,
                ),
            )
            if (active) {
                sources.clearActive()
                sources.setActive(descriptor.id.value)
            }
        }
    }

    /** Enforce exactly-one-active atomically (PLAN §3.1). */
    suspend fun setActiveSource(id: CgmSourceId) = withContext(io) {
        db.withTransaction {
            sources.clearActive()
            sources.setActive(id.value)
        }
    }

    suspend fun activeSourceId(): CgmSourceId? = withContext(io) {
        sources.activeSourceId()?.let(::CgmSourceId)
    }

    // ─── Readings + wide-sample projection ──────────────────────────────────────────────────

    fun observeReadings(sourceId: CgmSourceId, fromMs: Long, toMs: Long): Flow<List<CgmReading>> =
        readings.observeRange(sourceId.value, fromMs, toMs).map { list -> list.map { it.toModel() } }

    fun observeLatestReading(sourceId: CgmSourceId): Flow<CgmReading?> =
        readings.observeLatest(sourceId.value).map { it?.toModel() }

    /**
     * Grid-stamp upsert-in-place; if the reading is on the active source and not INVALID, project
     * its bg into `sample` (LWW on `rxWallMs`) and enqueue one INGEST item for that grid slot.
     */
    suspend fun upsertReading(reading: CgmReading) = withContext(io) {
        requireGrid(reading.tsMs)
        db.withTransaction {
            readings.upsert(reading.toEntity())
            val active = sources.activeSourceId()
            if (active == reading.sourceId.value && reading.flag != ReadingFlag.INVALID) {
                projectBg(reading)
                enqueueIngest(reading.tsMs, reading.rxWallMs)
            }
        }
    }

    private suspend fun projectBg(reading: CgmReading) {
        val existing = samples.byTs(reading.tsMs)
        if (existing != null && reading.rxWallMs < existing.updatedAt) return // LWW: keep newer
        val base = existing ?: emptySample(reading.tsMs, reading.tzOffsetMin, reading.rxWallMs)
        samples.upsert(
            base.copy(
                tzOffsetMin = reading.tzOffsetMin,
                bgMgdl = reading.bgMgdl,
                bgProvenance = reading.provenance,
                bgFlag = reading.flag,
                updatedAt = maxOf(base.updatedAt, reading.rxWallMs),
            ),
        )
    }

    // ─── Samples ────────────────────────────────────────────────────────────────────────────

    fun observeSamples(fromMs: Long, toMs: Long): Flow<List<SampleEntity>> =
        samples.observeRange(fromMs, toMs)

    suspend fun sampleAt(ts: Long): SampleEntity? = withContext(io) { samples.byTs(ts) }

    /** Steps arrive already bucketed on the 5-min grid by :sensors (PLAN §3.5). */
    suspend fun recordSteps(gridTs: Long, tzOffsetMin: Int, steps: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(steps = steps) }

    suspend fun recordMood(gridTs: Long, tzOffsetMin: Int, mood: Int, nowMs: Long) =
        mergeSample(gridTs, tzOffsetMin, nowMs) { it.copy(mood = mood) }

    /** Log a discrete dose and fold its units into the enclosing grid bucket's `sample`. */
    suspend fun logDose(dose: DoseEventEntity): Long = withContext(io) {
        db.withTransaction {
            val id = doses.insert(dose)
            val gridTs = snapToGrid(dose.tsMs)
            mergeSampleInTx(gridTs, dose.tzOffsetMin, dose.updatedAt) { s ->
                when (dose.kind) {
                    DoseKind.BOLUS -> s.copy(bolusU = (s.bolusU ?: 0.0) + dose.units)
                    DoseKind.BASAL -> s.copy(basalU = (s.basalU ?: 0.0) + dose.units)
                }
            }
            id
        }
    }

    private suspend fun mergeSample(
        gridTs: Long,
        tzOffsetMin: Int,
        nowMs: Long,
        edit: (SampleEntity) -> SampleEntity,
    ) = withContext(io) {
        db.withTransaction { mergeSampleInTx(gridTs, tzOffsetMin, nowMs, edit) }
    }

    private suspend fun mergeSampleInTx(
        gridTs: Long,
        tzOffsetMin: Int,
        nowMs: Long,
        edit: (SampleEntity) -> SampleEntity,
    ) {
        requireGrid(gridTs)
        val base = samples.byTs(gridTs) ?: emptySample(gridTs, tzOffsetMin, nowMs)
        samples.upsert(edit(base).copy(tzOffsetMin = tzOffsetMin, updatedAt = maxOf(base.updatedAt, nowMs)))
        enqueueIngest(gridTs, nowMs)
    }

    // ─── Raw adverts (forensics/replay) ─────────────────────────────────────────────────────

    suspend fun recordRawAdvert(advert: CgmAdvertRawEntity): Long =
        withContext(io) { advertsRaw.insert(advert) }

    suspend fun pruneRawAdvertsBefore(beforeMs: Long): Int =
        withContext(io) { advertsRaw.pruneBefore(beforeMs) }

    // ─── Outbox ─────────────────────────────────────────────────────────────────────────────

    /** Thin enqueue-on-write (PLAN Phase 1); dedup is enforced by the unique `dedupKey` index. */
    suspend fun enqueue(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
    ): Long = withContext(io) { enqueueRow(kind, dedupKey, payload, nowMs) }

    fun observeOutboxDepth(): Flow<Int> = outbox.observeDepth()

    private suspend fun enqueueIngest(gridTs: Long, nowMs: Long) =
        enqueueRow(OutboxKind.INGEST, "ingest:sample:$gridTs", ByteArray(0), nowMs)

    private suspend fun enqueueRow(
        kind: OutboxKind,
        dedupKey: String,
        payload: ByteArray,
        nowMs: Long,
    ): Long = outbox.enqueue(
        OutboxEntity(
            kind = kind,
            dedupKey = dedupKey,
            payload = payload,
            createdAtMs = nowMs,
            attempts = 0,
            nextAttemptMs = 0,
            state = OutboxState.PENDING,
        ),
    )

    // ─── kv / hw_telemetry ──────────────────────────────────────────────────────────────────

    suspend fun putKv(key: String, value: String, nowMs: Long) =
        withContext(io) { kv.put(KvEntity(key, value, nowMs)) }

    suspend fun getKv(key: String): String? = withContext(io) { kv.get(key) }

    fun observeKv(key: String): Flow<String?> = kv.observe(key)

    suspend fun recordTelemetry(row: HwTelemetryEntity): Long =
        withContext(io) { telemetry.insert(row) }

    companion object {
        const val GRID_MS: Long = 300_000L

        private fun requireGrid(ts: Long) =
            require(ts % GRID_MS == 0L) { "timestamp not on the 5-min grid: $ts" }

        private fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        private fun emptySample(ts: Long, tzOffsetMin: Int, updatedAt: Long) = SampleEntity(
            ts = ts,
            tzOffsetMin = tzOffsetMin,
            bgMgdl = null,
            bgProvenance = null,
            bgFlag = null,
            carbsG = null,
            bolusU = null,
            basalU = null,
            steps = null,
            mood = null,
            hr = null,
            sleep = null,
            exercise = null,
            updatedAt = updatedAt,
        )
    }
}
