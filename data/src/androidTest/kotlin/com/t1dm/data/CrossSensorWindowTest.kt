package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A window pairs a forecast only with SAME-sensor truth; cross-sensor gaps hit §8.4 as error. */
@RunWith(AndroidJUnit4::class)
class CrossSensorWindowTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val worn = CgmSourceId("vendora:WORN")     // conditioned the forecasts
    private val truthSrc = CgmSourceId("vendorb:NEW") // authoritative, supplies the truth

    private val step = 300_000L
    private val t0 = 1_800_000_000_000L
    private val horizonSteps = 24
    private val nq = 7

    private fun descriptor(id: CgmSourceId) = CgmSourceDescriptor(
        id = id,
        vendorId = id.value.substringBefore(':'),
        sensorModelId = "${id.value.substringBefore(':')}:m", advertName = null,
        displayName = "sensor ${id.value}",
        serialSuffix = id.value.substringAfter(':'),
        warmupWindowMin = 0,
        passiveOnly = true,
    )

    private fun reading(id: CgmSourceId, ts: Long, bg: Int) = CgmReading(
        sourceId = id,
        tsMs = ts,
        bgMgdl = bg,
        trendTenthsPerMin = 0,
        minFromStart = 120,
        quality = 100,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0,
        rxWallMs = ts,
        rssi = -70,
    )

    private fun prediction(cycleTs: Long, sourceId: String?) = ModelPrediction(
        modelId = MODEL,
        cycleTsMs = cycleTs,
        anchorTsMs = cycleTs,
        sourceId = sourceId,
        stepMs = step,
        medianBg = List(horizonSteps) { 120.0 },
        // Ascending τ, non-collapsed — the shape the maturation walk requires.
        bandsMgdl = List(horizonSteps * nq) { 100.0 + (it % nq) * 5.0 },
        nQuantiles = nq,
        lastBg = 120.0,
        status = ForecastStatus.OK,
        backend = BackendId.EXECUTORCH_XNNPACK_FP32,
        precision = Precision.FP32,
        selected = true,
        stale = false,
        latencyMs = 1.0,
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

    @Test
    fun aWindowIsRefusedUnlessItsForecastCameFromTheSourceSupplyingItsTruth() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = false, nowMs = t0)
        repo.upsertSource(descriptor(truthSrc), authoritative = true, nowMs = t0)

        for (i in 0..(horizonSteps * 4)) repo.upsertReading(reading(truthSrc, t0 + i * step, 120))
        // Present so a plausible-but-wrong truth exists at every instant.
        for (i in 0..(horizonSteps * 4)) repo.upsertReading(reading(worn, t0 + i * step, 148))

        repo.upsertPredictions(
            listOf(
                prediction(t0 + step, truthSrc.value),  // same sensor  → keep
                prediction(t0 + 2 * step, worn.value),  // other sensor → refuse
                prediction(t0 + 3 * step, null),        // pre-v25 row  → refuse
            ),
            nowMs = t0,
        )

        val now = t0 + (horizonSteps * 4) * step
        val set = repo.forecastWindows(MODEL, horizonMaxMin = 120, sinceMs = t0, nowMs = now)

        assertEquals(
            "only the window whose forecast came from the authoritative sensor may mature",
            1, set.windows.size,
        )
        // Truth is the authoritative sensor's 120, never the worn one's 148.
        assertEquals(120.0, set.windows.single().realizedBg.first(), 1e-9)
    }

    /** `bgSource` null pre-v15, can't attribute a slot: gap set = slots NO sensor covers. */
    @Test
    fun theReconcileSkipsSlotsAnotherSensorAlreadyCovers() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = false, nowMs = t0)
        repo.upsertSource(descriptor(truthSrc), authoritative = true, nowMs = t0)

        // Five slots the outgoing sensor measured and projected; the incoming one has none of them.
        for (i in 0 until 5) {
            val ts = t0 + i * step
            repo.upsertReading(reading(worn, ts, 148))
            db.sampleDao().upsert(
                SampleEntity(
                    ts = ts, tzOffsetMin = 0, bgMgdl = 148, bgSource = null,
                    bgProvenance = ReadingProvenance.MEASURED, bgFlag = ReadingFlag.NORMAL,
                    steps = null, mood = null, hr = null, sleep = null, exercise = null,
                    updatedAt = ts,
                ),
            )
        }
        // The slot no sensor covers — what the reconcile exists to recover.
        val orphan = t0 + 10 * step
        db.sampleDao().upsert(
            SampleEntity(
                ts = orphan, tzOffsetMin = 0, bgMgdl = 101, bgSource = null,
                bgProvenance = ReadingProvenance.MEASURED, bgFlag = ReadingFlag.NORMAL,
                steps = null, mood = null, hr = null, sleep = null, exercise = null,
                updatedAt = orphan,
            ),
        )

        val inserted = repo.reconcileReadingsFromSamples()

        assertEquals("only the uncovered slot may be filled", 1, inserted)
        assertEquals(
            "the incoming sensor must not be given the outgoing one's readings",
            null,
            db.cgmReadingDao().byTs(truthSrc.value, t0),
        )
        assertEquals(
            "and the genuinely missing slot is recovered",
            101,
            db.cgmReadingDao().byTs(truthSrc.value, orphan)?.bgMgdl,
        )
    }

    private companion object {
        const val MODEL = "m"
    }
}
