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

/**
 * A matured window may only pair a forecast with truth from the SAME sensor (Room v25).
 *
 * The defect this pins down: two sensors worn at once do not agree — a 28 mg/dL median gap was
 * measured between two of this patient's — and `forecastWindows` scoped only the TRUTH side to the
 * authoritative source. A forecast conditioned on the outgoing sensor was therefore scored against
 * the incoming one, which measures the gap between two sensors and calls it model error. Those
 * windows feed the realised-accuracy suite, CG-EGA and the `SPEC/inference.md` §8.4 band fit, so a
 * sensor swap silently pushed the sensor gap into the band drawn for the patient: the fitted τ.05
 * offset came out at −28 mg/dL, the sensor gap almost exactly.
 */
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

    /** One well-formed forecast, stamped with the source that conditioned it. */
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

        // Truth from the authoritative sensor, covering every step of every window below.
        for (i in 0..(horizonSteps * 4)) repo.upsertReading(reading(truthSrc, t0 + i * step, 120))
        // The other sensor is worn at the same time and reads 28 mg/dL higher. Present so the test
        // fails the way the field did — a plausible reading at every instant, simply not this one's.
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
        // And it is the right one: truth is the authoritative sensor's 120, never the worn one's 148.
        assertEquals(120.0, set.windows.single().realizedBg.first(), 1e-9)
    }

    /**
     * The sample→reading reconcile may not invent one sensor's history for another.
     *
     * `sample` is a single projection written by whichever source held authority at the time, and its
     * `bgSource` is opaque and null for every row written before v15 — so it cannot be used to
     * attribute a slot. The gap set is therefore slots NO sensor covers. Matching per source, and
     * later per model class, each let a sensor change re-import the outgoing sensor's whole record as
     * the incoming sensor's own MEASURED readings, which is the truth side of the window guard above.
     */
    @Test
    fun theReconcileSkipsSlotsAnotherSensorAlreadyCovers() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = false, nowMs = t0)
        repo.upsertSource(descriptor(truthSrc), authoritative = true, nowMs = t0)

        // Five slots the OUTGOING sensor measured, projected into `sample` as it was authoritative
        // then. The incoming sensor has no reading at any of them — the shape that used to match.
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
        // One slot nothing has a reading for — the case the reconcile genuinely exists to recover.
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
