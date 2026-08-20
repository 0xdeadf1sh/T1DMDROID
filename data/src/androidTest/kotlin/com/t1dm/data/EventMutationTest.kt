package com.t1dm.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CurveKind
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.NS_TREATMENT_DEDUP_PREFIX
import com.t1dm.data.db.OutboxKind
import com.t1dm.data.db.OutboxState
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
 * Editing and deleting a logged event (Room v21).
 *
 * A deletion writes a tombstone and removes the event in ONE transaction, and the tombstone is what
 * everything downstream stands on: it is the row a stale redelivery is rejected by, the record
 * hydration refuses against, and the term that keeps the event high-water mark from walking
 * backward. An edit bumps the authoring stamp and records what the dose's action curve looked like
 * BEFORE the change, which is what the dose-history rail's window is derived from.
 *
 * Instrumented against the production [BundledSQLiteDriver], like [TransactionTest], because
 * `inWriteTx` is only reachable through a configured driver.
 */
@RunWith(AndroidJUnit4::class)
class EventMutationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    /** On the five-minute grid, because `logLoggedDose`/`logMeal` are the snap authority. */
    private val nowMs = 1_700_000_100_000L

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

    private fun meal(grams: Double = 45.0, ts: Long = nowMs) = LoggedMealEntity(
        clientId = "", tsMs = ts, grams = grams, gi = 60.0, k = 2.0, theta = 20.0,
        durationMin = 180.0, customCurve = null, tzOffsetMin = 0, note = null, updatedAt = nowMs,
        loggedAtMs = nowMs,
    )

    private fun dose(units: Double = 4.0, ts: Long = nowMs, durationMin: Double = 360.0) =
        LoggedDoseEntity(
            clientId = "", tsMs = ts, kind = DoseKind.BOLUS, units = units, durationMin = durationMin,
            k = null, theta = null, kaPerHour = null, kePerHour = null, customCurve = null,
            tzOffsetMin = 0, note = "NovoRapid", updatedAt = nowMs, loggedAtMs = nowMs,
        )

    /**
     * The delete leaves a tombstone behind, and the tombstone carries a STRICTLY NEWER stamp than
     * the row it retires — even when the clock has not advanced, which `SPEC/invariants.md` §7 names
     * as a real possibility. Without that the server's ordering guard ignores the deletion.
     */
    @Test
    fun deleting_a_dose_leaves_a_tombstone_with_a_strictly_newer_stamp() = runTest {
        val row = repo.logLoggedDose(dose())
        // Deliberately the row's OWN stamp: the clock has not moved.
        val tomb = repo.tombstoneLoggedDose(row.id, nowMs)

        assertNotNull(tomb)
        assertEquals(row.clientId, tomb!!.clientId)
        assertEquals(CurveKind.INSULIN, tomb.kind)
        assertTrue(
            "the tombstone must outrank the row it retires (${tomb.updatedAt} vs ${row.updatedAt})",
            tomb.updatedAt > row.updatedAt,
        )
        assertNull("the event itself is gone", db.loggedDoseDao().byId(row.id))
        assertNotNull(db.eventTombstoneDao().byClientId(row.clientId))
        assertNull("nothing has filed its push yet", db.eventTombstoneDao().byClientId(row.clientId)!!.pushEnqueuedAtMs)
    }

    /**
     * An edit can only reach the Nightscout bridge by RECALLING the mirror the log filed, so the
     * recall has to answer whether there was anything to recall.
     *
     * A bridged treatment freezes its amount into the queued payload and `/api/v1` has no update for
     * one that has landed — and the bridged `created_at` derives from `updatedAt`, which an edit
     * bumps, so re-sending would file a SECOND treatment beside the first rather than replace it.
     * Double-counted insulin in someone's logbook is the failure this answer exists to prevent.
     */
    @Test
    fun an_edited_events_bridged_mirror_is_recalled_only_while_it_is_still_pending() = runTest {
        val row = repo.logLoggedDose(dose())
        repo.enqueue(
            OutboxKind.NIGHTSCOUT,
            "$NS_TREATMENT_DEDUP_PREFIX${row.clientId}",
            byteArrayOf(1),
            nowMs,
            nowMs,
        )

        assertTrue("a pending mirror is recallable", repo.withdrawEditedBridgedTreatment(row.clientId))
        assertFalse("and only once", repo.withdrawEditedBridgedTreatment(row.clientId))
        assertFalse(
            "an event with no mirror at all was never sent one, and is not recallable either",
            repo.withdrawEditedBridgedTreatment("no-such-client"),
        )
    }

    /**
     * A mirror the drainer has already claimed is NOT recalled. Deleting it would not unsend the POST
     * in flight — it would only tell the caller nothing had gone, which is exactly the belief that
     * produces a duplicate treatment.
     */
    @Test
    fun a_mirror_already_claimed_by_the_drainer_is_left_where_it_is() = runTest {
        val row = repo.logLoggedDose(dose())
        val key = "$NS_TREATMENT_DEDUP_PREFIX${row.clientId}"
        val id = repo.enqueue(OutboxKind.NIGHTSCOUT, key, byteArrayOf(1), nowMs, nowMs)
        db.outboxDao().claim(id, OutboxState.PENDING, OutboxState.INFLIGHT)

        assertFalse(repo.withdrawEditedBridgedTreatment(row.clientId))
        assertNotNull("and the row it could not recall is still queued", db.outboxDao().byId(id))
    }

    /**
     * The high-water mark must not move backward on a delete. It does without the tombstone term,
     * and the widened pull window then re-hydrates exactly what was deleted.
     */
    @Test
    fun deleting_the_newest_event_does_not_walk_the_catch_up_cursor_backward() = runTest {
        val older = repo.logLoggedDose(dose(ts = nowMs - 300_000L))
        val newest = repo.logLoggedDose(dose(ts = nowMs))
        assertEquals(newest.tsMs, repo.newestEventTs())

        repo.tombstoneLoggedDose(newest.id, nowMs + 1)

        assertEquals(
            "the mark stays at the deleted event's ts, not the survivor's",
            newest.tsMs,
            repo.newestEventTs(),
        )
        assertNotNull(db.loggedDoseDao().byId(older.id))
    }

    /** A catch-up must not resurrect a deleted event — and must still admit a genuinely newer one. */
    @Test
    fun hydration_refuses_an_event_the_local_record_has_deleted() = runTest {
        val row = repo.logLoggedDose(dose())
        val tomb = repo.tombstoneLoggedDose(row.id, nowMs)!!

        val stale = dose().copy(clientId = row.clientId, updatedAt = tomb.updatedAt - 500)
        assertEquals("a stale redelivery must be refused", -1L, repo.hydrateDoseEvent(stale))
        assertNull(db.loggedDoseDao().byClientId(row.clientId))

        // A version authored AFTER the deletion is a real re-creation and wins.
        val fresh = dose().copy(clientId = row.clientId, updatedAt = tomb.updatedAt + 500)
        assertTrue(repo.hydrateDoseEvent(fresh) > 0)
        assertNotNull(db.loggedDoseDao().byClientId(row.clientId))
    }

    /**
     * The rail's window is the LATER of the pre-edit and post-edit action ends.
     *
     * Reading the post-edit row alone would let a `durationMin` cut from 360 to 30 shorten the block
     * to half an hour while the IOB it invalidated stayed wrong for six — the edits that understate
     * IOB most are exactly the ones that shrink the post-edit window.
     */
    @Test
    fun an_edit_that_shortens_a_dose_keeps_the_original_action_end() = runTest {
        val row = repo.logLoggedDose(dose(durationMin = 360.0))
        val originalEnd = row.tsMs + 360L * 60_000L

        val edited = repo.editLoggedDose(row.copy(durationMin = 30.0), nowMs + 1_000)!!
        assertEquals(nowMs + 1_000, edited.mutatedAtMs)
        assertEquals(originalEnd, edited.mutatedActingUntilMs)
        assertEquals(
            "the rail reads the pre-edit end, not the shortened one",
            originalEnd,
            repo.editedDoseActiveUntilMs(),
        )
        assertEquals(nowMs + 1_000, repo.latestDoseMutationMs())
    }

    /**
     * A DELETED dose still answers the rail. `logged_dose` cannot — the row is gone — so the action
     * end rides the tombstone. Without it the rail can never fire for the case it exists to cover,
     * and a deleted dose silently lowers assumed IOB with nothing between that and a larger bolus.
     */
    @Test
    fun a_deleted_dose_still_reports_when_its_insulin_stops_acting() = runTest {
        val row = repo.logLoggedDose(dose(durationMin = 300.0))
        assertNull("nothing mutated yet", repo.editedDoseActiveUntilMs())

        repo.tombstoneLoggedDose(row.id, nowMs + 1_000)

        assertEquals(row.tsMs + 300L * 60_000L, repo.editedDoseActiveUntilMs())
        assertEquals(nowMs + 1_000, repo.latestDoseMutationMs())
    }

    /** The log-gap mark is pinned by when the phone was TOLD, so retiming forward cannot quiet it. */
    @Test
    fun retiming_a_dose_forward_cannot_move_the_log_gap_mark_later() = runTest {
        val row = repo.logLoggedDose(dose(ts = nowMs - 5 * 3_600_000L))
        val markBefore = repo.latestLoggedInsulinTs()
        assertEquals(row.tsMs, markBefore)

        repo.editLoggedDose(row.copy(tsMs = nowMs), nowMs)

        assertEquals(
            "the mark is the earlier of the claimed ts and when the phone was told",
            markBefore,
            repo.latestLoggedInsulinTs(),
        )
    }

    /** A cosmetic edit moves no curve, so it must invalidate nothing. */
    @Test
    fun a_note_only_edit_is_not_channel_affecting() = runTest {
        val row = repo.logMeal(meal())
        val edited = repo.editLoggedMeal(row.copy(note = "second helping"), nowMs + 1_000)!!
        assertEquals("second helping", edited.note)
        assertEquals(row.grams, edited.grams, 0.0)
        assertEquals(row.clientId, edited.clientId)
        assertEquals("identity survives an edit", row.id, edited.id)
    }
}
