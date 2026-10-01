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

enum class DoseKind { BOLUS, BASAL }

/** Persisted by name; MIGRATION_28_29 purged every other kind, so valueOf never meets one. */
enum class OutboxKind { NIGHTSCOUT }

/** NIGHTSCOUT row = BG slot, not treatment. Here not :sync, so one spelling can't drift. */
const val NS_ENTRY_DEDUP_PREFIX = "ns:entry:"

/** Bridged meal/dose prefix; here so repository's withdraw-on-delete matches one spelling. */
const val NS_TREATMENT_DEDUP_PREFIX = "ns:treat:"

enum class OutboxState { PENDING, INFLIGHT, FAILED }

/** authoritative: 1 row, implies active. sensorModelId=family. hidden: clears active. */
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
    /** Per-sensor number, -1 until minted. Not addedAtMs-derived: restore would renumber. */
    @ColumnInfo(defaultValue = "-1") val ordinal: Int,
)

/** blob: opaque, Keystore-wrapped. Own table so full-erase keeps it; not archived (per-install). */
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

/** Grid-keyed (sourceId, tsMs) % 300_000==0. Discarded slot-losers land in CgmRawSampleEntity. */
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

/** Keyed (sourceId, rxWallMs), not slot: every sample kept. Insert IGNORE dedupes. Not archived. */
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

/** Six nullable series; carbs/bolus/basal are curve events elsewhere. exercise is non-integral. */
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
    // Carb-equiv grams, fractional (SPEC/invariants.md §3,§5); recordExerciseCurve writes it.
    val exercise: Double?,
    val updatedAt: Long,
)

data class StepBucketRow(val ts: Long, val steps: Int)

/** Staleness key: n=insert, maxUpdatedAt=merge, counts=gap-fill, nNotMeasured=provenance swap. */
data class SampleWindowFingerprint(
    val n: Int,
    val maxUpdatedAt: Long,
    val nBg: Int,
    val nSteps: Int,
    val nMood: Int,
    /** BG rows toStatSample drops; nBg minus this is what the stats count. */
    val nNotMeasured: Int,
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

/** BOLUS: k/theta (gamma). BASAL: kaPerHour/kePerHour (Bateman). durationMin=DIA. */
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
    // User-drawn curve: per-5-min absolute units f64 BLOB; overrides analytic gamma/Bateman.
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val customCurve: ByteArray? = null,
    val tzOffsetMin: Int,
    val note: String?,
    val updatedAt: Long,
    // Wall clock at INSERT, never revised (stale-log rail). defaultValue needed for DDL too.
    @ColumnInfo(defaultValue = "0") val loggedAtMs: Long = 0L,
    // Null until first edited.
    val mutatedAtMs: Long? = null,
    // ts+durationMin pre-edit, null till then. Rail takes the later of this and current end.
    val mutatedActingUntilMs: Long? = null,
)

/** Carbs -> gamma Ra curve via gi; k/theta/durationMin stored resolved. customCurve overrides. */
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

/** Survives its event: updatedAt stops a restore resurrecting it. */
@Entity(tableName = "event_tombstone", indices = [Index("tsMs")])
data class EventTombstoneEntity(
    @PrimaryKey val clientId: String,
    val kind: String,
    val tsMs: Long,
    val tzOffsetMin: Int,
    // Phone clock at deletion, forced strictly newer than the row it retires.
    val updatedAt: Long,
    val createdAtMs: Long,
    // Dose: tsMs+durationMin at deletion; rail keeps blocking a deleted dose that could still act.
    val actingUntilMs: Long? = null,
)

/** scheduleId groups a schedule; exactly one active=1. timeOfDayMin: minutes from tz midnight. */
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

/** Backs FTS5 food_fts, trigger-synced. customCurve: f64, sums ~1.0, overrides GI gamma. */
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

/** Nutrition snapshotted at save; stable if food later edited/deleted. foodId is a soft link. */
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

/** Like LoggedDoseEntity: BOLUS k/theta, BASAL kaPerHour/kePerHour; customCurve overrides. */
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

/** LE f64 BLOBs. lineBlob=H doubles. fanBlob=nQuantiles*H, quantile-major. Unique key REPLACEs. */
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
    /** NULL=UNKNOWN; maturation walk refuses the row rather than scoring a later sensor. */
    val sourceId: String?,
    val lastBg: Double,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val lineBlob: ByteArray,   // H f64
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val fanBlob: ByteArray,    // nQuantiles*H, q-major
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

/** Display-only. points: (epoch-ms, height-frac) polyline; createdAtMs orders the stacking. */
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

/** SPEC/inference.md §8.4. deltaBlob step-major,tau-minor (i=s*nQuantiles+q), unlike fanBlob. */
@Entity(tableName = "conformal_delta")
data class ConformalDeltaEntity(
    @PrimaryKey val modelId: String,
    val steps: Int,
    val nQuantiles: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val deltaBlob: ByteArray,  // steps*nQuantiles f64
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

/** Not a reading (SPEC/invariants.md §1). spanStartMs=run's ts0; promotedAtMs null unpromoted. */
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
    // defaultValue must match MIGRATION_23_24's DDL; Kotlin default alone won't cover it.

    /** Seven mg/dL levels per slot, ascending τ. Empty on a pre-v24 row. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB, defaultValue = "x''")
    val bandsMgdl: ByteArray = ByteArray(0),
    /** Same fan in risk space, where tau interpolates (mg/dL crosses the warp). Empty pre-v24. */
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

/** blob: t1dm-core::lora_serialize. modelId: only model it attaches to; attached repo-enforced. */
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
    // Guard provenance on row: import/restore gives no verdict, refused. See MIGRATION_22_23.

    /** `PASS` / `BLOCKED` / `INCONCLUSIVE` / `ABSENT`, by name. Absent means never probed. */
    @ColumnInfo(defaultValue = "ABSENT") val guardVerdict: String = "ABSENT",
    @ColumnInfo(defaultValue = "0") val guardWindows: Int = 0,
    /** mg/dL per unit at horizon, frozen vs adapted; ratio alone hides which side moved. */
    @ColumnInfo(defaultValue = "0") val guardFrozenMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardAdaptedMgdl: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardRetention: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val guardSignAgreement: Double = 0.0,
    @ColumnInfo(defaultValue = "") val guardWhy: String = "",
    /** Zero means the fit could not see the dose response at all. */
    @ColumnInfo(defaultValue = "0") val nPaired: Int = 0,
    @ColumnInfo(defaultValue = "0") val distillScale: Double = 0.0,
    /** Override timestamp, or null. Sticks to this row; a re-fit starts unoverridden. */
    val guardOverrideAtMs: Long? = null,
    /** Last edit/delete of the fit history, or null; attach refuses until re-fit or overridden. */
    val historyMutatedAtMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val fittedAtMs: Long = 0,
    /** `LoraObjective` by name; null when not recorded (an import). */
    val objective: String? = null,
    /** Held-out objective metric, frozen head then adapter; null when not recorded. */
    val metricBefore: Double? = null,
    val metricAfter: Double? = null,
    /** `MaskGeometry` by name: the one run shape this adapter applies to. */
    @ColumnInfo(defaultValue = "FORECAST") val kind: String = "FORECAST",
) {
    override fun equals(other: Any?): Boolean =
        other is LoraEntity && id == other.id && modelId == other.modelId && name == other.name && kind == other.kind &&
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
            historyMutatedAtMs == other.historyMutatedAtMs && fittedAtMs == other.fittedAtMs &&
            objective == other.objective && metricBefore == other.metricBefore &&
            metricAfter == other.metricAfter

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + modelId.hashCode()) + blob.contentHashCode()
}

/** Phone-local, startMs/endMs unsnapped. null endMs=open/unseen-stop; unknown kind->OTHER. */
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

/** No FK on exercise_session: delete is explicit in deleteExerciseSession. accuracyM=horizontal. */
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

/** SPEC/invariants.md §5, phone-local. curveDurationMin=durationMin+90; grams/k/theta resolved. */
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

/** A cut BG slot (SPEC/invariants.md §1); clientId is [bgTombstoneId]. */
const val TOMBSTONE_KIND_BG = "bg"

fun bgTombstoneId(ts: Long): String = "bg:$ts"

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

/** True if next moves the channel, not a relabel; note/tz edits mustn't invalidate predictions. */
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

/** Curve is absolute; edit rescales it or IOB reads pre-edit dose. Shape change drops curve. */
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
    // Only the expected-width fan is handed on; other lengths would draw a shape nothing emitted.
    bands = bandsMgdl.toDoubleList().takeIf { it.size == FAN_LEVELS }.orEmpty(),
    tau = tau,
)

/** The quantile levels the head emits (`SPEC/invariants.md` §6). */
internal const val FAN_LEVELS = 7
