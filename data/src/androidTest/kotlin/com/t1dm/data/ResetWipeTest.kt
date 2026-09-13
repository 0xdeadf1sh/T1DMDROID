package com.t1dm.data

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.BgInfillEntity
import com.t1dm.data.db.CgmAdvertRawEntity
import com.t1dm.data.db.DoseEventEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.EventTombstoneEntity
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.HwTelemetryEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedExerciseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.LoraEntity
import com.t1dm.data.db.PredictionEntity
import com.t1dm.data.db.TOMBSTONE_KIND_DOSE
import com.t1dm.data.db.SavedMealEntity
import com.t1dm.data.db.SavedMealItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory builder omits food_fts callback, so custom-food DELETE is a plain row delete. */
@RunWith(AndroidJUnit4::class)
class ResetWipeTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val sourceId = CgmSourceId("aidexx:RESET")
    private val descriptor = CgmSourceDescriptor(
        id = sourceId,
        vendorId = "aidexx",
        sensorModelId = CgmSensorModelId.AIDEX_X, advertName = null,
        displayName = "AiDEX X RESET",
        serialSuffix = "RESET",
        warmupWindowMin = 60,
        passiveOnly = true,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun count(table: String): Long =
        db.useReaderConnection { c ->
            c.usePrepared("SELECT COUNT(*) FROM $table") { it.step(); it.getLong(0) }
        }

    private suspend fun userVersion(): Long =
        db.useReaderConnection { c ->
            c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) }
        }

    private suspend fun seedEveryTable() {
        val now = 1_700_000_000_000L
        // Also projects a sample and enqueues an INGEST outbox row.
        repo.upsertSource(descriptor, authoritative = true, nowMs = now)
        repo.upsertReading(
            CgmReading(
                sourceId = sourceId, tsMs = 300_000L, bgMgdl = 120, trendTenthsPerMin = 0,
                minFromStart = 60, quality = 100, provenance = ReadingProvenance.MEASURED,
                flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = 300_000L, rssi = -60,
            ),
        )
        repo.logDose(DoseEventEntity(tsMs = 300_000L, kind = DoseKind.BOLUS, units = 2.0, tzOffsetMin = 0, note = null, updatedAt = now))
        repo.logLoggedDose(
            LoggedDoseEntity(
                clientId = "", tsMs = 300_000L, kind = DoseKind.BOLUS, units = 3.0, durationMin = 300.0,
                k = 2.0, theta = 30.0, kaPerHour = null, kePerHour = null,
                tzOffsetMin = 0, note = "t", updatedAt = now,
            ),
        )
        repo.logMeal(
            LoggedMealEntity(
                clientId = "", tsMs = 300_000L, grams = 40.0, gi = 70.0, k = 2.0, theta = 20.0,
                durationMin = 180.0, customCurve = null, tzOffsetMin = 0, note = null, updatedAt = now,
            ),
        )
        repo.saveBasalSchedule(
            "sched-1",
            listOf(
                BasalScheduleEntity(
                    scheduleId = "sched-1", label = "night", timeOfDayMin = 0, doseU = 1.0,
                    durationMin = 1440.0, kaPerHour = 0.3, kePerHour = 0.07, tzOffsetMin = 0,
                    active = true, updatedAt = now,
                ),
            ),
            makeActive = true,
        )
        repo.recordRawAdvert(CgmAdvertRawEntity(sourceId = sourceId.value, rxWallMs = now, rssi = -60, payload = ByteArray(4), crcValid = true, minFromStart = 1))
        repo.recordTelemetry(HwTelemetryEntity(tsMs = now, metric = "exec_ms", modelId = "m", valueReal = 9.0, valueText = null))
        repo.saveMeal("saved", listOf(SavedMealItemEntity(mealId = 0, foodId = null, name = "rice", grams = 100.0, carbsPer100g = 28.0, gi = 70.0, customCurve = null)), now)

        db.predictionDao().upsert(
            PredictionEntity(
                madeAtMs = 300_000L, modelId = "m", horizonSteps = 1, nQuantiles = 1, stepMs = 300_000L,
                anchorTsMs = 300_000L, sourceId = "src", lastBg = 120.0, lineBlob = ByteArray(8), fanBlob = ByteArray(8),
                todBlob = null, todConf = null, status = ForecastStatus.OK, backend = BackendId.EXECUTORCH_XNNPACK_FP32,
                selected = true, stale = false, latencyMs = 9.0, createdAtMs = 300_000L,
            ),
        )

        db.foodDao().insert(FoodEntity(name = "SeedApple", brand = null, carbsPer100g = 14.0, gi = 40.0, category = "fruit", source = "USDA", custom = false, customCurve = null, updatedAt = now))
        db.foodDao().insert(FoodEntity(name = "MyFood", brand = null, carbsPer100g = 20.0, gi = 55.0, category = "custom", source = "user", custom = true, customCurve = null, updatedAt = now))
        db.insulinTypeDao().insert(InsulinTypeEntity(name = "Novorapid", kind = DoseKind.BOLUS, durationMin = 300.0, k = 2.0, theta = 30.0, kaPerHour = null, kePerHour = null, customCurve = null, builtin = true, updatedAt = now))
        db.insulinTypeDao().insert(InsulinTypeEntity(name = "MyInsulin", kind = DoseKind.BOLUS, durationMin = 300.0, k = 2.0, theta = 30.0, kaPerHour = null, kePerHour = null, customCurve = null, builtin = false, updatedAt = now))

        repo.addPaintStroke(
            PaintStroke(
                id = 0, createdAtMs = now, tool = "brush", colorArgb = -1, widthDp = 3f,
                tsMs = longArrayOf(now, now + 1_000), yFrac = floatArrayOf(0.25f, 0.75f),
            ),
        )

        repo.putBandCalibration(
            BandCalibration(
                modelId = "m", delta = List(7) { 0.5 }, steps = 1, nQuantiles = 7, nCal = 30, nEval = 12,
                maxAbsDeltaMgdl = 0.5, cov90Raw = 0.8, cov90Cal = 0.9, meanWidth90Raw = 40.0,
                meanWidth90Cal = 55.0, windowDays = 14, fittedAtMs = now,
            ),
        )

        val bout = repo.startExerciseSession(
            ExerciseSessionEntity(
                clientId = "", startMs = now, endMs = null, tzOffsetMin = 0, kind = "WALK",
                activeSec = 0, distanceM = null, kcal = null, interrupted = false, note = null,
                updatedAt = now,
            ),
        )
        repo.appendExerciseFixes(
            listOf(
                ExerciseFixEntity(
                    sessionId = bout.id, tsMs = now + 4_000L, lat = 41.015137, lon = 28.979530,
                    accuracyM = 6.5f, speedMps = 1.4f,
                ),
            ),
        )

        repo.logLoggedExercise(
            LoggedExerciseEntity(
                clientId = "", tsMs = now, tzOffsetMin = 0, kind = "WALK", durationMin = 45.0,
                grams = 22.5, k = 3.0, theta = 15.0, curveDurationMin = 135.0,
                sourceSessionId = null, updatedAt = now,
            ),
            emptyList(),
            now,
        )

        db.eventTombstoneDao().upsert(
            EventTombstoneEntity(
                clientId = "gone-1", kind = TOMBSTONE_KIND_DOSE, tsMs = now, tzOffsetMin = 0,
                updatedAt = now, createdAtMs = now, actingUntilMs = now + 300_000L,
            ),
        )
        db.loraDao().upsert(
            LoraEntity(
                modelId = "m", name = "fit 1", blob = ByteArray(64), rank = 4, alpha = 8.0,
                targets = 0b1100, nParams = 16, nTrain = 10, nHoldout = 4, epochs = 5,
                holdoutBefore = 1.0, holdoutAfter = 0.9, improved = true, attached = false,
                createdAtMs = now, updatedAtMs = now,
            ),
        )
        db.bgInfillDao().upsert(
            listOf(
                BgInfillEntity(
                    ts = now, mgdl = 120.0, lo90 = 110.0, hi90 = 130.0, modelId = "m",
                    createdAtMs = now, spanStartMs = now,
                ),
            ),
        )

        repo.putKv("ui.theme", "umbrella", now)
        repo.putKv("watch.paired", "1", now)
        repo.putKv("watch.keymaterial", "iv:ct", now)
        repo.putKv("watch.nonce.ceiling.5", "42", now)
    }

    @Test
    fun wipeEmptiesEveryTableKeepsSeedsAndSchema() = runTest {
        seedEveryTable()

        assertEquals(2L, count("food"))
        assertEquals(2L, count("insulin_type"))
        assert(count("kv") >= 4L)
        assertEquals(1L, count("prediction"))
        assertEquals(1L, count("exercise_session"))
        assertEquals(1L, count("exercise_fix"))
        assertEquals(1L, count("logged_exercise"))
        assertEquals(1L, count("event_tombstone"))
        assertEquals(1L, count("lora"))
        assertEquals(1L, count("bg_infill"))

        repo.wipeAllData()

        for (t in WIPED_EMPTY) assertEquals("$t must be empty after reset", 0L, count(t))

        assertEquals("seed food kept", 1L, count("food"))
        assertEquals("custom food wiped", 0L, count("food WHERE custom = 1"))
        assertEquals("builtin insulin kept", 1L, db.insulinTypeDao().builtinCount().toLong())
        assertEquals("custom insulin wiped", 0L, count("insulin_type WHERE builtin = 0"))

        assertEquals("kv fully cleared (nonce ceilings + pairing + settings)", 0L, count("kv"))

        // Row-wipe, not a drop/recreate.
        assertEquals(AppDatabase.SCHEMA_VERSION.toLong(), userVersion())
    }

    private companion object {
        /** food + insulin_type are absent: they keep their seed rows. */
        val WIPED_EMPTY = listOf(
            "cgm_source", "cgm_reading", "cgm_sample_raw", "sample", "dose_event", "logged_dose",
            "logged_meal", "basal_schedule", "cgm_advert_raw", "outbox", "prediction",
            "hw_telemetry", "saved_meal", "saved_meal_item", "bg_paint_stroke", "conformal_delta",
            "exercise_session", "exercise_fix", "logged_exercise", "kv",
            "event_tombstone", "lora", "bg_infill",
        )
    }
}
