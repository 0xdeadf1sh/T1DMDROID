package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
        assertEquals(original.bandsMgdl, p.bandsMgdl)
        assertEquals(ForecastStatus.OK, p.status)
        assertEquals(BackendId.EXECUTORCH_XNNPACK_FP32, p.backend)
        assertTrue(p.selected)
        assertEquals(100.0, p.lastBg, 0.0)
    }

    @Test
    fun sameCycleModelReplacesInPlace() = kotlinx.coroutines.test.runTest {
        repo.upsertPredictions(listOf(pred("m1")), nowMs = 1_000)
        repo.upsertPredictions(listOf(pred("m1")), nowMs = 2_000)
        assertEquals(1, repo.latestCyclePredictions().size)
    }

    @Test
    fun latestCycleReturnsAllModelsSelectedFirst() = kotlinx.coroutines.test.runTest {
        repo.upsertPredictions(
            listOf(pred("m1", selected = false), pred("m2", selected = true), pred("m3", selected = false)),
            nowMs = 1_000,
        )
        val loaded = repo.latestCyclePredictions()
        assertEquals(3, loaded.size)
        assertEquals("m2", loaded.first().modelId)
    }

    @Test
    fun latestCycleIgnoresOlderCycles() = kotlinx.coroutines.test.runTest {
        repo.upsertPredictions(listOf(pred("m1", cycleTs = 300_000)), nowMs = 1_000)
        repo.upsertPredictions(listOf(pred("m1", cycleTs = 600_000)), nowMs = 2_000)
        val loaded = repo.latestCyclePredictions()
        assertEquals(1, loaded.size)
        assertEquals(600_000, loaded.single().cycleTsMs)
    }
}
