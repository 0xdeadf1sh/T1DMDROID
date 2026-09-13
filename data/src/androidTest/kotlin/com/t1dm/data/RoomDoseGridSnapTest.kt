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

/** The single grid-snap authority (§4-#1): the repository writer snaps `tsMs` at insert. */
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

    // 137 s < 150 s, so the nearest bucket of g + 137 s is g itself.
    private val g = 300_000_000_000L
    private val raw = g + 137_000L
    // Replicates T1dmRepository.snapToGrid (private).
    private val expected = Math.floorDiv(raw + GRID / 2, GRID) * GRID

    @Test
    fun loggedDose_tsSnappedToItsSampleBucket() = runTest {
        val stored = repo.logLoggedDose(doseAt(raw))

        assertEquals(0L, stored.tsMs % GRID)
        assertEquals(expected, stored.tsMs)
        assertEquals(g, stored.tsMs)

        // Persisted snapped, not merely returned snapped.
        val persisted = repo.loggedDosesInRange(g - 10 * GRID, g + 10 * GRID).single()
        assertEquals(expected, persisted.tsMs)

        // requireGrid(expected) passing is the proof the snapped ts is a legal sample key.
        repo.recordSteps(expected, tzOffsetMin = 0, steps = 1, nowMs = raw)
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
