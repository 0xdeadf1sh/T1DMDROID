package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room verification of the Phase-1 persistence invariants (§3.1/§3.5).
 * Instrumented (real device SQLite) — deterministic and sensor-free.
 */
@RunWith(AndroidJUnit4::class)
class RoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    // io = Default keeps every DB call off a fake test scheduler and on real worker threads.
    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val sourceId = CgmSourceId("aidexx:TESTSOURCE")
    private val descriptor = CgmSourceDescriptor(
        id = sourceId,
        vendorId = "aidexx",
        displayName = "AiDEX X TEST",
        serialSuffix = "TESTSOURCE",
        warmupWindowMin = 60,
        passiveOnly = true,
    )

    private fun reading(
        ts: Long,
        bg: Int?,
        provenance: ReadingProvenance,
        flag: ReadingFlag = ReadingFlag.NORMAL,
        rxWallMs: Long = ts,
    ) = CgmReading(
        sourceId = sourceId,
        tsMs = ts,
        bgMgdl = bg,
        trendTenthsPerMin = 0,
        minFromStart = 120,
        quality = 100,
        provenance = provenance,
        flag = flag,
        tzOffsetMin = 0,
        rxWallMs = rxWallMs,
        rssi = -70,
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
    fun tearDown() {
        db.close()
    }

    @Test
    fun gridKeyUpsertsInPlace() = runTest {
        val ts = 300_000L
        // An early INTERPOLATED gap-fill, then the real MEASURED value at the same grid slot.
        repo.upsertReading(reading(ts, bg = 100, provenance = ReadingProvenance.INTERPOLATED, rxWallMs = ts))
        repo.upsertReading(reading(ts, bg = 120, provenance = ReadingProvenance.MEASURED, rxWallMs = ts + 1))

        val rows = repo.observeReadings(sourceId, 0, Long.MAX_VALUE).first()
        assertEquals(1, rows.size) // upsert-in-place on (sourceId, tsMs), not a second row
        assertEquals(120, rows.first().bgMgdl)
        assertEquals(ReadingProvenance.MEASURED, rows.first().provenance)
    }

    @Test
    fun projectsSampleFromActiveSourceReading() = runTest {
        repo.upsertSource(descriptor, active = true, nowMs = 1_000L)
        val ts = 600_000L
        repo.upsertReading(reading(ts, bg = 140, provenance = ReadingProvenance.MEASURED, rxWallMs = ts))

        val sample = repo.sampleAt(ts)
        assertNotNull(sample)
        assertEquals(140, sample!!.bgMgdl)
        assertEquals(ReadingProvenance.MEASURED, sample.bgProvenance)
        assertEquals(ReadingFlag.NORMAL, sample.bgFlag)
    }

    @Test
    fun rejectsOffGridTimestamp() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            // runTest already provides the coroutine; drive the suspend call synchronously here.
            kotlinx.coroutines.runBlocking {
                repo.upsertReading(reading(300_001L, bg = 100, provenance = ReadingProvenance.MEASURED))
            }
        }
    }

    @Test
    fun enqueuesOneDedupedIngestPerGridSlot() = runTest {
        repo.upsertSource(descriptor, active = true, nowMs = 1_000L)
        val ts = 900_000L
        repo.upsertReading(reading(ts, bg = 110, provenance = ReadingProvenance.INTERPOLATED, rxWallMs = ts))
        repo.upsertReading(reading(ts, bg = 115, provenance = ReadingProvenance.MEASURED, rxWallMs = ts + 1))

        // dedupKey = "ingest:sample:$ts" ⇒ the second write at the same slot must not add a row.
        assertEquals(1, repo.observeOutboxDepth().first())
    }
}
