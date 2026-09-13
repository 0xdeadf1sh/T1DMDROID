package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSensorModelId
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The BG panel's history is ONE sensor's, never a model's. */
@RunWith(AndroidJUnit4::class)
class PerSensorHistoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val worn = CgmSourceId("aidexx:WORN")
    private val alsoWorn = CgmSourceId("aidexx:ALSOWORN")

    private fun descriptor(id: CgmSourceId) = CgmSourceDescriptor(
        id = id,
        vendorId = "aidexx",
        sensorModelId = CgmSensorModelId.AIDEX_X,
        advertName = null,
        displayName = id.value,
        serialSuffix = id.value.substringAfter(':'),
        warmupWindowMin = 60,
        passiveOnly = true,
    )

    private fun reading(id: CgmSourceId, tsMs: Long, bg: Int) = CgmReading(
        sourceId = id,
        tsMs = tsMs,
        bgMgdl = bg,
        trendTenthsPerMin = null,
        minFromStart = 600,
        quality = null,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0,
        rxWallMs = tsMs,
        rssi = null,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    /** Two electrodes with their own calibration. Merging them draws a line neither measured. */
    @Test
    fun twoSensorsWornTogetherDoNotShareATrace() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = true, nowMs = 0)
        repo.upsertSource(descriptor(alsoWorn), authoritative = false, nowMs = 0)
        for (slot in 1..4) {
            repo.upsertReading(reading(worn, slot * 300_000L, bg = 100))
            repo.upsertReading(reading(alsoWorn, slot * 300_000L, bg = 200))
        }

        val mine = repo.observeReadingsForSource(worn, 0, Long.MAX_VALUE).first()
        val theirs = repo.observeReadingsForSource(alsoWorn, 0, Long.MAX_VALUE).first()

        assertEquals(listOf(100, 100, 100, 100), mine.map { it.bgMgdl })
        assertEquals(listOf(200, 200, 200, 200), theirs.map { it.bgMgdl })
        assertTrue(mine.all { it.sourceId == worn })
    }

    /** A replacement sensor starts its own trace; the outgoing wear is read by switching to it. */
    @Test
    fun aReplacementSensorStartsItsOwnTrace() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = false, nowMs = 0)
        repo.upsertSource(descriptor(alsoWorn), authoritative = true, nowMs = 0)
        repo.upsertReading(reading(worn, 300_000L, bg = 90))
        repo.upsertReading(reading(alsoWorn, 600_000L, bg = 140))

        val fresh = repo.observeReadingsForSource(alsoWorn, 0, Long.MAX_VALUE).first()

        assertEquals(listOf(140), fresh.map { it.bgMgdl })
        assertEquals("the outgoing wear is not spliced on", 600_000L, fresh.single().tsMs)
    }

    /** The pannable floor is the viewed sensor's own oldest reading, not the model's. */
    @Test
    fun theFloorIsThisSensorsOwn() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = false, nowMs = 0)
        repo.upsertSource(descriptor(alsoWorn), authoritative = true, nowMs = 0)
        repo.upsertReading(reading(worn, 300_000L, bg = 90))
        repo.upsertReading(reading(alsoWorn, 900_000L, bg = 140))

        assertEquals(300_000L, repo.observeOldestTsForSource(worn).first())
        assertEquals(900_000L, repo.observeOldestTsForSource(alsoWorn).first())
        assertNull(repo.observeOldestTsForSource(CgmSourceId("aidexx:NEVERWORN")).first())
    }

    @Test
    fun theWindowBoundsBothEnds() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = true, nowMs = 0)
        for (slot in 1..6) repo.upsertReading(reading(worn, slot * 300_000L, bg = 100 + slot))

        val windowed = repo.observeReadingsForSource(worn, 600_000L, 1_200_000L).first()

        assertEquals(listOf(102, 103, 104), windowed.map { it.bgMgdl })
    }

    /** A bout worn on a replaced sensor resolves to that sensor, not the one believed now. */
    @Test
    fun aWindowResolvesToTheSensorHoldingItsReadings() = runTest {
        repo.upsertSource(descriptor(worn), authoritative = false, nowMs = 0)
        repo.upsertSource(descriptor(alsoWorn), authoritative = true, nowMs = 0)
        for (slot in 1..3) repo.upsertReading(reading(worn, slot * 300_000L, bg = 90))
        for (slot in 3..8) repo.upsertReading(reading(alsoWorn, slot * 300_000L, bg = 140))

        assertEquals(worn, repo.sourceWithMostReadingsIn(0, 900_000L))
        assertEquals(alsoWorn, repo.sourceWithMostReadingsIn(1_200_000L, 2_400_000L))
        assertNull(repo.sourceWithMostReadingsIn(3_000_000L, 4_000_000L))
    }
}
