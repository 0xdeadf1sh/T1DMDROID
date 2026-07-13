package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room verification of the single grid-snap authority (§4-#1): the repository writer
 * ([T1dmRepository.logLoggedDose] / [T1dmRepository.logMeal]) round-snaps a raw wall-clock `tsMs`
 * onto the 5-min grid at insert, so a logged event's bucket coincides with its own scalar-sample
 * bucket (no ≤±150 s sub-grid smear, the phantom the old reconcile double-counted). Instrumented
 * so the real SQLite exercises the persisted snap, not merely the returned value.
 */
@RunWith(AndroidJUnit4::class)
class RoomDoseGridSnapTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository
    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After fun tearDown() = db.close()

    // A grid-aligned base (== 1_000_000 · GRID) so the nearest bucket of G+137 s is provably G:
    // 137 s < 150 s ⇒ round-to-nearest floors it back to the bucket start.
    private val g = 300_000_000_000L
    private val raw = g + 137_000L
    // Replicates T1dmRepository.snapToGrid (private) — Math.floorDiv(ts + GRID/2, GRID) * GRID.
    private val expected = Math.floorDiv(raw + GRID / 2, GRID) * GRID

    @Test
    fun loggedDose_tsSnappedToItsSampleBucket() = runTest {
        val stored = repo.logLoggedDose(doseAt(raw))

        assertEquals(0L, stored.tsMs % GRID)          // lands on the 5-min grid
        assertEquals(expected, stored.tsMs)           // == snapToGrid(raw)
        assertEquals(g, stored.tsMs)                  // the nearest bucket is G itself

        // Persisted snapped (not merely returned snapped): the row ChannelBuilder reads is on-grid.
        val persisted = repo.loggedDosesInRange(g - 10 * GRID, g + 10 * GRID).single()
        assertEquals(expected, persisted.tsMs)

        // The event bucket IS a legal sample bucket: a scalar sample at the same instant shares the
        // slot (requireGrid(expected) passing is itself the proof the snapped ts is a valid key).
        assertTrue(repo.mergeServerSample(SamplePatch(ts = expected, tzOffsetMin = 0, updatedAt = raw, steps = 1)))
        assertEquals(stored.tsMs, repo.sampleAt(expected)!!.ts)
    }

    @Test
    fun loggedMeal_tsSnappedToItsSampleBucket() = runTest {
        val stored = repo.logMeal(mealAt(raw))

        assertEquals(0L, stored.tsMs % GRID)
        assertEquals(expected, stored.tsMs)
        assertEquals(g, stored.tsMs)

        val persisted = repo.loggedMealsInRange(g - 10 * GRID, g + 10 * GRID).single()
        assertEquals(expected, persisted.tsMs)
    }

    private fun doseAt(tsMs: Long) = LoggedDoseEntity(
        clientId = "", tsMs = tsMs, kind = DoseKind.BOLUS, units = 5.0, durationMin = 360.0,
        k = 2.0, theta = 40.0, kaPerHour = null, kePerHour = null,
        tzOffsetMin = 0, note = null, updatedAt = tsMs,
    )

    private fun mealAt(tsMs: Long) = LoggedMealEntity(
        clientId = "", tsMs = tsMs, grams = 40.0, gi = 50.0, k = null, theta = null,
        durationMin = 240.0, customCurve = null, tzOffsetMin = 0, note = null, updatedAt = tsMs,
    )

    private companion object { const val GRID = 300_000L }
}
