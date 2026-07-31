package com.t1dm.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.8 (H7) walk bookkeeping. Every assertion here stands for a way the phone can lose history
 * silently, because the epoch this ledger guards is recorded once and never revisited: promote it a
 * moment early and the samples still sitting in the outbox are never offered to the server again.
 */
class ReMirrorLedgerTest {

    private val horizon = 7L * 24 * 60 * 60_000   // DrainConfig.maxAgeMs
    private val epochA = "aaaa1111"
    private val epochB = "bbbb2222"
    private val storeA = "default\u001fhttp://a\u001f100"
    private val storeB = "default\u001fhttp://b\u001f200"

    private val kv = mutableMapOf<String, String>()
    private var oldestQueued: Long? = null

    private fun ledger() = ReMirrorLedger(
        getKv = { kv[it] },
        putKv = { k, v, _ -> kv[k] = v },
        oldestQueuedAtMs = { oldestQueued },
        maxQueueAgeMs = horizon,
    )

    @Test
    fun firstWalkRaisesEventsAndStartsAtTheBeginningOfHistory() = runTest {
        val walk = ledger().resume(epochA, storeA, nowMs = 1_000)

        assertEquals(1_000L, walk.stampMs)
        assertEquals(0L, walk.scalarCursor)
        assertTrue(walk.raiseEvents)
        assertEquals(epochA, kv[ReMirrorKeys.PENDING_EPOCH])
        assertEquals(storeA, kv[ReMirrorKeys.WALK_STORE])
        // Nothing has been earned yet — least of all the epoch the gate records.
        assertNull(kv[ReMirrorKeys.MIRRORED_EPOCH])
    }

    /** The defect this ledger exists for: without a persisted cursor a walk over a month of samples
     *  restarts at ts 0 on every connect and never converges. */
    @Test
    fun resumedWalkKeepsItsStampAndItsBankedCursorAndDoesNotRaiseEventsAgain() = runTest {
        val l = ledger()
        val first = l.resume(epochA, storeA, nowMs = 1_000)
        l.bankEvents(first.stampMs, nowMs = 1_500)
        l.bankScalarCursor(ts = 5_000_000, nowMs = 2_000)

        val resumed = l.resume(epochA, storeA, nowMs = 60_000)

        assertEquals(1_000L, resumed.stampMs)
        assertEquals(5_000_000L, resumed.scalarCursor)
        assertFalse(resumed.raiseEvents)
    }

    /**
     * A pass that enqueued only part of the meal/dose phase and then died banks nothing, so the phase is
     * raised again — whole, under a fresh stamp — rather than resumed atop a half-enqueued one. Skipping
     * it on the strength of a stamp alone leaves the remainder unmentioned until the horizon expires.
     */
    @Test
    fun anUnbankedEventPhaseIsRaisedAgainWholeUnderAFreshStamp() = runTest {
        val l = ledger()
        l.resume(epochA, storeA, nowMs = 1_000)
        l.bankScalarCursor(ts = 5_000_000, nowMs = 2_000)

        val again = l.resume(epochA, storeA, nowMs = 60_000)

        assertTrue(again.raiseEvents)
        assertEquals(60_000L, again.stampMs)
        assertEquals(5_000_000L, again.scalarCursor)
    }

    @Test
    fun aDifferentStoreEpochDiscardsTheCursor() = runTest {
        val l = ledger()
        val first = l.resume(epochA, storeA, nowMs = 1_000)
        l.bankEvents(first.stampMs, nowMs = 1_500)
        l.bankScalarCursor(ts = 5_000_000, nowMs = 2_000)

        val fresh = l.resume(epochB, storeA, nowMs = 3_000)

        assertEquals(0L, fresh.scalarCursor)
        assertTrue(fresh.raiseEvents)
        assertEquals(epochB, kv[ReMirrorKeys.PENDING_EPOCH])
    }

    /**
     * A repointed profile on the SAME epoch discards the cursor as well as the stamp. A matching epoch is
     * NOT evidence that the banked prefix reached this store: the epoch is read once at the head of the
     * pass while the endpoint is resolved per request, so a profile repointed mid-pass drains later pages
     * to the new host and banks them under the old epoch. Keeping the prefix on a matching epoch alone
     * would skip exactly those pages forever on the return.
     */
    @Test
    fun aChangedProfileOnTheSameEpochDiscardsTheStampAndTheCursor() = runTest {
        val l = ledger()
        val first = l.resume(epochA, storeA, nowMs = 1_000)
        l.bankEvents(first.stampMs, nowMs = 1_500)
        l.bankScalarCursor(ts = 5_000_000, nowMs = 2_000)

        val re = l.resume(epochA, storeB, nowMs = 3_000)

        assertEquals(3_000L, re.stampMs)
        assertEquals(0L, re.scalarCursor)
        assertTrue(re.raiseEvents)
    }

    /** …and the discard is durable: returning to the original store does not resurrect the prefix that
     *  drained somewhere else while the walk was in flight. */
    @Test
    fun returningToTheOriginalStoreDoesNotResurrectTheDiscardedCursor() = runTest {
        val l = ledger()
        val first = l.resume(epochA, storeA, nowMs = 1_000)
        l.bankEvents(first.stampMs, nowMs = 1_500)
        l.bankScalarCursor(ts = 5_000_000, nowMs = 2_000)
        l.resume(epochA, storeB, nowMs = 3_000)

        assertEquals(0L, l.resume(epochA, storeA, nowMs = 4_000).scalarCursor)
    }

    /** Past the eviction horizon an INGEST/STATS row's absence stops meaning "sent", so the walk is
     *  re-raised — but the cursor's page-by-page proof does not expire with it. */
    @Test
    fun aWalkPastTheEvictionHorizonIsReRaisedWithItsCursorIntact() = runTest {
        val l = ledger()
        val first = l.resume(epochA, storeA, nowMs = 1_000)
        l.bankEvents(first.stampMs, nowMs = 1_500)
        l.bankScalarCursor(ts = 5_000_000, nowMs = 2_000)

        val re = l.resume(epochA, storeA, nowMs = 1_000 + horizon)

        assertEquals(1_000L + horizon, re.stampMs)
        assertEquals(5_000_000L, re.scalarCursor)
        assertTrue(re.raiseEvents)
    }

    @Test
    fun drainedThroughIgnoresRowsQueuedAfterTheStamp() = runTest {
        val l = ledger()
        oldestQueued = 1_000
        assertFalse(l.drainedThrough(1_000))     // a row exactly as old as the walk is still the walk's
        oldestQueued = 1_001
        assertTrue(l.drainedThrough(1_000))      // only live rows left
        oldestQueued = null
        assertTrue(l.drainedThrough(1_000))
    }

    @Test
    fun deliveredIsRefusedWhileAnyRowOfTheWalkRemains() = runTest {
        val l = ledger()
        l.resume(epochA, storeA, nowMs = 1_000)

        oldestQueued = 1_000
        assertFalse(l.delivered(epochA, storeA, nowMs = 2_000))

        oldestQueued = null
        assertTrue(l.delivered(epochA, storeA, nowMs = 2_000))
    }

    /** An empty queue takes the horizon guard too: a walk whose markers were all age-evicted during an
     *  outage would otherwise read as complete without a single row being examined. */
    @Test
    fun deliveredIsRefusedPastTheHorizonEvenOnAnEmptyQueue() = runTest {
        val l = ledger()
        l.resume(epochA, storeA, nowMs = 1_000)
        oldestQueued = null

        assertFalse(l.delivered(epochA, storeA, nowMs = 1_000 + horizon))
        assertTrue(l.delivered(epochA, storeA, nowMs = 1_000 + horizon - 1))
    }

    /** The endpoint is resolved per request and an outbox row carries no store identity, so a profile
     *  repointed while the walk drained sent the history somewhere this epoch does not name. */
    @Test
    fun deliveredIsRefusedWhenTheStoreMovedUnderTheWalk() = runTest {
        val l = ledger()
        l.resume(epochA, storeA, nowMs = 1_000)
        oldestQueued = null

        assertFalse(l.delivered(epochA, storeB, nowMs = 2_000))
        assertTrue(l.delivered(epochA, storeA, nowMs = 2_000))
    }

    @Test
    fun deliveredIsRefusedForAnEpochThisWalkWasNotRaisedAgainst() = runTest {
        val l = ledger()
        l.resume(epochA, storeA, nowMs = 1_000)
        oldestQueued = null

        assertFalse(l.delivered(epochB, storeA, nowMs = 2_000))
    }

    @Test
    fun deliveredIsRefusedWhenNoWalkHasEverBeenRaised() = runTest {
        oldestQueued = null
        assertFalse(ledger().delivered(epochA, storeA, nowMs = 2_000))
    }
}
