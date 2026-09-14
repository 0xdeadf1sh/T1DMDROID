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
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/** Keep-forever storage: every schema change is additive, never a drop. */

enum class DoseKind { BOLUS, BASAL }

/** Persisted by name; MIGRATION_28_29 purged every other kind, so valueOf never meets one. */
enum class OutboxKind { NIGHTSCOUT }

/** Marks a NIGHTSCOUT row as a BG slot; here not :sync, since this module writes it. */
const val NS_ENTRY_DEDUP_PREFIX = "ns:entry:"

/** Bridged meal/dose key prefix; here so the repository can WITHDRAW it on deletion. */
const val NS_TREATMENT_DEDUP_PREFIX = "ns:treat:"

enum class OutboxState { PENDING, INFLIGHT, FAILED }

/** authoritative feeds model/stats/wire, implies active; sensorModelId is the panel's FAMILY. */
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
    /** Stable zero-based ordinal per sensor, -1 until minted; persisted, not derived from order. */
    @ColumnInfo(defaultValue = "-1") val ordinal: Int,
)

/** blob is opaque, AndroidKeyStore-wrapped; own table so full-erase keeps it; not archived. */
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

/** Grid-keyed (sourceId, tsMs), tsMs%300_000==0; discards kept in CgmRawSampleEntity. */
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
    /** See [com.t1dm.core.model.CgmReading.measuredAtMs]. Null on rows written before v30. */
    val measuredAtMs: Long? = null,
)

/** Keyed (sourceId, rxWallMs) not a slot; insert IGNORE; not archived. */
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

/** Six nullable series; carbs/bolus/basal are curve events, not here; exercise is non-integral. */
@Entity(tableName = "sample")
@TypeConverters(Converters::class)
data class SampleEntity(
    @PrimaryKey val ts: Long,          // ts % 300_000 == 0
    val tzOffsetMin: Int,
    val bgMgdl: Int?,                  // projected from cgm_reading (authoritative source)
    // Opaque stable id (CgmSourceId.opaque), never the serial. Null pre-v15 or no bg.
    val bgSource: String?,
    val bgProvenance: ReadingProvenance?,
    val bgFlag: ReadingFlag?,
    // Unsnapped instant of the projected reading; the bridge sends it. Null falls back to ts.
    val bgMeasuredAtMs: Long? = null,
    val steps: Int?,                   // from :sensors StepSource
    val mood: Int?,                    // from the Logs panel's mood picker
    val hr: Int?,                      // wired-but-null until a source exists
    val sleep: Int?,
    // Carb-equiv grams disposed this bucket (§3, §5); written only by recordExerciseCurve.
    val exercise: Double?,
    val updatedAt: Long,
)

data class StepBucketRow(val ts: Long, val steps: Int)

/** Staleness key: n catches inserts, maxUpdatedAt merges, per-column counts gap-fills. */
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

/** BOLUS fills k/theta (gamma), BASAL fills ka/kePerHour (Bateman); units is the curve total. */
@Entity(
    tableName = "logged_dose",
    indices = [Index("tsMs"), Index(value = ["clientId"], unique = true)],
)
@TypeConverters(Converters::class)
data class LoggedDoseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Phone-minted UUID at insert: the tombstone, restore-merge and Nightscout dedup key.
    val clientId: String,
    val tsMs: Long,
    val kind: DoseKind,
    val units: Double,
    val durationMin: Double,
    val k: Double?,          // gamma shape (BOLUS)
    val theta: Double?,      // gamma scale (BOLUS)
    val kaPerHour: Double?,  // Bateman absorption (BASAL)
    val kePerHour: Double?,  // Bateman elimination (BASAL)
    // User-drawn curve, per-5-min units f64 BLOB; overrides gamma/Bateman when present.
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray? = null,
    val tzOffsetMin: Int,
    val note: String?,
    val updatedAt: Long,
    // Wall clock at INSERT, never revised; defaultValue needed too, it governs DDL not just Kotlin.
    @ColumnInfo(defaultValue = "0") val loggedAtMs: Long = 0L,
    // Null until first edited.
    val mutatedAtMs: Long? = null,
    // ts+durationMin before first edit; rail takes the LATER of this and current end.
    val mutatedActingUntilMs: Long? = null,
)

/** Carbs feed model as a gamma Ra curve from gi; customCurve, a per-5-min BLOB, overrides it. */
@Entity(
    tableName = "logged_meal",
    indices = [Index("tsMs"), Index(value = ["clientId"], unique = true)],
)
data class LoggedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Phone-minted UUID at insert: see [LoggedDoseEntity.clientId].
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

/** Survives its event: updatedAt stops a restore resurrecting it; kind is raw TEXT. */
@Entity(tableName = "event_tombstone", indices = [Index("tsMs")])
data class EventTombstoneEntity(
    @PrimaryKey val clientId: String,
    val kind: String,
    val tsMs: Long,
    val tzOffsetMin: Int,
    // Phone clock at deletion, forced strictly newer than the row it retires.
    val updatedAt: Long,
    val createdAtMs: Long,
    // For a dose, tsMs+durationMin at deletion: rail keeps blocking a deleted-but-acting dose.
    val actingUntilMs: Long? = null,
)

/** Rows share scheduleId as one daily schedule, one active=1; timeOfDayMin is local midnight. */
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

/** Authoritative content behind FTS5 food_fts; customCurve overrides GI gamma, per-5-min f64. */
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

/** Nutrition snapshotted at save, stable across later food edits; foodId is a link to reopen. */
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

/** Like LoggedDoseEntity: BOLUS fills k/theta, BASAL ka/kePerHour; customCurve overrides it. */
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

/** LE f64 BLOBs: lineBlob is H doubles; fanBlob is nQuantiles*H QUANTILE-MAJOR. */
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
    val madeAtMs: Long,                 // == ModelPrediction.cycleTsMs
    val modelId: String,
    val horizonSteps: Int,
    val nQuantiles: Int,
    val stepMs: Long,
    val anchorTsMs: Long,
    /** NULL is UNKNOWN: refused by the maturation walk, not scored against a later sensor. */
    val sourceId: String?,
    val lastBg: Double,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val lineBlob: ByteArray,   // H f64
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val fanBlob: ByteArray,    // nQ*H f64, q-major
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val todBlob: ByteArray?,   // 12 f64 or null
    val todConf: Double?,
    val status: ForecastStatus,
    val backend: BackendId,
    val selected: Boolean,
    val stale: Boolean,
    val latencyMs: Double?,
    val createdAtMs: Long,
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

/** Display-only; points is (epoch-ms, plot-fraction); immutable, createdAtMs orders the stack. */
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

/** §8.4: deltaBlob is STEP-major tau-minor, not fanBlob's quantile-major; SUFFICIENT fits only. */
@Entity(tableName = "conformal_delta")
data class ConformalDeltaEntity(
    @PrimaryKey val modelId: String,
    val steps: Int,
    val nQuantiles: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val deltaBlob: ByteArray,  // steps*nQ, step-major
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

/** Not a reading, never counted as one (§1); spanStartMs is run's first ts, lo90/hi90 bracket. */
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
    /** Seven mg/dL levels, ascending tau, empty pre-v24; defaultValue matches MIGRATION_23_24. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB, defaultValue = "x''")
    val bandsMgdl: ByteArray = ByteArray(0),
    /** Same fan in risk space, what tau interpolates in (mg/dL crosses the warp); empty pre-v24. */
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

/** blob opaque, lora_serialize owns format; modelId is the only attach target, repo enforces it. */
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
    /** PASS/BLOCKED/INCONCLUSIVE/ABSENT by name; on the row not blob, import/restore = ABSENT. */
    @ColumnInfo(defaultValue = "ABSENT") val guardVerdict: String = "ABSENT",
    @ColumnInfo(defaultValue = "0") val guardWindows: Int = 0,
    /** mg/dL per unit at horizon, frozen and adapted; the ratio alone hides which side moved. */
    @ColumnInfo(defaultValue = "0") val guardFrozenMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardAdaptedMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardRetention: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardSignAgreement: Double = 0.0,
    @ColumnInfo(defaultValue = "") val guardWhy: String = "",
    /** Zero means the fit could not see the dose response at all. */
    @ColumnInfo(defaultValue = "0") val nPaired: Int = 0,
    @ColumnInfo(defaultValue = "0") val distillScale: Double = 0.0,
    /** When the user overrode a refusal, or null; sticks to THIS row, a re-fit starts fresh. */
    val guardOverrideAtMs: Long? = null,
    /** When fitted-on history is edited/deleted, or null; attach refuses till re-fit/override. */
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

/** Phone-local; startMs/endMs wall-clock not grid-snapped; null endMs = open/unclosed bout. */
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

/** No FK onto exercise_session: cascade is an explicit delete, same transaction, PRAGMA unset. */
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

/** Replayed bout's disposal gamma (§5) into sample.exercise; grams/k/theta stored RESOLVED. */
@Entity(
    tableName = "logged_exercise",
    indices = [Index("tsMs"), Index(value = ["clientId"], unique = true)],
)
data class LoggedExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String,
    val tsMs: Long,
    val tzOffsetMin: Int,
    /** Raw TEXT, as `exercise_session.kind` is: a bout kind a later build writes stays readable. */
    val kind: String,
    val durationMin: Double,
    val grams: Double,
    val k: Double,
    val theta: Double,
    val curveDurationMin: Double,
    val sourceSessionId: Long?,
    val updatedAt: Long,
    /** See [LoggedDoseEntity.loggedAtMs], `defaultValue` included. */
    @ColumnInfo(defaultValue = "0") val loggedAtMs: Long = 0L,
    /** See [LoggedDoseEntity.mutatedAtMs]. */
    val mutatedAtMs: Long? = null,
)

const val TOMBSTONE_KIND_MEAL = "meal"

const val TOMBSTONE_KIND_DOSE = "dose"

/** Stops a restore resurrecting a deleted replay. */
const val TOMBSTONE_KIND_EXERCISE = "exercise"

fun EventTombstoneEntity.toModel(): EventTombstone = EventTombstone(
    clientId = clientId,
    kind = when (kind) {
        TOMBSTONE_KIND_DOSE -> CurveKind.INSULIN
        TOMBSTONE_KIND_EXERCISE -> CurveKind.EXERCISE
        else -> CurveKind.CARB
    },
    tsMs = tsMs,
    tzOffsetMin = tzOffsetMin,
    updatedAt = updatedAt,
    actingUntilMs = actingUntilMs,
)

fun LoggedDoseEntity.actingUntilMs(): Long = tsMs + (durationMin * 60_000.0).toLong()

/** True when next moves the insulin channel, not just relabels; a tz edit mustn't invalidate. */
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

/** Stored curve is ABSOLUTE; an edit must rescale it, or IOB reads the pre-edit dose. */
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
    // Only the expected-width fan is handed on; a different length means something else.
    bands = bandsMgdl.toDoubleList().takeIf { it.size == FAN_LEVELS }.orEmpty(),
    tau = tau,
)

/** The quantile levels the head emits (`SPEC/invariants.md` §6). */
private const val FAN_LEVELS = 7
