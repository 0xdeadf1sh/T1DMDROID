package com.t1dm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.EventTombstone
import com.t1dm.core.model.ReconstructedBg
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/**
 * Room v1 frozen schema (§3.5 / Phase 1). Entities + DAOs only; the @Database
 * wiring, the ALTER-only migration runner, repositories, and tests belong to the Data
 * implementer. Keep-forever storage FORBIDS destructive migration — every later change is
 * additive (nullable columns, new tables), never a drop.
 *
 * Enum columns carry [Converters]; it is scoped per-entity so the schema is self-describing
 * before the @Database registers it globally.
 */

/** A discrete dose the user administered (Phase-1 minimal; the curve engine expands it later). */
enum class DoseKind { BOLUS, BASAL }

/**
 * Durable outbound-queue item class, persisted by name (adding a constant is additive/safe —
 * [Converters] round-trips via `OutboxKind.valueOf`). Eviction priority and age-evictability are
 * defined in `:sync` (`Outbox.kt`, consumed by `QueueDrainer`), not here. `SERIES` is kept only as
 * a tombstone: the app DB is never wiped, so a `SERIES` row enqueued before this upgrade must still
 * decode even though nothing enqueues it anymore.
 *
 * REMOVING a constant is the dangerous direction, and only two things make it safe: the endpoint it
 * pushed to must be gone from the wire contract (so no queued row could ever be delivered anyway),
 * and the same schema migration must purge every surviving row of that kind — `valueOf` throws on a
 * name it does not know, and one undecodable row poisons every later drain. `NOTE` left this way in
 * v9; see `MigrationRunner.MIGRATION_8_9`.
 */
enum class OutboxKind { ALERT, DOSE, MEAL, INGEST, STATS, PREDICTIONS, SERIES, PHOTO, CGM_SOURCE, NIGHTSCOUT }

/**
 * The dedupKey prefix marking a `NIGHTSCOUT` row as a BG slot rather than a treatment.
 *
 * It lives here, beside the enum, because BOTH sides need it and they sit on opposite sides of the
 * module boundary: this module WRITES the row (a reading landing, inside the projection transaction)
 * and `:sync` READS it back to decide what to upload. Spelled in each, the two would be free to drift
 * and the only symptom would be bridged readings that silently never send.
 */
const val NS_ENTRY_DEDUP_PREFIX = "ns:entry:"

/** The key a bridged meal/dose is filed under, prefix half. Here rather than in `:sync` because the
 *  repository has to WITHDRAW one when the event it mirrors is deleted, and a second spelling of the
 *  key would withdraw nothing. */
const val NS_TREATMENT_DEDUP_PREFIX = "ns:treat:"

/** Lifecycle of an outbox row across drain attempts. */
enum class OutboxState { PENDING, INFLIGHT, FAILED }

/**
 * One recorded CGM source. Two flags, and the difference between them is the whole of what the app
 * believes about a sensor (§3.1):
 *
 *  - [authoritative] — the ONE source that feeds the model, the statistics, the alarm engine, the
 *    `sample` projection and everything pushed over the wire. Exactly one row carries it.
 *  - [active] — the app is reading this sensor, and the BG panel may be switched to look at it.
 *    Many rows may carry it at once.
 *
 * [authoritative] implies [active]: the source every value on screen is derived from cannot be one
 * the app is not reading. The reverse does not hold, and that asymmetry is the point — a second
 * sensor can be worn, read and drawn without being believed, which is what makes an overlap between
 * a retiring sensor and its replacement visible rather than a choice.
 *
 * [sensorModelId] is the sensor FAMILY (`com.t1dm.core.model.CgmSensorModelId`) and is indexed because the BG
 * panel's history query joins through it: displayed history spans the class, so replacing a sensor
 * with another of the same model keeps one continuous trace. Widening what is DRAWN — across the
 * class, and now across the active set — never widens what is believed.
 *
 * [hidden] is a display flag and only that: the user has removed a retired sensor from the lists,
 * which never shrink on their own. The row survives, so [sensorModelId] still selects its readings
 * into the panel's history — deleting it instead would leave that stretch of the trace unreachable
 * and its `cgm_reading` rows unreclaimable. Hiding clears [active]: a sensor off the lists is one
 * the user has finished with, and holding a link to it afterwards would cost battery for a reading
 * nothing can show. Not indexed: the one list that filters on it holds a row per sensor the phone
 * has ever met, and scanning that is free.
 */
@Entity(tableName = "cgm_source", indices = [Index("sensorModelId")])
data class CgmSourceEntity(
    @PrimaryKey val sourceId: String,
    val vendorId: String,
    val sensorModelId: String,
    val advertName: String?,
    val displayName: String,
    val serialSuffix: String?,
    val authoritative: Boolean,
    val active: Boolean,
    val warmupWindowMin: Int,
    val addedAtMs: Long,
    val lastSeenMs: Long?,
    val hidden: Boolean,
    /**
     * A small stable number per sensor, zero-based, for the surfaces that must name a sensor without
     * printing what it advertises. `-1` until one has been minted.
     *
     * **Persisted rather than derived, because a rank derived from order is not stable.** Numbering by
     * `addedAtMs` at read time looks equivalent and is not: an archive restore reads `addedAtMs`
     * verbatim, so bringing an older sensor back inserts a row earlier in the order and renumbers every
     * sensor after it — the number the user has learned to read as one physical device silently becomes
     * another. Minted once, in the write transaction that first records the row, and never revised.
     */
    @ColumnInfo(defaultValue = "-1") val ordinal: Int,
)

/**
 * One sensor's sealed, opaque secret — the per-sensor state that cannot be recovered from the sensor
 * itself once lost.
 *
 * **Deliberately knows nothing about what it holds.** The bytes are written and read by the CGM plugin
 * that owns that family; their layout and its versioning live there. This table stores a byte string
 * against a source id and interprets none of it, so no sensor family's protocol is named in `:data`.
 *
 * **Sealed at rest.** The composition root wraps [blob] with an AndroidKeyStore key before it arrives
 * here, on the same pattern the watch key material uses — the one place in this app that holds a
 * Keystore handle is the one place that can.
 *
 * **A table of its own, not a `kv` row.** The full-erase clears `kv` wholesale even when it is
 * preserving the CGM sources, and for a sensor still on the patient's arm these bytes may be the only
 * thing that could ever release it. A separate table can be preserved by the same reset that preserves
 * the source rows.
 *
 * Not in the archive: the archive is a portable file, and sealing is per-install, so a wrapped blob
 * would restore as unreadable bytes on any other phone and as a false promise on this one.
 */
@Entity(tableName = "cgm_sensor_secret")
data class CgmSensorSecretEntity(
    @PrimaryKey val sourceId: String,
    val blob: ByteArray,
    val updatedAtMs: Long,
) {
    // ByteArray in a data class: identity equality would make two equal rows unequal, and Room's own
    // diffing and the tests both compare by value.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is CgmSensorSecretEntity &&
                sourceId == other.sourceId &&
                blob.contentEquals(other.blob) &&
                updatedAtMs == other.updatedAtMs
            )

    override fun hashCode(): Int =
        (31 * (31 * sourceId.hashCode() + blob.contentHashCode())) + updatedAtMs.hashCode()

    /** Never log the bytes. */
    override fun toString(): String =
        "CgmSensorSecretEntity(sourceId=$sourceId, ${blob.size} sealed bytes, updatedAtMs=$updatedAtMs)"
}

/**
 * Authoritative per-source reading store, grid-keyed on `(sourceId, tsMs)` so the GridStamper
 * upserts in place (§3.1). `tsMs % 300_000 == 0` for every row.
 *
 * One row per slot is the point of it, so a sensor sampling faster than the grid has to lose
 * samples here. Which one survives is [com.t1dm.data.supersedesGridSlot]'s decision, and the ones it
 * discards are kept in [CgmRawSampleEntity] rather than dropped.
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
 * Every accepted sample at the instant it is filed under, off the grid — the sub-grid record
 * [CgmReadingEntity] cannot hold (see [com.t1dm.core.model.CgmRawSample] for what a row means, and which
 * instant that is for a source that dates its own samples).
 *
 * Keyed on `(sourceId, rxWallMs)` rather than a slot, which is the whole difference: a three-minute
 * sensor puts five samples into three slots, and only here do all five survive. Insert is IGNORE, so
 * a re-delivery of an instant already held keeps the first row and the write is idempotent.
 *
 * `index_cgm_sample_raw_rxWallMs` exists for the retention sweep alone
 * ([com.t1dm.data.T1dmRepository.pruneRawSamples]) — the primary key already serves every per-source
 * read, but it leads on `sourceId`, so an age sweep across all sources would scan without this.
 *
 * DELIBERATELY not in the archive; the reason is on `ArchiveWriter`, with the rest of the exclusions.
 */
@Entity(
    tableName = "cgm_sample_raw",
    primaryKeys = ["sourceId", "rxWallMs"],
    indices = [Index("rxWallMs")],
)
@TypeConverters(Converters::class)
data class CgmRawSampleEntity(
    val sourceId: String,
    val rxWallMs: Long,
    val bgMgdl: Int?,
    val trendTenthsPerMin: Int?,
    val minFromStart: Int?,
    val quality: Int?,
    val flag: ReadingFlag,
    val tzOffsetMin: Int,
    val rssi: Int?,
)

/**
 * The materialized wide scalar projection (§3.5): six nullable series —
 * `bg`, `hr`, `steps`, `sleep`, `exercise`, `mood`. `hr`/`sleep` stay null until a source exists
 * (adding one is data-only, no migration). Carbs/bolus/basal are no longer projected here: they are
 * self-describing curve events (`logged_meal`/`logged_dose`/`basal_schedule`).
 *
 * Five of the six are integral; `exercise` is the one that is not, so a reader must not assume the
 * row is a bundle of counts.
 */
@Entity(tableName = "sample")
@TypeConverters(Converters::class)
data class SampleEntity(
    @PrimaryKey val ts: Long,          // ts % 300_000 == 0
    val tzOffsetMin: Int,
    val bgMgdl: Int?,                  // projected from cgm_reading (authoritative source)
    // Which sensor produced [bgMgdl] — the authoritative source at the instant the row was written.
    // Opaque and stable per sensor (`CgmSourceId.opaque`), so it can cross the wire as `bg_source`
    // without carrying the serial. Null for a row written before v15, and for a slot with no bg.
    val bgSource: String?,
    val bgProvenance: ReadingProvenance?,
    val bgFlag: ReadingFlag?,
    val steps: Int?,                   // from :sensors StepSource
    val mood: Int?,                    // from the Logs panel's mood picker
    val hr: Int?,                      // wired-but-null until a source exists
    val sleep: Int?,
    // Grams of carbohydrate equivalent disposed in this bucket (invariants.md §3), the exercise
    // gamma of §5 laid on the grid. Fractional, hence REAL and not the seconds column it replaced:
    // an ordinary bout's peak bucket is a couple of grams, and rounding one to an integer would lose
    // most of the curve. Written only by [com.t1dm.data.T1dmRepository.recordExerciseCurve].
    val exercise: Double?,
    val updatedAt: Long,
)

/**
 * Two-column projection of `sample` for the BG panel's Steps overlay: the bucket and its count, and
 * nothing else. Not an entity — a query result. The overlay needs the buckets [SampleDao.stepsInRange]
 * sums away, but reading whole [SampleEntity] rows for two of their eleven columns is what that query's
 * own KDoc was written to stop.
 */
data class StepBucketRow(val ts: Long, val steps: Int)

/**
 * A window of `sample` reduced to the numbers that move whenever anything the stats reduction reads
 * changes: the row count, the newest `updatedAt`, and the count of each column that actually reaches
 * a `StatSample`.
 *
 * The staleness fingerprint the stats cache validates against, and each part earns its place:
 *
 *  - `n` catches an insert; without it a new bucket would be invisible.
 *  - `maxUpdatedAt` catches an ordinary in-place merge — a steps or mood write into a bucket that
 *    already existed — which moves no count. Together with `n` it also catches a DELETE.
 *  - the three per-column counts catch the one writer that moves NEITHER: `SampleGapFill.fill`
 *    merges a server catch-up into an existing row and deliberately preserves the local `updatedAt`
 *    (it is the phone-authored stamp, stored verbatim per `SPEC/invariants.md` §7, and moving it
 *    would change ordering semantics). That merge only ever turns a NULL into a value, never the
 *    reverse, so a fill of any stats-bearing column raises its count.
 *
 * `bgMgdl`/`steps`/`mood` are exactly the columns `toStatSample` projects — `hr`/`sleep`/`exercise`
 * reach no metric, so a gap-fill touching only those SHOULD hit the cache rather than invalidate it.
 * `exercise` has a real writer now ([com.t1dm.data.T1dmRepository.recordExerciseCurve]), so that
 * claim is the only thing keeping this fingerprint sufficient: the first statistic to read it must
 * add an `nExercise` count here in the same change, or the cache will serve a window it has already
 * missed.
 *
 * Resolved in SQL over the `ts` primary-key range, so a check costs one aggregate scan rather than
 * materialising the ~26 000 whole rows a 90-day window holds.
 */
data class SampleWindowFingerprint(
    val n: Int,
    val maxUpdatedAt: Long,
    val nBg: Int,
    val nSteps: Int,
    val nMood: Int,
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

/**
 * A logged insulin dose with its full curve/PK parameters (Room v3, §3.3/§3.5,
 * Phase 4). Unlike the Phase-1 [DoseEventEntity] (units only), this row is **self-describing**:
 * it carries the exact gamma (bolus) or Bateman (basal) parameters used to reconstruct the
 * insulin-action curve, so the reconstructed `insulin_combined` channel is stable even if the
 * quick-preset defaults change later. Carb/insulin channels are event-reconstructed from these
 * rows, so the CGM reboot-gap interpolation touches BG only (SPEC §3.3).
 *
 * A [DoseKind.BOLUS] fills `k`/`theta` (gamma, `simulator.bolus_pk_for_dose`); a
 * [DoseKind.BASAL] fills `kaPerHour`/`kePerHour` (Bateman). `durationMin` is the DIA; `units`
 * is the total the curve integrates to.
 */
@Entity(
    tableName = "logged_dose",
    indices = [Index("tsMs"), Index(value = ["clientId"], unique = true)],
)
@TypeConverters(Converters::class)
data class LoggedDoseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Phone-minted UUID, set once at insert and never re-minted: the idempotency key the server
    // upserts on (PUT /v1/doses) and the id this row is re-hydrated by on catch-up.
    val clientId: String,
    val tsMs: Long,
    val kind: DoseKind,
    val units: Double,
    val durationMin: Double,
    val k: Double?,          // gamma shape (BOLUS)
    val theta: Double?,      // gamma scale (BOLUS)
    val kaPerHour: Double?,  // Bateman absorption (BASAL)
    val kePerHour: Double?,  // Bateman elimination (BASAL)
    // A user-drawn action curve (per-5-min absolute units, f64 BLOB) from a custom insulin type;
    // when present it OVERRIDES the analytic gamma/Bateman on reconstruction, mirroring
    // `logged_meal.customCurve`. Added additively in Room v5 (`ALTER TABLE … ADD COLUMN`).
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray? = null,
    val tzOffsetMin: Int,
    val note: String?,
    val updatedAt: Long,
    // The wall clock when this row was INSERTed, never revised by an edit (Room v21). It is the
    // earlier half of the log-gap mark: `MAX(MIN(tsMs, loggedAtMs))`. Retiming a dose forward must
    // not be able to quiet the rail that says the log may be stale, and pinning the mark to when
    // the phone was TOLD is what stops it.
    //
    // `defaultValue` as well as the Kotlin default: `MIGRATION_20_21` adds this column with
    // `DEFAULT 0`, and a Kotlin default governs the INSERT while saying nothing about the DDL — so
    // without it a fresh install's table differs from an upgraded one's at the same schema version.
    @ColumnInfo(defaultValue = "0") val loggedAtMs: Long = 0L,
    // When this row was last edited; null until it first is (Room v21).
    val mutatedAtMs: Long? = null,
    // The action-curve end of this dose AS IT STOOD BEFORE its first edit — `ts + durationMin` read
    // from the pre-edit row. The dose-history rail's window is the LATER of this and the current
    // row's end, because the edits that understate IOB most are exactly the ones that shorten the
    // post-edit window: a `durationMin` cut from 360 to 30 would otherwise block for five minutes
    // while IOB stayed wrong for five and a half hours. Null until first edited.
    val mutatedActingUntilMs: Long? = null,
)

/**
 * A logged meal (Room v3, Phase 4). Carbs feed the model as an **appearance (Ra)** gamma curve;
 * `gi` (glycemic index) parameterizes that gamma (`k`/`theta`): juice ⇒ high early peak, bread ⇒
 * spread. `k`/`theta`/`durationMin` are stored resolved so reconstruction is stable. When
 * [customCurve] is non-null it is a user-drawn per-5-min appearance curve (LE `f64` BLOB, see
 * [toBlob]) that OVERRIDES the gamma — the food-builder's custom-curve path.
 */
@Entity(
    tableName = "logged_meal",
    indices = [Index("tsMs"), Index(value = ["clientId"], unique = true)],
)
data class LoggedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Phone-minted UUID, set once at insert and never re-minted: the idempotency key the server
    // upserts on (PUT /v1/meals) and the id this row is re-hydrated by on catch-up.
    val clientId: String,
    val tsMs: Long,
    val grams: Double,
    val gi: Double?,
    val k: Double?,
    val theta: Double?,
    val durationMin: Double,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray?,
    val tzOffsetMin: Int,
    val note: String?,
    val updatedAt: Long,
    /** See [LoggedDoseEntity.loggedAtMs], `defaultValue` included. */
    @ColumnInfo(defaultValue = "0") val loggedAtMs: Long = 0L,
    /** See [LoggedDoseEntity.mutatedAtMs]. */
    val mutatedAtMs: Long? = null,
)

/**
 * A logged event the patient deleted (Room v21). The row survives the event, and that is the whole
 * mechanism: only a surviving row carries the `updatedAt` the server's ordering guard compares a
 * stale redelivery against, and only a local record of the deletion stops an id-keyed catch-up
 * hydration from resurrecting it.
 *
 * See `com.t1dm.core.model.EventTombstone` for the three jobs it does. [pushEnqueuedAtMs] is null
 * until `:app` has filed the outbox row, so a process death between the delete transaction and the
 * enqueue leaves the connect-time replay something to find.
 *
 * [kind] is raw TEXT mapped at the repository edge, matching `exercise_session.kind`, so the table
 * carries no dependency on the model enum's ordinal.
 */
@Entity(tableName = "event_tombstone", indices = [Index("tsMs"), Index("pushEnqueuedAtMs")])
data class EventTombstoneEntity(
    @PrimaryKey val clientId: String,
    val kind: String,
    val tsMs: Long,
    val tzOffsetMin: Int,
    // Phone clock at deletion, forced strictly newer than the row it retires.
    val updatedAt: Long,
    val createdAtMs: Long,
    val pushEnqueuedAtMs: Long?,
    // For a dose, `tsMs + durationMin` of the row as it stood when it was deleted. The rail keeps
    // blocking while a deleted dose could still be acting, and after the delete there is no row
    // left to read a duration off.
    val actingUntilMs: Long? = null,
)

/**
 * One injection of a daily-repeating basal schedule (Room v3, Phase 4). Rows sharing a
 * [scheduleId] form one schedule; exactly one schedule has `active = 1`. The active rows expand
 * (via `extend_basal`) into the near-flat Bateman background the model always sees, and are the
 * search space for the day-long basal calculator (SPEC §3.6). `timeOfDayMin` is minutes from the
 * local midnight defined by `tzOffsetMin`.
 */
@Entity(tableName = "basal_schedule", indices = [Index("scheduleId"), Index("active")])
data class BasalScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: String,
    val label: String,
    val timeOfDayMin: Int,
    val doseU: Double,
    val durationMin: Double,
    val kaPerHour: Double,
    val kePerHour: Double,
    val tzOffsetMin: Int,
    val active: Boolean,
    val updatedAt: Long,
)

/**
 * One entry of the bundled glycemic dictionary (Room v4, Phase 4 deliverable 3).
 * Carbs-per-100 g + glycemic index for common foods (`FoodSeed`), plus user-added custom foods
 * ([custom] = 1). Full-text search rides the external-content FTS5 shadow table `food_fts` (kept
 * in sync by triggers, created in [MigrationRunner.MIGRATION_3_4] and the DB `onCreate` callback);
 * this table is the authoritative content. [customCurve], when non-null, is a user-drawn
 * **normalized** appearance shape (per-5-min f64 BLOB summing to ~1.0, see [toBlob]) that overrides
 * the GI-derived gamma. Nutrient facts are not copyrightable (see `FoodSeed` for provenance).
 */
@Entity(tableName = "food", indices = [Index("name"), Index("custom")])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String?,
    val carbsPer100g: Double,
    val gi: Double?,
    val category: String,
    val source: String,
    val custom: Boolean,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray?,
    val updatedAt: Long,
)

/** A saved multi-food meal header (Room v4, Phase 4 "saved meals"); its portions are
 *  [SavedMealItemEntity] rows. */
@Entity(tableName = "saved_meal")
data class SavedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val updatedAt: Long,
)

/**
 * One food + portion of a [SavedMealEntity] (Room v4, Phase 4). The nutrition fields are
 * **snapshotted** at save time (denormalized) so a saved meal is stable even if the referenced
 * [foodId] is later edited or deleted; [foodId] remains a soft link for re-opening in the builder.
 */
@Entity(tableName = "saved_meal_item", indices = [Index("mealId")])
data class SavedMealItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val foodId: Long?,
    val name: String,
    val grams: Double,
    val carbsPer100g: Double,
    val gi: Double?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray?,
)

/**
 * A configured insulin type (Room v4, Phase 4, `:feature:insulin`): the seeded quick presets
 * ([builtin] = 1: Novorapid gamma; Lantus/Tresiba Bateman) plus user-defined custom types. Like
 * [LoggedDoseEntity] it is self-describing — a [DoseKind.BOLUS] fills `k`/`theta` (gamma), a
 * [DoseKind.BASAL] fills `kaPerHour`/`kePerHour` (Bateman). [customCurve], when set, is a
 * user-authored **normalized** action shape (per-5-min f64 BLOB) overriding the analytic curve.
 */
@Entity(tableName = "insulin_type", indices = [Index("builtin")])
@TypeConverters(Converters::class)
data class InsulinTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: DoseKind,
    val durationMin: Double,
    val k: Double?,
    val theta: Double?,
    val kaPerHour: Double?,
    val kePerHour: Double?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray?,
    val builtin: Boolean,
    val updatedAt: Long,
)

/** Raw captured adverts for forensics / replay (Phase 1). */
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

/** Durable outbound queue (Phase 1 thin enqueue-on-write; drained in Phase 3). */
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

/**
 * Dedicated forecast store (Room v2, Phase 3 deliverable). Matches the server
 * `Prediction` schema — `made_at`(=cycle grid ts), `model_id`, `horizon_steps`, `line`, the
 * `nQuantiles × H` `fan`, and the optional circadian `tod`/`tod_conf` — and *replaces* the Phase-2
 * `kv`-blob `PredictionStore` as the source of truth for the dashboard overlay and the
 * `PREDICTIONS` outbox push.
 *
 * The numeric series ride as little-endian `f64` BLOBs (see [Blobs]): [lineBlob] is `H` doubles
 * (== the 0.5 quantile row); [fanBlob] is `nQuantiles·H` **quantile-major** doubles in the exact
 * server row order `[0.05,0.1,0.25,0.5,0.75,0.9,0.95]` (`fan[q·H + s]`), so a wire push is a
 * straight reshape with no re-transpose. The local rehydrate fields ([backend]/[precision]/
 * [selected]/[stale]/[status]/[lastBg]/[latencyMs]/[anchorTsMs]/[stepMs]) let a full
 * [com.t1dm.core.model.ModelPrediction] be reconstructed for the overlay.
 *
 * `(madeAtMs, modelId)` is unique — one row per model per cycle — so a re-run of the same cycle
 * REPLACEs in place (idempotent local store) rather than accreting duplicates.
 */
@Entity(
    tableName = "prediction",
    indices = [
        Index(value = ["madeAtMs", "modelId"], unique = true),
        Index("madeAtMs"),
        Index("modelId"),
    ],
)
@TypeConverters(Converters::class)
data class PredictionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val madeAtMs: Long,                 // == ModelPrediction.cycleTsMs; server `made_at`
    val modelId: String,
    val horizonSteps: Int,
    val nQuantiles: Int,
    val stepMs: Long,
    val anchorTsMs: Long,
    val lastBg: Double,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val lineBlob: ByteArray,   // H f64
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val fanBlob: ByteArray,    // nQuantiles·H f64, q-major
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val todBlob: ByteArray?,   // 12 f64 or null
    val todConf: Double?,
    val status: ForecastStatus,
    val backend: BackendId,
    val precision: Precision,
    val selected: Boolean,
    val stale: Boolean,
    val latencyMs: Double?,
    val createdAtMs: Long,
)

/**
 * A configured T1DMSERVER endpoint (Room v2, Phase 3 deliverable). The schema is
 * **N-profile from the start** with **exactly one** `active = true`; full CRUD/switch UI is Phase 7
 * but the shape is frozen now so later work is additive. The `rw` **token never lands here** — it
 * lives in the Keystore-backed `TokenStore`, keyed by [id] — so a DB export/backup never leaks the
 * secret. `baseUrl` is a bare `http(s)://host:port` (Tailscale ⇒ TLS is moot).
 */
@Entity(tableName = "server_profile")
data class ServerProfileEntity(
    @PrimaryKey val id: String,
    val label: String,
    val baseUrl: String,
    val active: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Hardware / inference telemetry; Phase 2 tags rows by `modelId` (§2.4). */
@Entity(tableName = "hw_telemetry", indices = [Index("tsMs"), Index("modelId")])
data class HwTelemetryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMs: Long,
    val metric: String,
    val modelId: String?,
    val valueReal: Double?,
    val valueText: String?,
)

/**
 * One freehand stroke drawn over the BG graph panel (Room v8) — the persisted half of
 * [com.t1dm.core.model.PaintStroke]. Pure user annotation: no channel, calculator or §3.6 rail ever
 * reads it, so it is display-only and is wiped whole by the issue-5 reset.
 *
 * [points] is the (absolute epoch-ms, plot-height fraction) polyline in the versioned LE encoding of
 * [PaintStrokeBlob] — never pixels, so the drawing scrolls with the data and survives the Y auto-fit.
 * [minTsMs]/[maxTsMs] are that polyline's time bounds hoisted OUT of the blob and indexed, because
 * viewport culling ("which strokes touch the window on screen?") is the only query this table has and
 * it cannot reach inside a BLOB. They are derived, never authored — the mapper computes them by
 * scanning every point, since a stroke that doubles back in X has no sorted first/last to shortcut to.
 *
 * There is no `updatedAt`: a stroke is immutable once lifted — the only mutations are insert and
 * delete — so [createdAtMs] is the whole story, and it doubles as the stacking order (later strokes
 * paint over earlier ones). [tool] is TEXT rather than an enum-with-converter so a row written by a
 * later build never fails `valueOf` on an older one.
 */
@Entity(tableName = "bg_paint_stroke", indices = [Index("minTsMs"), Index("maxTsMs")])
data class PaintStrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMs: Long,
    val tool: String,
    val colorArgb: Int,
    val widthDp: Float,
    val minTsMs: Long,
    val maxTsMs: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val points: ByteArray,
)

/**
 * One model's fitted split-conformal band correction (Room v10) — the persisted half of
 * [com.t1dm.core.model.BandCalibration], `SPEC/inference.md` §8.4.
 *
 * It is here rather than in `kv` for three reasons: the payload is `steps · nQuantiles` f64 and
 * `kv.value` is TEXT, so it would have to be re-encoded; the provenance columns beside it
 * ([nCal]/[nEval]/[cov90Raw]/[cov90Cal]/[fittedAtMs]) are what lets the drill-down say what the
 * correction is worth rather than merely that one exists; and the row is per-model, which a keyed
 * table expresses and a flat namespace fakes. [deltaBlob] rides in the same little-endian f64
 * encoding as the `prediction` fan/line ([Blobs]) — one codec for every numeric series in this
 * database.
 *
 * [deltaBlob] is **step-major, τ-minor** (`i = s·nQuantiles + q`), matching
 * [com.t1dm.core.model.ModelPrediction.bandsMgdl] and the core's `Forecast::bands_mgdl` — NOT the
 * quantile-major layout [PredictionEntity.fanBlob] uses for the wire. The correction is applied to
 * a fan in core layout and never travels, so it is stored in the layout it is used in.
 *
 * `modelId` is the primary key: one correction per model, replaced whole by a later fit. Only a
 * SUFFICIENT fit is ever written — a refusal leaves the previous row intact, since a fit that
 * declined to run has learnt nothing about whether the previous one was wrong.
 */
@Entity(tableName = "conformal_delta")
data class ConformalDeltaEntity(
    @PrimaryKey val modelId: String,
    val steps: Int,
    val nQuantiles: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val deltaBlob: ByteArray,  // steps·nQuantiles f64, step-major
    val nCal: Int,
    val nEval: Int,
    val maxAbsDeltaMgdl: Double,
    val cov90Raw: Double?,
    val cov90Cal: Double?,
    val meanWidth90Raw: Double?,
    val meanWidth90Cal: Double?,
    val windowDays: Int,
    val fittedAtMs: Long,
)

/**
 * One model-reconstructed BG sample over a gap the sensor left (Room v20; span and promotion v22).
 *
 * **This is not a reading and must never be counted as one.** It lives in its own table for that
 * reason: `SPEC/invariants.md` §1 makes a filled value a presentation step, and this one is not
 * even a carry-forward — it is what a model thinks was there.
 *
 * What reads it: the conditioning series (`RoomBgHistoryProvider.recentBgSeries` — a seven-day
 * context with a hole in it is a context the model never saw), the BG panel, and, once promoted,
 * the `sample` row and the wire. What never reads it, promoted or not: the alarm engine, the dose
 * calculator, `fitBgSeries`, `dosingBgSeries`, the statistics and the accuracy suite.
 *
 * [lo90]/[hi90] carry the fan the fill came with, because a reconstructed value without its
 * uncertainty invites exactly the reading it must not be given — and because this row is the ONLY
 * place that band exists. The wire carries a boolean and no fan.
 *
 * [bandsMgdl] and [bandsRisk] are the WHOLE fan (Room v24) — seven levels per slot, ascending τ —
 * where [lo90]/[hi90] are its outer pair alone. Both are written by one statement from one run, so
 * the outer pair is not a second fact free to drift; it stays a column because the promotion path,
 * the archive and the wire all read it by name. The risk-space copy is what τ is interpolated in
 * (`SPEC/inference.md` §8: the fan is assembled there, and interpolating in mg/dL crosses the
 * warp), and [mgdl] is the line read at [tau] — not necessarily the median.
 *
 * Both blobs are EMPTY on a row written before v24. A fill from then has an outer band and no fan,
 * so it draws as one band and its τ cannot be moved; that is the honest state, and inventing five
 * interior levels from two edges would draw a shape the model never emitted.
 *
 * @param spanStartMs the `ts` of the first row of the contiguous run this belongs to. Promotion and
 *   demotion act on a span, and a contiguity scan at read time is not an identity two operations
 *   can be relied on to agree about. Pre-v22 rows are back-filled to their own `ts` — each becomes
 *   a one-step span, which is honest: the runs were never recorded, and guessing them from a
 *   gap-and-island query inside a migration would invent an identity nothing authored.
 * @param promotedAtMs when this span was written into the record as a stored sample, or null while
 *   it is only a drawing.
 */
@Entity(tableName = "bg_infill", indices = [Index(value = ["spanStartMs"])])
data class BgInfillEntity(
    @PrimaryKey val ts: Long,
    val mgdl: Double,
    val lo90: Double,
    val hi90: Double,
    val modelId: String,
    val createdAtMs: Long,
    @ColumnInfo(defaultValue = "0") val spanStartMs: Long = 0,
    val promotedAtMs: Long? = null,
    // Room v24. Every one carries `defaultValue` as well as a Kotlin default, and the two must
    // agree with `MIGRATION_23_24`'s `ALTER TABLE … DEFAULT`: a Kotlin default alone governs the
    // INSERT and says nothing about the DDL, so a FRESH install's table would differ from an
    // UPGRADED one's and Room's own schema validation would report the mismatch.
    /** Seven mg/dL levels per slot, ascending τ. Empty on a pre-v24 row. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB, defaultValue = "x''")
    val bandsMgdl: ByteArray = ByteArray(0),
    /** The same fan in risk space — what τ is interpolated in. Empty on a pre-v24 row. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB, defaultValue = "x''")
    val bandsRisk: ByteArray = ByteArray(0),
    /** Which quantile [mgdl] is the line at. `0.5` is the median, and every pre-v24 row is one. */
    @ColumnInfo(defaultValue = "0.5") val tau: Double = 0.5,
) {
    // Room entities are compared by value in tests and by identity nowhere; the two blobs make the
    // generated `equals` reference-compare, which is why both are spelled out here.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BgInfillEntity) return false
        return ts == other.ts && mgdl == other.mgdl && lo90 == other.lo90 && hi90 == other.hi90 &&
            modelId == other.modelId && createdAtMs == other.createdAtMs &&
            spanStartMs == other.spanStartMs && promotedAtMs == other.promotedAtMs &&
            bandsMgdl.contentEquals(other.bandsMgdl) && bandsRisk.contentEquals(other.bandsRisk) &&
            tau == other.tau
    }

    override fun hashCode(): Int {
        var h = ts.hashCode()
        h = 31 * h + mgdl.hashCode()
        h = 31 * h + lo90.hashCode()
        h = 31 * h + hi90.hashCode()
        h = 31 * h + modelId.hashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + spanStartMs.hashCode()
        h = 31 * h + (promotedAtMs?.hashCode() ?: 0)
        h = 31 * h + bandsMgdl.contentHashCode()
        h = 31 * h + bandsRisk.contentHashCode()
        h = 31 * h + tau.hashCode()
        return h
    }
}

/**
 * One low-rank adapter (Room v20) — a fitted personalisation of ONE model's BG head.
 *
 * The base weights live in the `.pte` and are never written; everything trainable is the few
 * thousand numbers in [blob], serialized by `t1dm-core::lora_serialize` with its own digest. The
 * blob is opaque here on purpose: this table stores and lists adapters, and the crate is the only
 * thing that reads one.
 *
 * [modelId] is the model the adapter was fitted against and the ONLY one it may attach to — an
 * adapter carries its own trunk geometry and the crate refuses a foreign one, but nothing should
 * get that far. [attached] is what the forecast path reads; at most one adapter per model may
 * carry it, which the repository enforces rather than the schema.
 *
 * The held-out numbers are stored beside the weights because they are the only basis anyone has
 * for attaching it: a fit that did not beat the frozen head learnt the patient's past, not their
 * physiology, and a row that lost its report would be a set of weights with nothing to judge.
 */
@Entity(tableName = "lora", indices = [Index(value = ["modelId"])])
data class LoraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    val name: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val blob: ByteArray,
    val rank: Int,
    val alpha: Double,
    val targets: Int,
    val nParams: Int,
    val nTrain: Int,
    val nHoldout: Int,
    val epochs: Int,
    val holdoutBefore: Double,
    val holdoutAfter: Double,
    val improved: Boolean,
    val attached: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    // ── the counterfactual guard's verdict (Room v23) ──
    //
    // Stored on the ROW rather than in the adapter blob, deliberately: the blob is what the
    // adapter IS and its format is unchanged, while this is provenance — what was measured about
    // it, when, and on what evidence. An adapter arriving by import or by archive restore
    // therefore has no verdict, which is the honest state and the one that refuses attach.
    // Every one of these carries `defaultValue` as well as a Kotlin default, and the two must agree
    // with `MIGRATION_22_23`'s `ALTER TABLE … DEFAULT`. A Kotlin default alone governs the INSERT
    // and says nothing about the DDL, so a FRESH install's `lora` table would have no defaults at
    // all where an UPGRADED one does — the exact drift a fresh-vs-upgraded comparison exists to
    // catch, and one that Room's own schema validation reports as a mismatch.
    /** `PASS` / `BLOCKED` / `INCONCLUSIVE` / `ABSENT`, by name. Absent means never probed. */
    @ColumnInfo(defaultValue = "ABSENT") val guardVerdict: String = "ABSENT",
    @ColumnInfo(defaultValue = "0") val guardWindows: Int = 0,
    /** mg/dL per unit at the horizon, frozen model and adapted. Stored so a refusal can be READ
     *  rather than trusted — the ratio alone hides which side moved. */
    @ColumnInfo(defaultValue = "0") val guardFrozenMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardAdaptedMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardRetention: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardSignAgreement: Double = 0.0,
    @ColumnInfo(defaultValue = "") val guardWhy: String = "",
    /** How many training windows carried a counterfactual branch, and what the distillation term
     *  ran at. Zero means the fit could not see the dose response at all. */
    @ColumnInfo(defaultValue = "0") val nPaired: Int = 0,
    @ColumnInfo(defaultValue = "0") val distillScale: Double = 0.0,
    /**
     * When the user deliberately overrode a refusal, or null.
     *
     * It sticks to THIS row: a re-fit makes a fresh row with no override, so an override can
     * never outlive the adapter it was granted for.
     */
    val guardOverrideAtMs: Long? = null,
    /**
     * When the dose or meal history this adapter was fitted on was last edited or deleted, or
     * null. An adapter fitted on a history that has since changed describes windows that no
     * longer exist, and attach refuses until it is re-fitted or overridden.
     */
    val historyMutatedAtMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val fittedAtMs: Long = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is LoraEntity && id == other.id && modelId == other.modelId && name == other.name &&
            blob.contentEquals(other.blob) && rank == other.rank && alpha == other.alpha &&
            targets == other.targets && nParams == other.nParams && nTrain == other.nTrain &&
            nHoldout == other.nHoldout && epochs == other.epochs &&
            holdoutBefore == other.holdoutBefore && holdoutAfter == other.holdoutAfter &&
            improved == other.improved && attached == other.attached &&
            createdAtMs == other.createdAtMs && updatedAtMs == other.updatedAtMs &&
            guardVerdict == other.guardVerdict && guardWindows == other.guardWindows &&
            guardFrozenMgdl == other.guardFrozenMgdl && guardAdaptedMgdl == other.guardAdaptedMgdl &&
            guardRetention == other.guardRetention &&
            guardSignAgreement == other.guardSignAgreement && guardWhy == other.guardWhy &&
            nPaired == other.nPaired && distillScale == other.distillScale &&
            guardOverrideAtMs == other.guardOverrideAtMs &&
            historyMutatedAtMs == other.historyMutatedAtMs && fittedAtMs == other.fittedAtMs

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + modelId.hashCode()) + blob.contentHashCode()
}

/**
 * One start-to-stop exercise bout (Room v16) — the phone-local record of a logged session.
 *
 * **This row is not what exercise means to the model or to the server.** The bout's glucose-disposal
 * curve goes into the wide sample's `exercise` scalar through
 * [com.t1dm.data.T1dmRepository.recordExerciseCurve], and that is the whole of what syncs. This row
 * and its [ExerciseFixEntity] track record how those seconds were spent; they cross no wire, there
 * being no route, track or session object on it.
 *
 * [activeSec] therefore stays SECONDS while the scalar it feeds is grams: this is the bout as the
 * patient lived it, and the curve is derived from its duration rather than stored beside it.
 *
 * [startMs]/[endMs] are wall-clock instants and are deliberately NOT grid-snapped — a bout begins
 * when the user says so, and only the derived per-bucket `sample` write is on the five-minute grid.
 * A null [endMs] is a bout still open, or one the app never saw stopped;
 * [com.t1dm.data.exercise.ExerciseController.reconcileOpenSessions] settles the second case at the
 * next launch by closing it at what was actually recorded and setting [interrupted].
 *
 * [clientId] and [updatedAt] are the §7 authority columns [LoggedMealEntity] and [LoggedDoseEntity]
 * carry, minted here even though nothing syncs this table: a bout is a user-authored log event, and
 * the insert is the one moment its id can be minted honestly. Retro-fitting an idempotency key onto
 * rows written without one cannot be done.
 *
 * [kind] is raw TEXT with no [Converters] entry, following [PaintStrokeEntity.tool]: a row written by
 * a later build must never fail `valueOf` on an older one. It is mapped at the repository edge, where
 * a name this build does not know becomes [com.t1dm.core.model.ExerciseKind.OTHER].
 *
 * [kcal] is what could be justified on the day and nothing recomputes it — a later change of body
 * mass must not silently rewrite the energy of a bout already walked. Null where no figure could be
 * justified at all.
 */
@Entity(
    tableName = "exercise_session",
    indices = [Index(value = ["clientId"], unique = true), Index("startMs")],
)
data class ExerciseSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String,
    val startMs: Long,
    val endMs: Long?,
    val tzOffsetMin: Int,
    val kind: String,
    val activeSec: Int,
    val distanceM: Double?,
    val kcal: Int?,
    val interrupted: Boolean,
    val note: String?,
    val updatedAt: Long,
)

/**
 * One accepted GPS fix on a bout's track (Room v16).
 *
 * A row per fix rather than one polyline BLOB per bout, unlike [PaintStrokeEntity]: a stroke is
 * written once when the finger lifts, whereas a recording appends for as long as it runs, and the
 * blob shape would rewrite the whole track on every fix. At a fix every four seconds an hour costs
 * ~900 rows, which is nothing beside `cgm_reading`.
 *
 * **No foreign key onto `exercise_session`.** The cascade is an explicit delete in the same
 * transaction ([com.t1dm.data.T1dmRepository.deleteExerciseSession]). Whether SQLite enforces one
 * under [androidx.sqlite.driver.bundled.BundledSQLiteDriver] rests on a `PRAGMA foreign_keys` state
 * nothing here sets, and a constraint silently not enforced is worse than none — it reads as a
 * guarantee.
 *
 * [accuracyM] is the receiver's own horizontal accuracy. Which fixes are accepted at all is decided
 * in `:sensors`, so a row here has already passed that filter.
 */
@Entity(tableName = "exercise_fix", indices = [Index(value = ["sessionId", "tsMs"])])
data class ExerciseFixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

/** `event_tombstone.kind` for a deleted meal — raw TEXT, mapped at the repository edge. */
const val TOMBSTONE_KIND_MEAL = "meal"

/** `event_tombstone.kind` for a deleted dose. */
const val TOMBSTONE_KIND_DOSE = "dose"

/** The dependency-free shape `:app` builds the deletion's wire body from. */
fun EventTombstoneEntity.toModel(): EventTombstone = EventTombstone(
    clientId = clientId,
    kind = if (kind == TOMBSTONE_KIND_DOSE) CurveKind.INSULIN else CurveKind.CARB,
    tsMs = tsMs,
    tzOffsetMin = tzOffsetMin,
    updatedAt = updatedAt,
    actingUntilMs = actingUntilMs,
)

/** Where this dose's action curve ends, from the row as it stands. */
fun LoggedDoseEntity.actingUntilMs(): Long = tsMs + (durationMin * 60_000.0).toLong()

/**
 * True when [next] moves the insulin channel rather than merely relabelling the row.
 *
 * A note or a timezone correction changes nothing a forecast was conditioned on, and invalidating
 * a band correction and a window of stored predictions over one would be a visible, unexplained
 * change to what the patient sees for no reason at all.
 */
fun LoggedDoseEntity.affectsChannel(next: LoggedDoseEntity): Boolean =
    tsMs != next.tsMs ||
        kind != next.kind ||
        units != next.units ||
        durationMin != next.durationMin ||
        k != next.k ||
        theta != next.theta ||
        kaPerHour != next.kaPerHour ||
        kePerHour != next.kePerHour ||
        !customCurve.contentEquals(next.customCurve)

/** The meal twin of [LoggedDoseEntity.affectsChannel]; `gi` counts because the appearance gamma is
 *  resolved from it. */
fun LoggedMealEntity.affectsChannel(next: LoggedMealEntity): Boolean =
    tsMs != next.tsMs ||
        grams != next.grams ||
        gi != next.gi ||
        k != next.k ||
        theta != next.theta ||
        durationMin != next.durationMin ||
        !customCurve.contentEquals(next.customCurve)

/**
 * The `customCurve` an edit of this row into [next] must store.
 *
 * A stored curve is ABSOLUTE — every generator behind it takes the amount as a linear scale — so it
 * is keyed to the amount that produced it. Carried across an edit unchanged it goes on describing
 * the pre-edit dose, and the curve engine prefers it over `units`, so IOB, the ceiling rail and the
 * forecast all keep reading the number the edit corrected away.
 *
 * Linearity is what makes the ratio exact, and it holds for all four generators: `gamma`,
 * `expAction`, `bateman`, and a normalized user shape multiplied by the amount. Rescaling therefore
 * also preserves a user-drawn shape, which re-deriving from a preset would discard.
 *
 * A change to a SHAPE input cannot be rescaled, so the curve is dropped and the row re-derives from
 * the params it carries. A caller that supplied its own re-derived curve is left alone.
 */
fun LoggedDoseEntity.curveAfterEdit(next: LoggedDoseEntity): ByteArray? = when {
    !next.customCurve.contentEquals(customCurve) -> next.customCurve
    kind != next.kind || durationMin != next.durationMin || k != next.k || theta != next.theta ||
        kaPerHour != next.kaPerHour || kePerHour != next.kePerHour -> null
    else -> customCurve.rescaledBy(units, next.units)
}

/** The meal twin of [LoggedDoseEntity.curveAfterEdit]; `gi` counts as a shape input because the
 *  appearance gamma is resolved from it. */
fun LoggedMealEntity.curveAfterEdit(next: LoggedMealEntity): ByteArray? = when {
    !next.customCurve.contentEquals(customCurve) -> next.customCurve
    gi != next.gi || durationMin != next.durationMin || k != next.k || theta != next.theta -> null
    else -> customCurve.rescaledBy(grams, next.grams)
}

/** [this] scaled by [to]/[from], or null where the ratio is not usable and the row's own params
 *  are the better authority. */
private fun ByteArray?.rescaledBy(from: Double, to: Double): ByteArray? {
    if (this == null) return null
    if (from == to) return this
    if (!from.isFinite() || !to.isFinite() || from == 0.0) return null
    val factor = to / from
    return toDoubleList().map { it * factor }.toBlob()
}

/** A reconstructed row as the drawing layer's own type. */
fun BgInfillEntity.toModel(): ReconstructedBg = ReconstructedBg(
    tsMs = ts,
    mgdl = mgdl,
    lo90 = lo90,
    hi90 = hi90,
    modelId = modelId,
    spanStartMs = if (spanStartMs == 0L) ts else spanStartMs,
    promoted = promotedAtMs != null,
    // Only a fan of the expected width is handed on. A blob of some other length is a row written
    // by a build that meant something else by it, and half a fan drawn as nested bands would be a
    // shape nothing emitted.
    bands = bandsMgdl.toDoubleList().takeIf { it.size == FAN_LEVELS }.orEmpty(),
    tau = tau,
)

/** The seven quantile levels the head emits (`SPEC/invariants.md` §6) — the width of one slot's fan. */
private const val FAN_LEVELS = 7
