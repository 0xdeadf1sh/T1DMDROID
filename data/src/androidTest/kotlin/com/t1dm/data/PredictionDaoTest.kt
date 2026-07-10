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
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room verification of the Phase-3 dedicated `prediction` table + the wide-sample LWW
 * catch-up merge (Phase 3). Instrumented so the real SQLite exercises the fan-BLOB
 * round-trip and the `(madeAtMs, modelId)` REPLACE.
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
    fun mergeServerSampleAppliesLwwIntoWideTable() = kotlinx.coroutines.test.runTest {
        // Seed a local MEASURED bg via the reading projection, then a newer server carbs row folds in.
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

        val wrote = repo.mergeServerSample(
            SamplePatch(ts = ts, tzOffsetMin = 0, updatedAt = 9_000, carbsG = 30.0),
        )
        assertTrue(wrote)
        val s = repo.sampleAt(ts)!!
        assertEquals(120, s.bgMgdl)          // local bg preserved (gap preservation)
        assertEquals(30.0, s.carbsG!!, 0.0)  // newer server carbs merged in

        // An older server write is rejected.
        val wrote2 = repo.mergeServerSample(
            SamplePatch(ts = ts, tzOffsetMin = 0, updatedAt = 1_000, carbsG = 999.0),
        )
        assertFalse(wrote2)
        assertEquals(30.0, repo.sampleAt(ts)!!.carbsG!!, 0.0)
    }

    private fun CgmReadingWith(ts: Long, bg: Int, prov: ReadingProvenance, rx: Long) =
        com.t1dm.core.model.CgmReading(
            sourceId = com.t1dm.core.model.CgmSourceId("aidexx:X"), tsMs = ts, bgMgdl = bg,
            trendTenthsPerMin = 0, minFromStart = 120, quality = 100, provenance = prov,
            flag = ReadingFlag.NORMAL, tzOffsetMin = 0, rxWallMs = rx, rssi = -70,
        )
}
