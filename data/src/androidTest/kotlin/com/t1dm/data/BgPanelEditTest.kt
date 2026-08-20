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
import com.t1dm.core.model.Precision
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

/**
 * The BG panel's own edits: cut a stretch of the curve, put it back, throw a fill away, and move a
 * drawn line through the fan it came with.
 *
 * A cut is the only operation in the app that destroys measured physiologic data on purpose, so
 * what the undo restores is a safety property rather than a convenience — a restore that guessed at
 * provenance would file model output, or a calibration, as an ordinary sensor measurement.
 */
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
        // Risk space is not exercised here — the decode is the crate's, and this suite has no
        // descriptor. What matters is that the blob survives a round trip at its own width.
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

    /** An empty stretch is not an error, and it must not push an undo entry's worth of nothing. */
    @Test
    fun cutting_an_empty_stretch_takes_nothing() = runTest {
        seedThreeReadings()
        assertTrue(repo.cutBgRange(t0 + 10 * step, t0 + 12 * step, now).isEmpty())
        assertEquals(3, db.cgmReadingDao().allAt(t0).size + db.cgmReadingDao().allAt(t0 + step).size +
            db.cgmReadingDao().allAt(t0 + 2 * step).size)
    }

    /**
     * The undo restores the ROWS, provenance and flag included — not a value.
     *
     * A restore that re-filed everything as MEASURED would turn a suppressed or calibrated reading
     * into one `isRealMeasurement` accepts, which is the predicate every alarm and every dose rail
     * is anchored on.
     */
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

    /** A cut invalidates what was derived from the curve it changed. */
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

        // Promoted first: the refusal is the property worth pinning, because this table holds the
        // only copy of the band a promoted sample carries.
        assertTrue(repo.promoteInfillSpan(spanStart, now) is PromoteResult.Promoted)
        assertFalse("a promoted span refuses the discard", repo.discardInfillSpan(spanStart))
        assertEquals(2, db.bgInfillDao().span(spanStart).size)

        assertTrue("demote reports how many rows left the record", repo.demoteInfillSpan(spanStart, now) is PromoteResult.Promoted)
        assertTrue("once demoted it can be thrown away", repo.discardInfillSpan(spanStart))
        assertEquals(0, db.bgInfillDao().span(spanStart).size)
        assertFalse("and discarding a span that is gone reports so", repo.discardInfillSpan(spanStart))
    }

    /**
     * A cut leaves the STORED FORECASTS alone.
     *
     * `invalidateForecastDerivedInTx` drops `prediction` from the change onwards, which is right
     * for a channel-affecting edit and wrong here: a stored forecast is the record of what the
     * model SAID at that cycle, and replaying it is the whole of what the hindsight sweep does.
     * Erasing one compression low took a day of that record with it.
     */
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
                    precision = Precision.FP32,
                    selected = true,
                    stale = false,
                    latencyMs = 10.0,
                ),
            ),
            nowMs = now,
        )
        assertEquals(1, repo.predictionsForModelInRange("m.pte", t0, now).size)

        // A cut BEFORE the forecast was made — the case that used to wipe everything after it.
        repo.cutBgRange(t0, t0, now)

        assertEquals(
            "the record of what the model said survives its inputs being corrected",
            1,
            repo.predictionsForModelInRange("m.pte", t0, now).size,
        )
    }

    /**
     * A cut refuses to cross a PROMOTED span, whole.
     *
     * Erasing one takes its `RECONSTRUCTED` rows out of `cgm_reading` while `bg_infill` still calls
     * the span promoted, and from that state a demote removes nothing, unpromotes anyway, and the
     * band — the only copy there is — becomes ordinary deletable state.
     */
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

        // Demote first, and the same cut goes through.
        assertTrue(repo.demoteInfillSpan(spanStart, now) is PromoteResult.Promoted)
        assertEquals(2, repo.cutBgRange(t0, t0 + 3 * step, now).size)
    }

    /**
     * A promoted span the SENSOR has since superseded can still be demoted, and then discarded.
     *
     * A real measurement replaces a reconstruction in place, while this table's promoted row is
     * deliberately spared. Demotion then finds nothing to remove — and refusing there stranded the
     * span for good: undemotable, undiscardable, and blocking a cut of the very measurements that
     * had replaced it. Nothing of the span is in the record, so saying so is the whole job.
     */
    @Test
    fun a_span_the_sensor_superseded_can_still_be_demoted_and_discarded() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        repo.upsertReading(measured(t0 + 2 * step, 140))
        val spanStart = t0 + step
        repo.saveInfill(listOf(fill(spanStart, spanStart)))
        assertTrue(repo.promoteInfillSpan(spanStart, now) is PromoteResult.Promoted)

        // The sensor's own history arrives for the slot the fill occupied.
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

        // …and the real measurement that replaced it is cuttable again.
        assertEquals(1, repo.cutBgRange(spanStart, spanStart, now).size)
    }

    /**
     * Moving the line records WHICH level it is, so a promotion of it cannot later be read as the
     * median. The fan itself is never touched.
     */
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

    /** The fan round-trips at its own width, which is what makes it drawable as nested bands. */
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
