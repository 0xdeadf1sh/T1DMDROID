package com.t1dm.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BgInfillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Promotion is the one act that turns model output into the patient's stored history. */
@RunWith(AndroidJUnit4::class)
class PromoteInfillTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)
    private val src = CgmSourceId("s-1")
    private val t0 = 1_700_000_100_000L / 300_000L * 300_000L
    private val now = t0 + 10 * 300_000L

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

    private fun measured(ts: Long, mgdl: Int) = CgmReading(
        sourceId = src,
        tsMs = ts,
        bgMgdl = mgdl,
        trendTenthsPerMin = null,
        minFromStart = null,
        quality = null,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 60,
        rxWallMs = ts,
        rssi = null,
    )

    private fun fill(ts: Long, spanStart: Long, mgdl: Double = 120.0) = BgInfillEntity(
        ts = ts,
        mgdl = mgdl,
        lo90 = mgdl - 20.0,
        hi90 = mgdl + 20.0,
        modelId = "m.pte",
        createdAtMs = now,
        spanStartMs = spanStart,
    )

    /** A span bracketed by real measurements, in the past — the one shape that may be promoted. */
    private suspend fun seedPromotableSpan(): Long {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        repo.upsertReading(measured(t0 + 4 * 300_000L, 140))
        val spanStart = t0 + 300_000L
        repo.saveInfill(listOf(fill(spanStart, spanStart), fill(spanStart + 300_000L, spanStart)))
        return spanStart
    }

    @Test
    fun a_promoted_span_becomes_a_reconstructed_sample_that_is_never_a_measurement() = runTest {
        val spanStart = seedPromotableSpan()

        val r = repo.promoteInfillSpan(spanStart, now)
        assertTrue(r.toString(), r is PromoteResult.Promoted)
        assertEquals(2, (r as PromoteResult.Promoted).steps)

        val row = db.cgmReadingDao().byTs(src.value, spanStart)
        assertNotNull(row)
        assertEquals(ReadingProvenance.RECONSTRUCTED, row!!.provenance)
        assertEquals(120, row.bgMgdl)
        // The offset came from the bracketing measurement, never from the clock's zone now.
        assertEquals(60, row.tzOffsetMin)

        val sample = db.sampleDao().byTs(spanStart)
        assertNotNull(sample)
        assertEquals(ReadingProvenance.RECONSTRUCTED, sample!!.bgProvenance)
        // `bgSource` is an assertion about which SENSOR produced the number, and none did.
        assertNull(sample.bgSource)

        // The band survives, and it is the only copy: the wire carries a boolean and no fan.
        assertEquals(2, db.bgInfillDao().span(spanStart).size)
        assertTrue(db.bgInfillDao().span(spanStart).all { it.promotedAtMs != null })
    }

    /** A span with nothing measured before it has one anchor: draw is fine, storing is not. */
    @Test
    fun a_backcast_span_is_refused() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0 + 10 * 300_000L, 120))
        val spanStart = t0
        repo.saveInfill(listOf(fill(spanStart, spanStart)))

        val r = repo.promoteInfillSpan(spanStart, now)
        assertTrue(r.toString(), r is PromoteResult.Refused)
        assertNull(db.cgmReadingDao().byTs(src.value, spanStart))
    }

    /** It would become the newest `cgm_reading` row, every glance surface reads as current BG. */
    @Test
    fun a_forecast_span_is_refused() = runTest {
        repo.upsertSource(descriptor(), authoritative = true, nowMs = t0)
        repo.upsertReading(measured(t0, 100))
        val spanStart = t0 + 300_000L
        repo.saveInfill(listOf(fill(spanStart, spanStart)))

        val r = repo.promoteInfillSpan(spanStart, now)
        assertTrue(r.toString(), r is PromoteResult.Refused)
        assertNull(db.cgmReadingDao().byTs(src.value, spanStart))
    }

    /** A band that does not bracket its own median is withheld rather than stored. */
    @Test
    fun a_degenerate_band_is_refused() = runTest {
        val spanStart = seedPromotableSpan()
        db.bgInfillDao().upsert(listOf(fill(spanStart, spanStart).copy(lo90 = 200.0, hi90 = 210.0)))

        val r = repo.promoteInfillSpan(spanStart, now)
        assertTrue(r.toString(), r is PromoteResult.Refused)
        assertNull(db.cgmReadingDao().byTs(src.value, spanStart))
    }

    /** A real measurement always beats a reconstruction for a slot; the contest isn't symmetric. */
    @Test
    fun a_measurement_landing_in_a_promoted_slot_wins_and_keeps_the_band() = runTest {
        val spanStart = seedPromotableSpan()
        repo.promoteInfillSpan(spanStart, now)

        repo.upsertReading(measured(spanStart, 133))

        val row = db.cgmReadingDao().byTs(src.value, spanStart)!!
        assertEquals("the sensor's reading takes the slot", ReadingProvenance.MEASURED, row.provenance)
        assertEquals(133, row.bgMgdl)
        // The band is not deleted out from under it: demotion reads the span from this table.
        assertEquals(2, db.bgInfillDao().span(spanStart).size)
    }

    /** Demotion removes only what is still a reconstruction, and clears the slot for the wire. */
    @Test
    fun demotion_removes_the_reconstruction_and_leaves_a_measurement_alone() = runTest {
        val spanStart = seedPromotableSpan()
        repo.promoteInfillSpan(spanStart, now)
        repo.upsertReading(measured(spanStart + 300_000L, 150))

        val r = repo.demoteInfillSpan(spanStart, now + 1_000)
        assertTrue(r.toString(), r is PromoteResult.Promoted)

        assertNull("the reconstruction is gone", db.cgmReadingDao().byTs(src.value, spanStart))
        assertEquals(
            "the measurement stays",
            ReadingProvenance.MEASURED,
            db.cgmReadingDao().byTs(src.value, spanStart + 300_000L)!!.provenance,
        )
        assertNull(db.sampleDao().byTs(spanStart)!!.bgMgdl)
        assertTrue(db.bgInfillDao().span(spanStart).all { it.promotedAtMs == null })
    }
}
