package com.t1dm.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BgInfillEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BgPanelEditTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)
    private val src = CgmSourceId("s-1")
    private val step = 300_000L
    private val t0 = 1_700_000_100_000L / step * step
    private val now = t0 + 20 * step

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    private fun descriptor() = CgmSourceDescriptor(
        id = src,
        vendorId = "test",
        sensorModelId = "test:m1",
        advertName = null,
        displayName = "sensor",
        serialSuffix = "1",
        warmupWindowMin = 60,
        passiveOnly = true,
    )

    private fun measured(
        ts: Long,
        mgdl: Int,
        provenance: ReadingProvenance = ReadingProvenance.MEASURED,
        flag: ReadingFlag = ReadingFlag.NORMAL,
    ) = CgmReading(
        sourceId = src,
        tsMs = ts,
        bgMgdl = mgdl,
        trendTenthsPerMin = 3,
        minFromStart = null,
        quality = null,
        provenance = provenance,
        flag = flag,
        tzOffsetMin = 60,
        rxWallMs = ts,
        rssi = null,
    )

    private fun fan(centre: Double) = List(7) { k -> centre + (k - 3) * 12.0 }

    private fun fill(ts: Long, spanStart: Long, mgdl: Double = 120.0) = BgInfillEntity(
        ts = ts,
        mgdl = mgdl,
        lo90 = mgdl - 36.0,
        hi90 = mgdl + 36.0,
        modelId = "m.pte",
        createdAtMs = now,
        spanStartMs = spanStart,
        bandsMgdl = fan(mgdl).toBlob(),
        // Not real risk-space values; only the round trip is under test.
        bandsRisk = fan(mgdl / 100.0).toBlob(),
        tau = 0.5,
    )

    private suspend fun seedThreeReadings() {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        repo.upsertReading(measured(t0 + step, 110))
        repo.upsertReading(measured(t0 + 2 * step, 120))
    }

    @Test
    fun a_cut_erases_the_range_and_reports_exactly_what_it_took() = runTest {
        seedThreeReadings()

        val taken = repo.cutBgRange(t0, t0 + step, now)

        assertEquals("both slots in the range, and not the third", 2, taken.size)
        assertNull(db.cgmReadingDao().byTs(src.value, t0))
        assertNull(db.cgmReadingDao().byTs(src.value, t0 + step))
        assertNotNull("the slot outside the range is untouched", db.cgmReadingDao().byTs(src.value, t0 + 2 * step))
        assertNull(db.sampleDao().byTs(t0)?.bgMgdl)
    }

    @Test
    fun cutting_an_empty_stretch_takes_nothing() = runTest {
        seedThreeReadings()
        assertTrue(repo.cutBgRange(t0 + 10 * step, t0 + 12 * step, now).isEmpty())
        assertEquals(3, db.cgmReadingDao().allAt(t0).size + db.cgmReadingDao().allAt(t0 + step).size +
            db.cgmReadingDao().allAt(t0 + 2 * step).size)
    }

    /** Provenance and flag come back too: `isRealMeasurement` gates every alarm and dose rail. */
    @Test
    fun an_undo_puts_back_the_row_it_took_rather_than_a_number() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100, ReadingProvenance.MEASURED, ReadingFlag.WARMUP))

        val taken = repo.cutBgRange(t0, t0, now)
        assertEquals(1, taken.size)
        assertNull(db.cgmReadingDao().byTs(src.value, t0))

        repo.restoreBgCut(taken, now + step)

        val back = db.cgmReadingDao().byTs(src.value, t0)
        assertNotNull(back)
        assertEquals(100, back!!.bgMgdl)
        assertEquals(ReadingProvenance.MEASURED, back.provenance)
        assertEquals("the flag came back with it", ReadingFlag.WARMUP, back.flag)
        assertEquals("and so did the offset it was filed under", 60, back.tzOffsetMin)
        assertEquals(3, back.trendTenthsPerMin)
        assertEquals(100.0, db.sampleDao().byTs(t0)?.bgMgdl?.toDouble())
    }

    /** A cut and its undo both push the slot, or the phone and the server disagree about it. */
    @Test
    fun a_cut_and_its_undo_both_enqueue_the_slot() = runTest {
        seedThreeReadings()
        db.outboxDao().deleteAllRows()

        val taken = repo.cutBgRange(t0, t0, now)
        assertTrue("the cut is pushed", db.outboxDao().count() > 0)

        db.outboxDao().deleteAllRows()
        repo.restoreBgCut(taken, now + step)
        assertTrue("and so is the restore", db.outboxDao().count() > 0)
    }

    @Test
    fun a_cut_drops_the_unpromoted_fills_that_followed_it() = runTest {
        seedThreeReadings()
        val spanStart = t0 + 5 * step
        repo.saveInfill(listOf(fill(spanStart, spanStart)))
        assertEquals(1, db.bgInfillDao().span(spanStart).size)

        repo.cutBgRange(t0, t0, now)

        assertEquals(
            "a reconstruction of a curve that has changed describes a history that no longer exists",
            0,
            db.bgInfillDao().span(spanStart).size,
        )
    }

    @Test
    fun a_drawn_span_can_be_discarded_and_a_promoted_one_cannot() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        repo.upsertReading(measured(t0 + 3 * step, 140))
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart), fill(spanStart + step, spanStart)))

        // This table holds the only copy of a promoted sample's band.
        assertTrue(repo.promoteInfillSpan(spanStart, now) is PromoteResult.Promoted)
        assertFalse("a promoted span refuses the discard", repo.discardInfillSpan(spanStart))
        assertEquals(2, db.bgInfillDao().span(spanStart).size)

        assertTrue("demote reports how many rows left the record", repo.demoteInfillSpan(spanStart, now) is PromoteResult.Promoted)
        assertTrue("once demoted it can be thrown away", repo.discardInfillSpan(spanStart))
        assertEquals(0, db.bgInfillDao().span(spanStart).size)
        assertFalse("and discarding a span that is gone reports so", repo.discardInfillSpan(spanStart))
    }

    /** Stored forecast is what the model SAID; invalidateForecastDerivedInTx must skip it. */
    @Test
    fun a_cut_keeps_the_forecasts_that_were_already_made() = runTest {
        seedThreeReadings()
        val made = t0 + 5 * step
        repo.upsertPredictions(
            listOf(
                ModelPrediction(
                    modelId = "m.pte",
                    cycleTsMs = made,
                    anchorTsMs = made,
                    stepMs = step,
                    medianBg = List(3) { 110.0 + it },
                    bandsMgdl = List(3 * 7) { 100.0 + it },
                    nQuantiles = 7,
                    lastBg = 100.0,
                    status = ForecastStatus.OK,
                    backend = BackendId.EXECUTORCH_XNNPACK_FP32,
                    selected = true,
                    stale = false,
                    latencyMs = 10.0,
                ),
            ),
            nowMs = now,
        )
        assertEquals(1, repo.predictionsForModelInRange("m.pte", t0, now).size)

        repo.cutBgRange(t0, t0, now)

        assertEquals(
            "the record of what the model said survives its inputs being corrected",
            1,
            repo.predictionsForModelInRange("m.pte", t0, now).size,
        )
    }

    /** Cutting RECONSTRUCTED rows while bg_infill calls span promoted leaves it deletable state. */
    @Test
    fun a_cut_refuses_to_cross_a_promoted_span() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        repo.upsertReading(measured(t0 + 3 * step, 140))
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart), fill(spanStart + step, spanStart)))
        assertTrue(repo.promoteInfillSpan(spanStart, now) is PromoteResult.Promoted)

        val thrown = runCatching { repo.cutBgRange(t0, t0 + 3 * step, now) }.exceptionOrNull()
        assertTrue("the cut is refused", thrown is IllegalStateException)
        assertNotNull("and the measurement beside it is untouched", db.cgmReadingDao().byTs(src.value, t0))
        assertEquals(2, db.bgInfillDao().span(spanStart).size)
        assertTrue(db.bgInfillDao().span(spanStart).all { it.promotedAtMs != null })

        assertTrue(repo.demoteInfillSpan(spanStart, now) is PromoteResult.Promoted)
        assertEquals(2, repo.cutBgRange(t0, t0 + 3 * step, now).size)
    }

    /** A measurement replaces reconstruction in place; demotion finds nothing but must succeed. */
    @Test
    fun a_span_the_sensor_superseded_can_still_be_demoted_and_discarded() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        repo.upsertReading(measured(t0 + 2 * step, 140))
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart)))
        assertTrue(repo.promoteInfillSpan(spanStart, now) is PromoteResult.Promoted)

        repo.upsertReading(measured(spanStart, 130))
        assertEquals(
            ReadingProvenance.MEASURED,
            db.cgmReadingDao().byTs(src.value, spanStart)?.provenance,
        )

        assertTrue(repo.demoteInfillSpan(spanStart, now) is PromoteResult.Promoted)
        assertTrue(
            "nothing of it is stored, so nothing of it is promoted",
            db.bgInfillDao().span(spanStart).all { it.promotedAtMs == null },
        )
        assertTrue("and it can now be thrown away", repo.discardInfillSpan(spanStart))

        assertEquals(1, repo.cutBgRange(spanStart, spanStart, now).size)
    }

    /** Recording the level keeps a later promotion from being read as the median. */
    @Test
    fun moving_the_line_records_its_level_and_leaves_the_fan_alone() = runTest {
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart, 120.0), fill(spanStart + step, spanStart, 130.0)))
        val fanBefore = db.bgInfillDao().span(spanStart).map { it.bandsMgdl.toDoubleList() }

        assertTrue(repo.retauInfillSpan(spanStart, 0.75, listOf(132.0, 142.0)))

        val rows = db.bgInfillDao().span(spanStart)
        assertEquals(listOf(132.0, 142.0), rows.map { it.mgdl })
        assertEquals(listOf(0.75, 0.75), rows.map { it.tau })
        assertEquals("the emitted fan is untouched", fanBefore, rows.map { it.bandsMgdl.toDoubleList() })
        assertEquals("and so are its outer edges", listOf(84.0, 94.0), rows.map { it.lo90 })
    }

    /** A line of the wrong length is a fan read against the wrong span, and it is refused whole. */
    @Test
    fun a_line_that_does_not_match_the_span_is_refused() = runTest {
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart), fill(spanStart + step, spanStart)))
        assertFalse(repo.retauInfillSpan(spanStart, 0.75, listOf(132.0)))
        assertEquals(listOf(0.5, 0.5), db.bgInfillDao().span(spanStart).map { it.tau })
    }

    /** A promoted span's line is a stored sample. Moving it would rewrite history from a slider. */
    @Test
    fun a_promoted_span_refuses_the_sweep() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        repo.upsertReading(measured(t0 + 2 * step, 140))
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart)))
        assertTrue(repo.promoteInfillSpan(spanStart, now) is PromoteResult.Promoted)

        assertFalse(repo.retauInfillSpan(spanStart, 0.9, listOf(156.0)))
        assertEquals(0.5, db.bgInfillDao().span(spanStart).single().tau, 0.0)
    }

    @Test
    fun the_stored_fan_keeps_its_seven_levels() = runTest {
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart, 120.0)))
        val row = db.bgInfillDao().span(spanStart).single()
        assertEquals(7, row.bandsMgdl.toDoubleList().size)
        assertEquals(7, row.bandsRisk.toDoubleList().size)
        assertEquals(fan(120.0), row.bandsMgdl.toDoubleList())
    }
}
