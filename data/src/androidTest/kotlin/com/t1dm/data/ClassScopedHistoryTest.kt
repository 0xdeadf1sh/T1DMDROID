package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CgmSensorModelId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The BG panel's history spans a sensor MODEL, not one physical sensor — §3.1. */
@RunWith(AndroidJUnit4::class)
class ClassScopedHistoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val expired = CgmSourceId("aidexx:EXPIRED")
    private val fresh = CgmSourceId("aidexx:FRESH")
    private val otherModel = CgmSourceId("aidexx:OTHERMODEL")

    private fun descriptor(id: CgmSourceId, sensorModelId: String) = CgmSourceDescriptor(
        id = id,
        vendorId = "aidexx",
        sensorModelId = sensorModelId, advertName = null,
        displayName = "sensor ${id.value}",
        serialSuffix = id.value.substringAfter(':'),
        warmupWindowMin = 60,
        passiveOnly = true,
    )

    private fun reading(id: CgmSourceId, ts: Long, bg: Int, rxWallMs: Long = ts) = CgmReading(
        sourceId = id,
        tsMs = ts,
        bgMgdl = bg,
        trendTenthsPerMin = 0,
        minFromStart = 120,
        quality = 100,
        provenance = ReadingProvenance.MEASURED,
        flag = ReadingFlag.NORMAL,
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
    fun replacingASensorKeepsTheExpiredOnesHistoryOnThePanel() = runTest {
        repo.upsertSource(descriptor(expired, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 1_000)
        repo.upsertReading(reading(expired, 300_000L, bg = 100))
        repo.upsertReading(reading(expired, 600_000L, bg = 110))

        repo.upsertSource(descriptor(fresh, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 2_000)
        repo.upsertReading(reading(fresh, 1_200_000L, bg = 120))

        val history = repo.observeReadingsForSensorModel(CgmSensorModelId.AIDEX_X, fresh, 0, Long.MAX_VALUE).first()

        assertEquals(listOf(300_000L, 600_000L, 1_200_000L), history.map { it.tsMs })
        // The oldest reading floors the graph's pannable domain.
        assertEquals(300_000L, history.first().tsMs)
        assertEquals(1, repo.observeReadings(fresh, 0, Long.MAX_VALUE).first().size)
    }

    @Test
    fun aSensorOfAnotherModelStaysOutOfTheClass() = runTest {
        repo.upsertSource(descriptor(fresh, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 1_000)
        repo.upsertSource(descriptor(otherModel, "aidexx:2"), authoritative = false, nowMs = 1_000)
        repo.upsertReading(reading(fresh, 300_000L, bg = 100))
        repo.upsertReading(reading(otherModel, 600_000L, bg = 200))

        val history = repo.observeReadingsForSensorModel(CgmSensorModelId.AIDEX_X, fresh, 0, Long.MAX_VALUE).first()

        assertEquals(1, history.size)
        assertEquals(fresh, history.single().sourceId)
    }

    /** Both sensors worn at once — the replacement warming up while the old one still reports. */
    @Test
    fun aContestedGridSlotResolvesToTheSelectedSource() = runTest {
        repo.upsertSource(descriptor(expired, CgmSensorModelId.AIDEX_X), authoritative = false, nowMs = 1_000)
        repo.upsertSource(descriptor(fresh, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 2_000)
        // The retiring sensor received LATER, so newest-wins alone would have picked it.
        repo.upsertReading(reading(expired, 300_000L, bg = 137, rxWallMs = 9_000L))
        repo.upsertReading(reading(fresh, 300_000L, bg = 142, rxWallMs = 1_000L))

        val history = repo.observeReadingsForSensorModel(CgmSensorModelId.AIDEX_X, fresh, 0, Long.MAX_VALUE).first()

        assertEquals(1, history.size)
        assertEquals(fresh, history.single().sourceId)
        // The number printed above the trace reads the active source alone.
        assertEquals(repo.observeLatestReading(fresh).first()?.bgMgdl, history.single().bgMgdl)
    }

    @Test
    fun aClassWithNoSourceYieldsNoHistoryRatherThanFailing() = runTest {
        // Room emits `IN ()` for an empty id list and SQLite rejects it, so this must short-circuit.
        assertTrue(
            repo.observeReadingsForSensorModel("aidexx:nothing", null, 0, Long.MAX_VALUE).first().isEmpty(),
        )
    }

    @Test
    fun theClassFloorIsTheRecordsBeginningNotTheLoadedWindows() = runTest {
        repo.upsertSource(descriptor(expired, CgmSensorModelId.AIDEX_X), authoritative = false, nowMs = 1_000)
        repo.upsertSource(descriptor(fresh, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 2_000)
        repo.upsertReading(reading(expired, 300_000L, bg = 100))
        repo.upsertReading(reading(fresh, 900_000L, bg = 120))

        val windowed = repo.observeReadingsForSensorModel(
            CgmSensorModelId.AIDEX_X, fresh, fromMs = 600_000L, toMs = Long.MAX_VALUE,
        ).first()
        assertEquals(listOf(900_000L), windowed.map { it.tsMs })

        assertEquals(300_000L, repo.observeOldestTsForSensorModel(CgmSensorModelId.AIDEX_X).first())
    }

    @Test
    fun aClassHoldingNothingHasNoFloor() = runTest {
        assertTrue(repo.observeOldestTsForSensorModel("aidexx:nothing").first() == null)
        repo.upsertSource(descriptor(fresh, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 1_000)
        assertTrue(repo.observeOldestTsForSensorModel(CgmSensorModelId.AIDEX_X).first() == null)
    }

    /** The reconcile's gap set spans the class, not one source. */
    @Test
    fun reconcileDoesNotDuplicateHistoryOntoAReplacementSensor() = runTest {
        repo.upsertSource(descriptor(expired, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 1_000)
        repo.upsertReading(reading(expired, 300_000L, bg = 100))
        repo.upsertReading(reading(expired, 600_000L, bg = 110))
        // Both readings projected into `sample`, which is not source-scoped.
        assertEquals(0, repo.reconcileReadingsFromSamples())

        repo.upsertSource(descriptor(fresh, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 2_000)

        assertEquals("the replacement inherited a copy of the record", 0, repo.reconcileReadingsFromSamples())
        assertEquals(0, repo.observeReadings(fresh, 0, Long.MAX_VALUE).first().size)
        assertEquals(2, repo.observeReadingsForSensorModel(CgmSensorModelId.AIDEX_X, fresh, 0, Long.MAX_VALUE).first().size)
    }

    @Test
    fun theWindowBoundsStillApplyAcrossTheWholeClass() = runTest {
        repo.upsertSource(descriptor(expired, CgmSensorModelId.AIDEX_X), authoritative = false, nowMs = 1_000)
        repo.upsertSource(descriptor(fresh, CgmSensorModelId.AIDEX_X), authoritative = true, nowMs = 2_000)
        repo.upsertReading(reading(expired, 300_000L, bg = 100))
        repo.upsertReading(reading(fresh, 900_000L, bg = 120))

        val window = repo.observeReadingsForSensorModel(
            CgmSensorModelId.AIDEX_X,
            fresh,
            fromMs = 600_000L,
            toMs = Long.MAX_VALUE,
        ).first()

        assertEquals(listOf(900_000L), window.map { it.tsMs })
    }
}
