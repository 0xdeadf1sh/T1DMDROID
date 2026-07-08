package com.t1dm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/**
 * Room v1 frozen schema (PLAN.private.md §3.5 / Phase 1). Entities + DAOs only; the @Database
 * wiring, the ALTER-only migration runner, repositories, and tests belong to the Data
 * implementer. Keep-forever storage FORBIDS destructive migration — every later change is
 * additive (nullable columns, new tables), never a drop.
 *
 * Enum columns carry [Converters]; it is scoped per-entity so the schema is self-describing
 * before the @Database registers it globally.
 */

/** A discrete dose the user administered (Phase-1 minimal; the curve engine expands it later). */
enum class DoseKind { BOLUS, BASAL }

/** Durable outbound-queue item class; eviction priority ALERT > NOTE > INGEST > PREDICTIONS >
 *  SERIES > PHOTO (PLAN.private.md Phase 3). */
enum class OutboxKind { ALERT, NOTE, INGEST, PREDICTIONS, SERIES, PHOTO }

/** Lifecycle of an outbox row across drain attempts. */
enum class OutboxState { PENDING, INFLIGHT, FAILED }

/** One recorded CGM source; exactly one row has `active = true` (PLAN.private.md §3.1). */
@Entity(tableName = "cgm_source")
data class CgmSourceEntity(
    @PrimaryKey val sourceId: String,
    val vendorId: String,
    val displayName: String,
    val serialSuffix: String?,
    val active: Boolean,
    val warmupWindowMin: Int,
    val addedAtMs: Long,
    val lastSeenMs: Long?,
)

/**
 * Authoritative per-source reading store, grid-keyed on `(sourceId, tsMs)` so the GridStamper
 * upserts in place (PLAN.private.md §3.1). `tsMs % 300_000 == 0` for every row.
 */
@Entity(
    tableName = "cgm_reading",
    primaryKeys = ["sourceId", "tsMs"],
    indices = [Index("tsMs")],
)
@TypeConverters(Converters::class)
data class CgmReadingEntity(
    val sourceId: String,
    val tsMs: Long,
    val bgMgdl: Int?,
    val trendTenthsPerMin: Int?,
    val minFromStart: Int?,
    val quality: Int?,
    val provenance: ReadingProvenance,
    val flag: ReadingFlag,
    val tzOffsetMin: Int,
    val rxWallMs: Long,
    val rssi: Int?,
)

/**
 * The materialized wide 9-series projection (PLAN.private.md §3.5). All series nullable from
 * the start; `hr/sleep/exercise` stay null until a source exists (adding one is data-only, no
 * migration). Merge is last-writer-wins on [updatedAt].
 */
@Entity(tableName = "sample")
@TypeConverters(Converters::class)
data class SampleEntity(
    @PrimaryKey val ts: Long,          // ts % 300_000 == 0
    val tzOffsetMin: Int,
    val bgMgdl: Int?,                  // projected from cgm_reading (active source)
    val bgProvenance: ReadingProvenance?,
    val bgFlag: ReadingFlag?,
    val carbsG: Double?,               // projected from logged_meal
    val bolusU: Double?,               // projected from logged_dose
    val basalU: Double?,               // projected from basal schedule
    val steps: Int?,                   // from :sensors StepSource
    val mood: Int?,                    // from journal mood picker
    val hr: Int?,                      // wired-but-null until a source exists
    val sleep: Int?,
    val exercise: Int?,
    val updatedAt: Long,
)

/** Minimal discrete dose event (Phase 1). The full curve/PK expansion lands in Phase 4. */
@Entity(tableName = "dose_event", indices = [Index("tsMs")])
@TypeConverters(Converters::class)
data class DoseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMs: Long,
    val kind: DoseKind,
    val units: Double,
    val tzOffsetMin: Int,
    val note: String?,
    val updatedAt: Long,
)

/** Raw captured adverts for forensics / replay (PLAN.private.md Phase 1). */
@Entity(tableName = "cgm_advert_raw", indices = [Index("rxWallMs")])
data class CgmAdvertRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String?,
    val rxWallMs: Long,
    val rssi: Int?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    val crcValid: Boolean,
    val minFromStart: Int?,
)

/** Durable outbound queue (PLAN.private.md §Phase 1 thin enqueue-on-write; drained in Phase 3). */
@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index("state"),
        Index("createdAtMs"),
    ],
)
@TypeConverters(Converters::class)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: OutboxKind,
    val dedupKey: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    val createdAtMs: Long,
    val attempts: Int,
    val nextAttemptMs: Long,
    val state: OutboxState,
)

/** Small key/value store (e.g. `kv.last_alive_ts` service heartbeat). */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/** Hardware / inference telemetry; Phase 2 tags rows by `modelId` (PLAN.private.md §2.4). */
@Entity(tableName = "hw_telemetry", indices = [Index("tsMs"), Index("modelId")])
data class HwTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMs: Long,
    val metric: String,
    val modelId: String?,
    val valueReal: Double?,
    val valueText: String?,
)
