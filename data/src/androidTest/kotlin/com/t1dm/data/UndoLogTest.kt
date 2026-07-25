package com.t1dm.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
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

/**
 * The undo unwind for a just-logged meal/dose ([T1dmRepository.undoLoggedMeal] /
 * [T1dmRepository.undoLoggedDose]): the event row goes, the still-queued push goes with it in the
 * same transaction, and the `logEvents` ticker is bumped so IOB/COB, the curve channels and the
 * dashboard overlay recompute.
 *
 * That ticker is the whole reason these tests exist at this layer. An event delete touches neither
 * `cgm_reading` nor `sample` (§3.1 retired the carb/bolus/basal scalars), and `iobCob` merges exactly
 * those two Flows plus `logEvents` — so a delete that forgets to bump leaves the read-out stale until
 * the next CGM reading, silently, with nothing on screen to say the undo half-worked.
 *
 * Instrumented against the production [BundledSQLiteDriver], like [TransactionTest], because
 * `inWriteTx` is only reachable through a configured driver.
 */
@RunWith(AndroidJUnit4::class)
class UndoLogTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val nowMs = 1_700_000_000_000L

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

    private fun meal(grams: Double = 45.0) = LoggedMealEntity(
        clientId = "", tsMs = nowMs, grams = grams, gi = 60.0, k = 2.0, theta = 20.0,
        durationMin = 180.0, customCurve = null, tzOffsetMin = 0, note = null, updatedAt = nowMs,
    )

    private fun dose(units: Double = 4.0) = LoggedDoseEntity(
        clientId = "", tsMs = nowMs, kind = DoseKind.BOLUS, units = units, durationMin = 360.0,
        k = null, theta = null, kaPerHour = null, kePerHour = null, customCurve = null,
        tzOffsetMin = 0, note = "NovoRapid", updatedAt = nowMs,
    )

    /** Enqueue a push the way `OutboxEnqueuer` does, under the same deterministic dedupKey. */
    private suspend fun enqueuePush(kind: OutboxKind, dedupKey: String): Long =
        repo.enqueue(kind, dedupKey, ByteArray(0), nowMs)

    @Test
    fun undoingAMealRemovesTheRowTheQueuedPushAndBumpsTheTicker() = runTest {
        val persisted = repo.logMeal(meal())
        val dedupKey = "meal:${persisted.clientId}"
        val outboxId = enqueuePush(OutboxKind.MEAL, dedupKey)
        val tickAfterLog = repo.logEvents.value

        assertEquals(1, repo.loggedMealsInRange(0, Long.MAX_VALUE).size)
        assertNotNull(db.outboxDao().byId(outboxId))

        val outcome = repo.undoLoggedMeal(persisted.id, outboxId, dedupKey)

        assertEquals(PushWithdrawal.WITHDRAWN, outcome)
        assertEquals(emptyList<LoggedMealEntity>(), repo.loggedMealsInRange(0, Long.MAX_VALUE))
        assertNull("the queued PUT /v1/meals must be gone", db.outboxDao().byId(outboxId))
        assertEquals(0, db.outboxDao().count())
        assertTrue(
            "logEvents must advance or IOB/COB and the overlay stay stale until the next reading",
            repo.logEvents.value > tickAfterLog,
        )
    }

    @Test
    fun undoingADoseRemovesTheRowTheQueuedPushAndBumpsTheTicker() = runTest {
        val persisted = repo.logLoggedDose(dose())
        val dedupKey = "dose:${persisted.clientId}"
        val outboxId = enqueuePush(OutboxKind.DOSE, dedupKey)
        val tickAfterLog = repo.logEvents.value

        assertEquals(nowMs, repo.latestLoggedInsulinTs())

        val outcome = repo.undoLoggedDose(persisted.id, outboxId, dedupKey)

        assertEquals(PushWithdrawal.WITHDRAWN, outcome)
        assertEquals(emptyList<LoggedDoseEntity>(), repo.loggedDosesInRange(0, Long.MAX_VALUE))
        assertNull(db.outboxDao().byId(outboxId))
        assertTrue(repo.logEvents.value > tickAfterLog)
        // §3.6-F/-C: the IOB provenance mark moves back with the row, never lags behind it — a stale
        // `latestLoggedInsulinTs` would keep `Rails.mandatoryConfirmation` quiet about a gap that now
        // exists, i.e. fail OPEN.
        assertNull(repo.latestLoggedInsulinTs())
    }

    /**
     * The drained case: `QueueDrainer` DELETEs on HTTP success, so an already-sent push is simply
     * absent. The local row must still go, and the outcome must say the server keeps its copy rather
     * than reporting a clean unwind.
     */
    @Test
    fun undoAfterThePushDrainedStillRemovesTheLocalRowAndSaysSo() = runTest {
        val persisted = repo.logLoggedDose(dose())
        val dedupKey = "dose:${persisted.clientId}"
        val outboxId = enqueuePush(OutboxKind.DOSE, dedupKey)
        db.outboxDao().delete(outboxId) // what a successful drain does

        val outcome = repo.undoLoggedDose(persisted.id, outboxId, dedupKey)

        assertEquals(PushWithdrawal.ALREADY_SENT, outcome)
        assertEquals(emptyList<LoggedDoseEntity>(), repo.loggedDosesInRange(0, Long.MAX_VALUE))
    }

    /**
     * SQLite recycles rowids. If our push drained and an unrelated one inherited its id, the undo must
     * not delete the stranger — the dedupKey cross-check is what makes delete-by-id safe here.
     */
    @Test
    fun undoNeverDeletesAPushThatMerelyInheritedTheRowid() = runTest {
        val persisted = repo.logMeal(meal())
        val staleOutboxId = enqueuePush(OutboxKind.MEAL, "meal:${persisted.clientId}")
        db.outboxDao().delete(staleOutboxId)
        val strangerId = enqueuePush(OutboxKind.DOSE, "dose:someone-else")

        val outcome = repo.undoLoggedMeal(persisted.id, staleOutboxId, "meal:${persisted.clientId}")

        assertEquals(PushWithdrawal.ALREADY_SENT, outcome)
        assertNotNull("an unrelated push must survive", db.outboxDao().byId(strangerId))
    }

    /** The `insulin/types` surface enqueues nothing at all, so its undo is purely local — and total. */
    @Test
    fun undoWithNoQueuedPushIsTotal() = runTest {
        val persisted = repo.logLoggedDose(dose())

        val outcome = repo.undoLoggedDose(persisted.id, outboxId = null, dedupKey = null)

        assertEquals(PushWithdrawal.NEVER_QUEUED, outcome)
        assertEquals(emptyList<LoggedDoseEntity>(), repo.loggedDosesInRange(0, Long.MAX_VALUE))
    }

    /** An INFLIGHT push is a genuine race: the row is withdrawn, but the PUT may already have landed. */
    @Test
    fun undoOfAnInflightPushReportsTheRace() = runTest {
        val persisted = repo.logLoggedDose(dose())
        val dedupKey = "dose:${persisted.clientId}"
        val outboxId = enqueuePush(OutboxKind.DOSE, dedupKey)
        db.outboxDao().reschedule(outboxId, OutboxState.INFLIGHT, attempts = 1, nextAttemptMs = nowMs)

        val outcome = repo.undoLoggedDose(persisted.id, outboxId, dedupKey)

        assertEquals(PushWithdrawal.RACED, outcome)
        assertNull(db.outboxDao().byId(outboxId))
    }
}
