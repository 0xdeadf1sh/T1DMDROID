package com.t1dm.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.8 (H7) walk bookkeeping. */
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
        assertNull(kv[ReMirrorKeys.MIRRORED_EPOCH])
    }

    /** Without a persisted cursor a walk restarts at ts 0 on every connect and never converges. */
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

    /** Matching epoch isn't proof of a banked prefix: epoch reads once/pass, endpoint/request. */
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

    @Test
    fun returningToTheOriginalStoreDoesNotResurrectTheDiscardedCursor() = runTest {
        val l = ledger()
        val first = l.resume(epochA, storeA, nowMs = 1_000)
        l.bankEvents(first.stampMs, nowMs = 1_500)
        l.bankScalarCursor(ts = 5_000_000, nowMs = 2_000)
        l.resume(epochA, storeB, nowMs = 3_000)

        assertEquals(0L, l.resume(epochA, storeA, nowMs = 4_000).scalarCursor)
    }

    /** Past the horizon a missing row stops meaning "sent"; the cursor's proof doesn't expire. */
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
        assertFalse(l.drainedThrough(1_000))     // a row as old as the walk is still the walk's
        oldestQueued = 1_001
        assertTrue(l.drainedThrough(1_000))
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

    /** A walk whose markers were all age-evicted would otherwise read as complete, unexamined. */
    @Test
    fun deliveredIsRefusedPastTheHorizonEvenOnAnEmptyQueue() = runTest {
        val l = ledger()
        l.resume(epochA, storeA, nowMs = 1_000)
        oldestQueued = null

        assertFalse(l.delivered(epochA, storeA, nowMs = 1_000 + horizon))
        assertTrue(l.delivered(epochA, storeA, nowMs = 1_000 + horizon - 1))
    }

    /** An outbox row carries no store identity, so a repoint mid-walk sent history elsewhere. */
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
