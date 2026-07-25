package com.t1dm.data

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.PaintStroke
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmAdvertRawEntity
import com.t1dm.data.db.DoseEventEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.HwTelemetryEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.NoteEntity
import com.t1dm.data.db.PredictionEntity
import com.t1dm.data.db.SavedMealEntity
import com.t1dm.data.db.SavedMealItemEntity
import com.t1dm.data.db.ServerProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the issue-5 full app reset ([T1dmRepository.wipeAllData]): every user + runtime table is
 * emptied, the shipped reference data (seed food dictionary + builtin insulin presets) is KEPT, the
 * watch nonce-ceiling kv rows are cleared (so a later re-pair cannot reuse a (key, nonce) pair), and
 * the store is left at the CURRENT schema version (a row-wipe, never a destructive drop/recreate).
 *
 * Instrumented (real device SQLite), sensor-free. The in-memory builder omits the `food_fts` callback
 * (the shadow virtual table is not exercised here) — the custom-food DELETE is a plain row delete.
 */
@RunWith(AndroidJUnit4::class)
class ResetWipeTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val sourceId = CgmSourceId("aidexx:RESET")
    private val descriptor = CgmSourceDescriptor(
        id = sourceId,
        vendorId = "aidexx",
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

    /** Write at least one row into every table the wipe must clear (plus the seed rows it must keep). */
    private suspend fun seedEveryTable() {
        val now = 1_700_000_000_000L
        // source → reading (projects a sample + enqueues an INGEST outbox row).
        repo.upsertSource(descriptor, active = true, nowMs = now)
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
        repo.logNote(NoteEntity(tsMs = now, tzOffsetMin = 0, text = "note", updatedAt = now))
        repo.recordRawAdvert(CgmAdvertRawEntity(sourceId = sourceId.value, rxWallMs = now, rssi = -60, payload = ByteArray(4), crcValid = true, minFromStart = 1))
        repo.recordTelemetry(HwTelemetryEntity(tsMs = now, metric = "exec_ms", modelId = "m", valueReal = 9.0, valueText = null))
        repo.upsertProfile(
            ServerProfileEntity(id = "default", label = "srv", baseUrl = "http://x", active = true, createdAtMs = now, updatedAtMs = now),
            makeActive = true,
        )
        repo.saveMeal("saved", listOf(SavedMealItemEntity(mealId = 0, foodId = null, name = "rice", grams = 100.0, carbsPer100g = 28.0, gi = 70.0, customCurve = null)), now)

        // A prediction row (dedicated table).
        db.predictionDao().upsert(
            PredictionEntity(
                madeAtMs = 300_000L, modelId = "m", horizonSteps = 1, nQuantiles = 1, stepMs = 300_000L,
                anchorTsMs = 300_000L, lastBg = 120.0, lineBlob = ByteArray(8), fanBlob = ByteArray(8),
                todBlob = null, todConf = null, status = ForecastStatus.OK, backend = BackendId.EXECUTORCH_XNNPACK_FP32,
                precision = Precision.FP32, selected = true, stale = false, latencyMs = 9.0, createdAtMs = 300_000L,
            ),
        )

        // Reference data (kept) + user-added rows (wiped).
        db.foodDao().insert(FoodEntity(name = "SeedApple", brand = null, carbsPer100g = 14.0, gi = 40.0, category = "fruit", source = "USDA", custom = false, customCurve = null, updatedAt = now))
        db.foodDao().insert(FoodEntity(name = "MyFood", brand = null, carbsPer100g = 20.0, gi = 55.0, category = "custom", source = "user", custom = true, customCurve = null, updatedAt = now))
        db.insulinTypeDao().insert(InsulinTypeEntity(name = "Novorapid", kind = DoseKind.BOLUS, durationMin = 300.0, k = 2.0, theta = 30.0, kaPerHour = null, kePerHour = null, customCurve = null, builtin = true, updatedAt = now))
        db.insulinTypeDao().insert(InsulinTypeEntity(name = "MyInsulin", kind = DoseKind.BOLUS, durationMin = 300.0, k = 2.0, theta = 30.0, kaPerHour = null, kePerHour = null, customCurve = null, builtin = false, updatedAt = now))

        // A freehand graph annotation (Room v8) — display-only user data, wiped whole (no seed rows).
        repo.addPaintStroke(
            PaintStroke(
                id = 0, createdAtMs = now, tool = "brush", colorArgb = -1, widthDp = 3f,
                tsMs = longArrayOf(now, now + 1_000), yFrac = floatArrayOf(0.25f, 0.75f),
            ),
        )

        // kv: a setting, and the watch pairing + nonce-ceiling rows the wipe must burn.
        repo.putKv("ui.theme", "umbrella", now)
        repo.putKv("watch.paired", "1", now)
        repo.putKv("watch.keymaterial", "iv:ct", now)
        repo.putKv("watch.nonce.ceiling.5", "42", now)
    }

    @Test
    fun wipeEmptiesEveryTableKeepsSeedsAndSchema() = runTest {
        seedEveryTable()

        // Sanity: the tables are non-empty before the wipe.
        assertEquals(2L, count("food"))
        assertEquals(2L, count("insulin_type"))
        assert(count("kv") >= 4L)
        assertEquals(1L, count("prediction"))

        repo.wipeAllData()

        for (t in WIPED_EMPTY) assertEquals("$t must be empty after reset", 0L, count(t))

        // Reference data survives; user-added rows are gone.
        assertEquals("seed food kept", 1L, count("food"))
        assertEquals("custom food wiped", 0L, count("food WHERE custom = 1"))
        assertEquals("builtin insulin kept", 1L, db.insulinTypeDao().builtinCount().toLong())
        assertEquals("custom insulin wiped", 0L, count("insulin_type WHERE builtin = 0"))

        // Nonce ceilings + pairing + settings all gone (kv fully cleared).
        assertEquals("kv fully cleared (nonce ceilings + pairing + settings)", 0L, count("kv"))

        // Row-wipe, not a drop/recreate: still at the CURRENT schema version.
        assertEquals(AppDatabase.SCHEMA_VERSION.toLong(), userVersion())
    }

    private companion object {
        /** Every table the reset must leave empty (food + insulin_type keep their seed rows). */
        val WIPED_EMPTY = listOf(
            "cgm_source", "cgm_reading", "sample", "dose_event", "logged_dose", "logged_meal",
            "basal_schedule", "cgm_advert_raw", "outbox", "prediction", "server_profile", "note",
            "hw_telemetry", "saved_meal", "saved_meal_item", "bg_paint_stroke", "kv",
        )
    }
}
