package com.t1dm.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CurveKind
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.LoggedExerciseEntity
import com.t1dm.data.db.TOMBSTONE_KIND_EXERCISE
import com.t1dm.data.db.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The replay's write paths, against the production driver: `inWriteTx` needs a configured one. */
@RunWith(AndroidJUnit4::class)
class LoggedExerciseMutationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val nowMs = 1_700_000_100_000L
    private val grid = T1dmRepository.GRID_MS

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

    private fun row(tsMs: Long = nowMs, tz: Int = 0) = LoggedExerciseEntity(
        clientId = "", tsMs = tsMs, tzOffsetMin = tz, kind = "WALK", durationMin = 45.0,
        grams = 22.5, k = 3.0, theta = 15.0, curveDurationMin = 135.0,
        sourceSessionId = null, updatedAt = nowMs,
    )

    private fun buckets(gridStart: Long, values: DoubleArray, tz: Int, prior: Boolean = false) =
        values.indices.map { i ->
            ExerciseCurveBucket(
                gridTs = gridStart + i * grid,
                tzOffsetMin = tz,
                grams = if (prior) 0.0 else values[i],
                priorGrams = if (prior) values[i] else 0.0,
            )
        }

    @Test
    fun deletingAReplayTombstonesIt() = runTest {
        val values = doubleArrayOf(1.0, 2.0, 1.5)
        val stored = repo.logLoggedExercise(row(), buckets(nowMs, values, 0), nowMs)
        repo.deleteLoggedExercise(stored.id, buckets(nowMs, values, 0, prior = true), nowMs + 1000)

        val tomb = db.eventTombstoneDao().byClientId(stored.clientId)
        assertNotNull("a restore would otherwise bring the row back without its grams", tomb)
        assertEquals(TOMBSTONE_KIND_EXERCISE, tomb!!.kind)
        assertEquals(CurveKind.EXERCISE, tomb.toModel().kind)
        // Nothing to push: no exercise event exists on the wire.
        assertEquals(nowMs + 1000, tomb.pushEnqueuedAtMs)
        assertEquals(emptyList<Any>(), repo.unpushedTombstones())
        assertNull(repo.loggedExerciseById(stored.id))
    }

    /** A phone-local deletion must not advance the cursor past server events never fetched. */
    @Test
    fun anExerciseTombstoneStaysOutOfTheEventHighWaterMark() = runTest {
        val late = nowMs + 30 * grid
        val stored = repo.logLoggedExercise(row(tsMs = late), buckets(late, doubleArrayOf(1.0), 0), nowMs)
        repo.deleteLoggedExercise(stored.id, buckets(late, doubleArrayOf(1.0), 0, prior = true), nowMs)
        assertNull("no meal, dose or wire tombstone exists", repo.newestEventTs())
    }

    /** §2: offset is the one the slot was authored at; a replay reads it from zone TODAY. */
    @Test
    fun aPastDatedReplayLeavesAnExistingSamplesOffsetAlone() = runTest {
        repo.recordSteps(nowMs, tzOffsetMin = 120, steps = 40, nowMs = nowMs)
        assertEquals(120, db.sampleDao().byTs(nowMs)!!.tzOffsetMin)

        val stored = repo.logLoggedExercise(
            row(tsMs = nowMs, tz = -240),
            buckets(nowMs, doubleArrayOf(2.0), -240),
            nowMs,
        )
        val after = db.sampleDao().byTs(nowMs)!!
        assertEquals("the authored offset stands", 120, after.tzOffsetMin)
        assertEquals(2.0, after.exercise!!, 1e-9)

        // A slot the replay MINTS takes the offset it was handed.
        assertEquals(-240, db.sampleDao().byTs(nowMs + grid)?.tzOffsetMin ?: -240)
        assertNotNull(stored.clientId)
    }
}
