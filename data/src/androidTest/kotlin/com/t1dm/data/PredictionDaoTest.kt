package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room verification of the dedicated `prediction` table (the fan-BLOB round-trip and the
 * `(madeAtMs, modelId)` REPLACE) plus the post-redesign catch-up reconciliation:
 *  - `mergeServerSample` now presence-gap-fills the wide `sample` via [SampleGapFill] — it fills
 *    ONLY scalars the local row lacks and NEVER overwrites a present local value, with NO `updated_at`
 *    discriminator, so a server echo can never win over local truth (decisions #2/#3). The retired LWW
 *    fold-of-carbs is gone — carbs/bolus/basal are no longer sample scalars (§3.1).
 *  - meal/dose history re-hydrates by phone `clientId` via [T1dmRepository.hydrateMealEvent] /
 *    [T1dmRepository.hydrateDoseEvent] (`@Insert(onConflict = IGNORE)` on the unique `clientId`), so a
 *    redelivery is a no-op with no duplicate row (§3.4). Instrumented so the real SQLite exercises both.
 */
@RunWith(AndroidJUnit4::class)
class PredictionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository
    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private fun pred(
        modelId: String,
        cycleTs: Long = 300_000,
        h: Int = 3,
        nq: Int = 7,
        selected: Boolean = false,
    ) = ModelPrediction(
        modelId = modelId,
        cycleTsMs = cycleTs,
        anchorTsMs = cycleTs,
        stepMs = 300_000,
        medianBg = List(h) { s -> 103.0 + s * 10 },       // q=3 row
        bandsMgdl = List(h * nq) { i -> val s = i / nq; val q = i % nq; 100.0 + s * 10 + q },
        nQuantiles = nq,
        lastBg = 100.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        precision = Precision.FP32,
        selected = selected,
        stale = false,
        latencyMs = 12.5,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After fun tearDown() = db.close()

    @Test
    fun upsertRoundTripsFanAndFields() = kotlinx.coroutines.test.runTest {
        val original = pred("m1", selected = true)
        repo.upsertPredictions(listOf(original), nowMs = 1_000)

        val loaded = repo.latestCyclePredictions()
        assertEquals(1, loaded.size)
        val p = loaded.single()
        assertEquals("m1", p.modelId)
        assertEquals(original.medianBg, p.medianBg)
        assertEquals(original.bandsMgdl, p.bandsMgdl)       // fan BLOB round-trips exactly
        assertEquals(ForecastStatus.OK, p.status)
        assertEquals(BackendId.EXECUTORCH_XNNPACK_FP32, p.backend)
        assertEquals(Precision.FP32, p.precision)
        assertTrue(p.selected)
        assertEquals(100.0, p.lastBg, 0.0)
    }

    @Test
    fun sameCycleModelReplacesInPlace() = kotlinx.coroutines.test.runTest {
        repo.upsertPredictions(listOf(pred("m1")), nowMs = 1_000)
        repo.upsertPredictions(listOf(pred("m1")), nowMs = 2_000)   // re-run same cycle+model
        assertEquals(1, repo.latestCyclePredictions().size)         // one row, not two
    }

    @Test
    fun latestCycleReturnsAllModelsSelectedFirst() = kotlinx.coroutines.test.runTest {
        repo.upsertPredictions(
            listOf(pred("m1", selected = false), pred("m2", selected = true), pred("m3", selected = false)),
            nowMs = 1_000,
        )
        val loaded = repo.latestCyclePredictions()
        assertEquals(3, loaded.size)
        assertEquals("m2", loaded.first().modelId)      // selected model first
    }

    @Test
    fun latestCycleIgnoresOlderCycles() = kotlinx.coroutines.test.runTest {
        repo.upsertPredictions(listOf(pred("m1", cycleTs = 300_000)), nowMs = 1_000)
        repo.upsertPredictions(listOf(pred("m1", cycleTs = 600_000)), nowMs = 2_000)
        val loaded = repo.latestCyclePredictions()
        assertEquals(1, loaded.size)
        assertEquals(600_000, loaded.single().cycleTsMs)
    }

    @Test
    fun mergeServerSampleGapFillsMissingScalarsNeverOverwritingLocal() = kotlinx.coroutines.test.runTest {
        // Seed a local MEASURED bg (projected into the wide sample via the reading path).
        repo.upsertSource(
            com.t1dm.core.model.CgmSourceDescriptor(
                id = com.t1dm.core.model.CgmSourceId("aidexx:X"), vendorId = "aidexx",
                displayName = "X", serialSuffix = "X", warmupWindowMin = 60, passiveOnly = true,
            ),
            active = true, nowMs = 1_000,
        )
        val ts = 300_000L
        repo.upsertReading(
            CgmReadingWith(ts, bg = 120, prov = ReadingProvenance.MEASURED, rx = 5_000),
        )

        // A server patch that (a) tries to OVERWRITE the present bg and (b) fills a MISSING scalar (hr).
        val wrote = repo.mergeServerSample(
            SamplePatch(ts = ts, tzOffsetMin = 0, updatedAt = 9_000, bgMgdl = 999, hr = 60),
        )
        assertTrue(wrote)
        val s = repo.sampleAt(ts)!!
        assertEquals(120, s.bgMgdl)                          // present bg preserved (never clobbered)
        assertEquals(ReadingProvenance.MEASURED, s.bgProvenance)  // provenance intact (safety rail #5)
        assertEquals(60, s.hr)                               // the gap (hr) is filled from the server
        assertEquals(5_000L, s.updatedAt)                    // local updated_at kept, NOT re-stamped

        // A NEWER server updated_at still cannot win over a present local value — no clock discriminator
        // (the crux of replacing LWW: presence gap-fill only, decisions #2/#3).
        val wrote2 = repo.mergeServerSample(
            SamplePatch(ts = ts, tzOffsetMin = 0, updatedAt = 50_000, bgMgdl = 888, hr = 77),
        )
        assertFalse(wrote2)                                  // nothing missing ⇒ no write
        val s2 = repo.sampleAt(ts)!!
        assertEquals(120, s2.bgMgdl)
        assertEquals(60, s2.hr)
        assertEquals(5_000L, s2.updatedAt)
    }

    @Test
    fun hydrateDoseEventIgnoresRedeliveryByClientId() = kotlinx.coroutines.test.runTest {
        val ev = doseEvent(clientId = "dose-A", tsMs = 300_000L, units = 5.0)
        assertTrue(repo.hydrateDoseEvent(ev) > 0)                          // first insert
        assertEquals(-1L, repo.hydrateDoseEvent(ev.copy(units = 999.0)))   // same clientId ⇒ IGNOREd

        val rows = repo.loggedDosesInRange(0L, 10_000_000L)
        assertEquals(1, rows.size)                                         // no duplicate row
        assertEquals(5.0, rows.single().units, 0.0)                        // original retained, not overwritten

        // A distinct clientId still hydrates.
        assertTrue(repo.hydrateDoseEvent(doseEvent(clientId = "dose-B", tsMs = 600_000L, units = 3.0)) > 0)
        assertEquals(2, repo.loggedDosesInRange(0L, 10_000_000L).size)
    }

    @Test
    fun hydrateMealEventIgnoresRedeliveryByClientId() = kotlinx.coroutines.test.runTest {
        val ev = mealEvent(clientId = "meal-A", tsMs = 300_000L, grams = 40.0)
        assertTrue(repo.hydrateMealEvent(ev) > 0)
        assertEquals(-1L, repo.hydrateMealEvent(ev.copy(grams = 999.0)))

        val rows = repo.loggedMealsInRange(0L, 10_000_000L)
        assertEquals(1, rows.size)
        assertEquals(40.0, rows.single().grams, 0.0)

        assertTrue(repo.hydrateMealEvent(mealEvent(clientId = "meal-B", tsMs = 600_000L, grams = 25.0)) > 0)
        assertEquals(2, repo.loggedMealsInRange(0L, 10_000_000L).size)
    }

    private fun doseEvent(clientId: String, tsMs: Long, units: Double) = LoggedDoseEntity(
        clientId = clientId, tsMs = tsMs, kind = DoseKind.BOLUS, units = units, durationMin = 360.0,
        k = 2.0, theta = 40.0, kaPerHour = null, kePerHour = null,
        tzOffsetMin = 0, note = null, updatedAt = 1_000,
    )

    private fun mealEvent(clientId: String, tsMs: Long, grams: Double) = LoggedMealEntity(
        clientId = clientId, tsMs = tsMs, grams = grams, gi = 50.0, k = null, theta = null,
        durationMin = 240.0, customCurve = null, tzOffsetMin = 0, note = null, updatedAt = 1_000,
    )

    private fun CgmReadingWith(ts: Long, bg: Int, prov: ReadingProvenance, rx: Long) =
        com.t1dm.core.model.CgmReading(
            sourceId = com.t1dm.core.model.CgmSourceId("aidexx:X"), tsMs = ts, bgMgdl = bg,
            trendTenthsPerMin = 0, minFromStart = 120, quality = 100, provenance = prov,
            flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = rx, rssi = -70,
        )
}
