package com.t1dm.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/**
 * Room v1 frozen schema (SPEC.private.md §3.5 / Phase 1). Entities + DAOs only; the @Database
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
 */
enum class OutboxKind { ALERT, DOSE, MEAL, NOTE, INGEST, STATS, PREDICTIONS, SERIES, PHOTO }

/** Lifecycle of an outbox row across drain attempts. */
enum class OutboxState { PENDING, INFLIGHT, FAILED }

/** One recorded CGM source; exactly one row has `active = true` (SPEC.private.md §3.1). */
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
 * upserts in place (SPEC.private.md §3.1). `tsMs % 300_000 == 0` for every row.
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
 * The materialized wide scalar projection (SPEC.private.md §3.5): six nullable series —
 * `bg`, `hr`, `steps`, `sleep`, `exercise`, `mood`. `hr/sleep/exercise` stay null until a source
 * exists (adding one is data-only, no migration). Carbs/bolus/basal are no longer projected here:
 * they are self-describing curve events (`logged_meal`/`logged_dose`/`basal_schedule`).
 */
@Entity(tableName = "sample")
@TypeConverters(Converters::class)
data class SampleEntity(
    @PrimaryKey val ts: Long,          // ts % 300_000 == 0
    val tzOffsetMin: Int,
    val bgMgdl: Int?,                  // projected from cgm_reading (active source)
    val bgProvenance: ReadingProvenance?,
    val bgFlag: ReadingFlag?,
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

/**
 * A logged insulin dose with its full curve/PK parameters (Room v3, SPEC.private.md §3.3/§3.5,
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

/**
 * A free-text journal note (Room v4, Phase 4 deliverable 2 — the locked "mood +
 * free-text journal now" scope). This is the durable producer the outbox's reserved `NOTE` class
 * was awaiting: each row is mirrored via `POST /v1/notes`. Unlike the wide `sample`, [tsMs] is the
 * wall-clock instant the note was written (NOT grid-snapped) — a note annotates a moment, not a
 * 5-min bucket — so it is its own event store, never projected into `sample`.
 */
@Entity(tableName = "note", indices = [Index("tsMs")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMs: Long,
    val tzOffsetMin: Int,
    val text: String,
    val updatedAt: Long,
)

/** Hardware / inference telemetry; Phase 2 tags rows by `modelId` (SPEC.private.md §2.4). */
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
