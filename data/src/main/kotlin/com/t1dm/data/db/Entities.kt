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

/** Keep-forever storage: every schema change is additive, never a drop. */

enum class DoseKind { BOLUS, BASAL }

/**
 * Persisted by name. Adding a constant is safe; REMOVING one poisons every later drain unless the
 * same migration purges its surviving rows, `valueOf` throwing on a name it does not know. `SERIES`
 * is kept only so rows enqueued before it stopped being written still decode.
 */
enum class OutboxKind { ALERT, DOSE, MEAL, INGEST, STATS, PREDICTIONS, SERIES, PHOTO, CGM_SOURCE, NIGHTSCOUT }

/** Marks a `NIGHTSCOUT` row as a BG slot, not a treatment. Here rather than in `:sync` because this
 *  module writes the row and `:sync` reads it back; two spellings would silently drift. */
const val NS_ENTRY_DEDUP_PREFIX = "ns:entry:"

/** Bridged meal/dose key prefix. Here because the repository must WITHDRAW one when the event it
 *  mirrors is deleted, and a second spelling would withdraw nothing. */
const val NS_TREATMENT_DEDUP_PREFIX = "ns:treat:"

enum class OutboxState { PENDING, INFLIGHT, FAILED }

/**
 * [authoritative] — the one source feeding the model, stats, alarms, the `sample` projection and the
 * wire; exactly one row carries it, and it implies [active]. [active] — the app is reading this
 * sensor; many rows may. [sensorModelId] is the FAMILY the panel's history spans. [hidden] is
 * display-only: the row survives so its readings stay reachable, and hiding clears [active].
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
    /** Stable zero-based number per sensor, for naming one without printing what it advertises;
     *  `-1` until minted. Persisted, not derived from `addedAtMs` order: an archive restore inserts
     *  an older row and would renumber every sensor after it. */
    @ColumnInfo(defaultValue = "-1") val ordinal: Int,
)

/**
 * [blob] is opaque here — the owning CGM plugin decides its layout — and arrives already wrapped by
 * an AndroidKeyStore key. Its own table rather than a `kv` row, so the full-erase can preserve it
 * alongside the source rows. Not in the archive: sealing is per-install.
 */
@Entity(tableName = "cgm_sensor_secret")
data class CgmSensorSecretEntity(
    @PrimaryKey val sourceId: String,
    val blob: ByteArray,
    val updatedAtMs: Long,
) {
    // Hand-written: a data class compares [blob] by identity.
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

/** Grid-keyed on `(sourceId, tsMs)`; `tsMs % 300_000 == 0`. One row per slot — the samples
 *  [com.t1dm.data.supersedesGridSlot] discards are kept in [CgmRawSampleEntity]. */
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
 * Keyed on `(sourceId, rxWallMs)` rather than a slot, so a sensor faster than the grid keeps every
 * sample. Insert is IGNORE, so a re-delivery of an instant already held is a no-op. The `rxWallMs`
 * index serves the retention sweep alone. Not in the archive; see `ArchiveWriter`.
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

/** Six nullable series. Carbs/bolus/basal are NOT projected here — they are curve events
 *  (`logged_meal`/`logged_dose`/`basal_schedule`). `exercise` is the one non-integral series. */
@Entity(tableName = "sample")
@TypeConverters(Converters::class)
data class SampleEntity(
    @PrimaryKey val ts: Long,          // ts % 300_000 == 0
    val tzOffsetMin: Int,
    val bgMgdl: Int?,                  // projected from cgm_reading (authoritative source)
    // Opaque stable sensor id (`CgmSourceId.opaque`), so it crosses the wire without the serial.
    // Null for a row written before v15, and for a slot with no bg.
    val bgSource: String?,
    val bgProvenance: ReadingProvenance?,
    val bgFlag: ReadingFlag?,
    val steps: Int?,                   // from :sensors StepSource
    val mood: Int?,                    // from the Logs panel's mood picker
    val hr: Int?,                      // wired-but-null until a source exists
    val sleep: Int?,
    // Grams of carbohydrate equivalent disposed in this bucket (`SPEC/invariants.md` §3, §5),
    // fractional. Written only by [com.t1dm.data.T1dmRepository.recordExerciseCurve].
    val exercise: Double?,
    val updatedAt: Long,
)

data class StepBucketRow(val ts: Long, val steps: Int)

/**
 * The staleness key the stats cache validates against. `n` catches an insert, `maxUpdatedAt` an
 * in-place merge, and the three per-column counts a `SampleGapFill.fill` that moves neither. They
 * are exactly the columns `toStatSample` projects: a statistic that starts reading `exercise` must
 * add a count for it here, or the cache serves a window it has already missed.
 */
data class SampleWindowFingerprint(
    val n: Int,
    val maxUpdatedAt: Long,
    val nBg: Int,
    val nSteps: Int,
    val nMood: Int,
)

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
 * Self-describing: a [DoseKind.BOLUS] fills `k`/`theta` (gamma), a [DoseKind.BASAL]
 * `kaPerHour`/`kePerHour` (Bateman), so the reconstructed channel is stable when the presets
 * change. `durationMin` is the DIA; `units` is the total the curve integrates to.
 */
@Entity(
    tableName = "logged_dose",
    indices = [Index("tsMs"), Index(value = ["clientId"], unique = true)],
)
@TypeConverters(Converters::class)
data class LoggedDoseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Phone-minted UUID, set once at insert: the key the server upserts on (PUT /v1/doses) and the
    // id this row is re-hydrated by on catch-up.
    val clientId: String,
    val tsMs: Long,
    val kind: DoseKind,
    val units: Double,
    val durationMin: Double,
    val k: Double?,          // gamma shape (BOLUS)
    val theta: Double?,      // gamma scale (BOLUS)
    val kaPerHour: Double?,  // Bateman absorption (BASAL)
    val kePerHour: Double?,  // Bateman elimination (BASAL)
    // User-drawn action curve: per-5-min absolute units, f64 BLOB. When present it OVERRIDES the
    // analytic gamma/Bateman on reconstruction.
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray? = null,
    val tzOffsetMin: Int,
    val note: String?,
    val updatedAt: Long,
    // Wall clock at INSERT, never revised by an edit: retiming a dose must not quiet the stale-log
    // rail. `defaultValue` as well as the Kotlin default, which governs the INSERT and says nothing
    // about the DDL — without it a fresh install's table differs from an upgraded one's.
    @ColumnInfo(defaultValue = "0") val loggedAtMs: Long = 0L,
    // Null until first edited.
    val mutatedAtMs: Long? = null,
    // `ts + durationMin` of this row BEFORE its first edit; null until then. The dose-history rail
    // takes the LATER of this and the current end, since an edit that shortens `durationMin` would
    // otherwise stop blocking while IOB stayed wrong.
    val mutatedActingUntilMs: Long? = null,
)

/** Carbs feed the model as a gamma appearance (Ra) curve parameterised by `gi`, with
 *  `k`/`theta`/`durationMin` stored resolved. [customCurve], a per-5-min f64 BLOB, overrides it. */
@Entity(
    tableName = "logged_meal",
    indices = [Index("tsMs"), Index(value = ["clientId"], unique = true)],
)
data class LoggedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Phone-minted UUID, set once at insert: the key the server upserts on (PUT /v1/meals) and the
    // id this row is re-hydrated by on catch-up.
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
 * Survives the event it retires: it carries the `updatedAt` the server's ordering guard compares a
 * stale redelivery against, and stops an id-keyed catch-up hydration from resurrecting it.
 * [pushEnqueuedAtMs] is null until `:app` has filed the outbox row. [kind] is raw TEXT.
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
    // For a dose, `tsMs + durationMin` as it stood at deletion: the rail keeps blocking while a
    // deleted dose could still be acting, and no row is left to read a duration off.
    val actingUntilMs: Long? = null,
)

/** Rows sharing [scheduleId] form one daily schedule; exactly one schedule has `active = 1`.
 *  `timeOfDayMin` is minutes from the local midnight defined by `tzOffsetMin`. */
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

/** The authoritative content behind the external-content FTS5 table `food_fts`, which triggers keep
 *  in sync. [customCurve] is a normalized per-5-min f64 shape summing to ~1.0, overriding the
 *  GI-derived gamma. */
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

/** Header; its portions are [SavedMealItemEntity] rows. */
@Entity(tableName = "saved_meal")
data class SavedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val updatedAt: Long,
)

/** Nutrition is snapshotted at save time, so a saved meal is stable when the food is later edited
 *  or deleted; [foodId] stays a soft link for re-opening in the builder. */
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

/** Self-describing like [LoggedDoseEntity]: BOLUS fills `k`/`theta`, BASAL `kaPerHour`/`kePerHour`.
 *  [customCurve] is a normalized per-5-min action shape overriding the analytic curve. */
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

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/**
 * Little-endian f64 BLOBs (see [Blobs]): [lineBlob] is `H` doubles (the 0.5 row); [fanBlob] is
 * `nQuantiles·H` QUANTILE-MAJOR (`fan[q·H + s]`) in the server row order
 * `[0.05,0.1,0.25,0.5,0.75,0.9,0.95]`. `(madeAtMs, modelId)` is unique, so a re-run REPLACEs.
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
    /** NULL is UNKNOWN and matches no source, so the row is refused by the maturation walk rather
     *  than scored against whichever sensor is authoritative later. */
    val sourceId: String?,
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

/** Exactly one row has `active = true`. The `rw` token never lands here — it lives in the
 *  Keystore-backed `TokenStore`, keyed by [id] — so a DB export never leaks it. */
@Entity(tableName = "server_profile")
data class ServerProfileEntity(
    @PrimaryKey val id: String,
    val label: String,
    val baseUrl: String,
    val active: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

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
 * Display-only: no channel, calculator or rail reads it. [points] is the (epoch-ms, plot-height
 * fraction) polyline in [PaintStrokeBlob]'s encoding, never pixels; [minTsMs]/[maxTsMs] are its
 * bounds hoisted out and indexed for viewport culling, derived and never authored. A stroke is
 * immutable, so there is no `updatedAt` and [createdAtMs] doubles as the stacking order.
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
 * `SPEC/inference.md` §8.4. [deltaBlob] is STEP-major, τ-minor (`i = s·nQuantiles + q`) — NOT the
 * quantile-major layout [PredictionEntity.fanBlob] uses for the wire. One row per model, replaced
 * whole by a later fit; only a SUFFICIENT fit is written, so a refusal leaves the previous intact.
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
    /** NULL is UNKNOWN and is refused by the apply, so such a correction draws the raw fan. */
    val sourceId: String?,
)

/**
 * Not a reading, and never counted as one (`SPEC/invariants.md` §1). [spanStartMs] is the `ts` of
 * the first row of the contiguous run promotion and demotion act on; [promotedAtMs] is null while
 * the span is only a drawing. [lo90]/[hi90] are the outer pair of [bandsMgdl].
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
    // `defaultValue` must agree with `MIGRATION_23_24`'s `ALTER TABLE … DEFAULT`: a Kotlin default
    // governs the INSERT and says nothing about the DDL, so without it a fresh install's table
    // differs from an upgraded one's.
    /** Seven mg/dL levels per slot, ascending τ. Empty on a pre-v24 row. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB, defaultValue = "x''")
    val bandsMgdl: ByteArray = ByteArray(0),
    /** The same fan in risk space — what τ is interpolated in; mg/dL would cross the warp. Empty
     *  on a pre-v24 row. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB, defaultValue = "x''")
    val bandsRisk: ByteArray = ByteArray(0),
    /** Which quantile [mgdl] is the line at; every pre-v24 row is the median. */
    @ColumnInfo(defaultValue = "0.5") val tau: Double = 0.5,
) {
    // Hand-written: the two blobs would make the generated `equals` reference-compare.
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
 * [blob] is opaque here — `t1dm-core::lora_serialize` owns its format. [modelId] is the only model
 * the adapter may attach to. At most one adapter per model carries [attached], which the repository
 * enforces rather than the schema.
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
    // Guard provenance, on the row rather than in the blob, so an adapter arriving by import or
    // archive restore has no verdict and is refused. `defaultValue` must agree with
    // `MIGRATION_22_23`'s DDL: a Kotlin default governs the INSERT and says nothing about it.
    /** `PASS` / `BLOCKED` / `INCONCLUSIVE` / `ABSENT`, by name. Absent means never probed. */
    @ColumnInfo(defaultValue = "ABSENT") val guardVerdict: String = "ABSENT",
    @ColumnInfo(defaultValue = "0") val guardWindows: Int = 0,
    /** mg/dL per unit at the horizon, frozen model and adapted; the ratio alone hides which
     *  side moved. */
    @ColumnInfo(defaultValue = "0") val guardFrozenMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardAdaptedMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardRetention: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardSignAgreement: Double = 0.0,
    @ColumnInfo(defaultValue = "") val guardWhy: String = "",
    /** Zero means the fit could not see the dose response at all. */
    @ColumnInfo(defaultValue = "0") val nPaired: Int = 0,
    @ColumnInfo(defaultValue = "0") val distillScale: Double = 0.0,
    /** When the user overrode a refusal, or null. It sticks to THIS row: a re-fit makes a fresh row
     *  with no override. */
    val guardOverrideAtMs: Long? = null,
    /** When the history this adapter was fitted on was last edited or deleted, or null. Attach
     *  refuses until it is re-fitted or overridden. */
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
 * Phone-local; nothing here syncs. [startMs]/[endMs] are wall-clock and deliberately NOT
 * grid-snapped, and a null [endMs] is a bout still open or one the app never saw stopped.
 * [activeSec] stays SECONDS while the `sample.exercise` scalar it feeds is grams. [kind] is raw
 * TEXT, mapped at the repository edge, where an unknown name becomes `ExerciseKind.OTHER`.
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

/** No foreign key onto `exercise_session`: the cascade is an explicit delete in the same
 *  transaction ([com.t1dm.data.T1dmRepository.deleteExerciseSession]), because enforcement would
 *  rest on a `PRAGMA foreign_keys` nothing here sets. [accuracyM] is horizontal accuracy. */
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

const val TOMBSTONE_KIND_MEAL = "meal"

const val TOMBSTONE_KIND_DOSE = "dose"

fun EventTombstoneEntity.toModel(): EventTombstone = EventTombstone(
    clientId = clientId,
    kind = if (kind == TOMBSTONE_KIND_DOSE) CurveKind.INSULIN else CurveKind.CARB,
    tsMs = tsMs,
    tzOffsetMin = tzOffsetMin,
    updatedAt = updatedAt,
    actingUntilMs = actingUntilMs,
)

fun LoggedDoseEntity.actingUntilMs(): Long = tsMs + (durationMin * 60_000.0).toLong()

/** True when [next] moves the insulin channel rather than relabelling the row: a note or timezone
 *  edit must not invalidate a band correction and a window of stored predictions. */
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

/** The meal twin; `gi` counts because the appearance gamma is resolved from it. */
fun LoggedMealEntity.affectsChannel(next: LoggedMealEntity): Boolean =
    tsMs != next.tsMs ||
        grams != next.grams ||
        gi != next.gi ||
        k != next.k ||
        theta != next.theta ||
        durationMin != next.durationMin ||
        !customCurve.contentEquals(next.customCurve)

/**
 * A stored curve is ABSOLUTE, keyed to the amount that produced it, and the engine prefers it over
 * `units` — so an edit must rescale it or IOB goes on reading the pre-edit dose. The ratio is exact
 * because all four generators are linear in the amount. A changed SHAPE input drops the curve.
 */
fun LoggedDoseEntity.curveAfterEdit(next: LoggedDoseEntity): ByteArray? = when {
    !next.customCurve.contentEquals(customCurve) -> next.customCurve
    kind != next.kind || durationMin != next.durationMin || k != next.k || theta != next.theta ||
        kaPerHour != next.kaPerHour || kePerHour != next.kePerHour -> null
    else -> customCurve.rescaledBy(units, next.units)
}

/** The meal twin; `gi` counts as a shape input because the appearance gamma is resolved from it. */
fun LoggedMealEntity.curveAfterEdit(next: LoggedMealEntity): ByteArray? = when {
    !next.customCurve.contentEquals(customCurve) -> next.customCurve
    gi != next.gi || durationMin != next.durationMin || k != next.k || theta != next.theta -> null
    else -> customCurve.rescaledBy(grams, next.grams)
}

/** Null where the ratio is not usable and the row's own params are the better authority. */
private fun ByteArray?.rescaledBy(from: Double, to: Double): ByteArray? {
    if (this == null) return null
    if (from == to) return this
    if (!from.isFinite() || !to.isFinite() || from == 0.0) return null
    val factor = to / from
    return toDoubleList().map { it * factor }.toBlob()
}

fun BgInfillEntity.toModel(): ReconstructedBg = ReconstructedBg(
    tsMs = ts,
    mgdl = mgdl,
    lo90 = lo90,
    hi90 = hi90,
    modelId = modelId,
    spanStartMs = if (spanStartMs == 0L) ts else spanStartMs,
    promoted = promotedAtMs != null,
    // Only a fan of the expected width is handed on: another length was written by a build that
    // meant something else, and half a fan would draw a shape nothing emitted.
    bands = bandsMgdl.toDoubleList().takeIf { it.size == FAN_LEVELS }.orEmpty(),
    tau = tau,
)

/** The quantile levels the head emits (`SPEC/invariants.md` §6). */
private const val FAN_LEVELS = 7
