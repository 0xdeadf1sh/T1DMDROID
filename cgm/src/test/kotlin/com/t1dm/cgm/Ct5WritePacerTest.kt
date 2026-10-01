package com.t1dm.cgm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writes to 0x1002 are WRITE-WITHOUT-RESPONSE: no ATT pacing, no error on loss; ladder retries. */
@OptIn(ExperimentalCoroutinesApi::class)
class Ct5WritePacerTest {

    @Test
    fun `the default ladder writes five times at the vendor's offsets`() = runTest {
        val log = mutableListOf<Long>()
        val pacer = pacer(this) { log += currentTime }
        pacer.enqueue(FRAME, Ct5Constants.DEFAULT_LADDER_MS, expectReply = true)

        settle(Ct5Constants.DEFAULT_LADDER_MS.last() + 1)
        assertEquals(listOf(100L, 900L, 1700L, 2500L, 3300L), log)
    }

    @Test
    fun `the self-check ladder writes three times on its own schedule`() = runTest {
        val log = mutableListOf<Long>()
        val pacer = pacer(this) { log += currentTime }
        pacer.enqueue(FRAME, Ct5Constants.SELF_CHECK_LADDER_MS, expectReply = true)

        settle(Ct5Constants.SELF_CHECK_LADDER_MS.last() + 1)
        assertEquals(listOf(100L, 2900L, 5700L), log)
    }

    @Test
    fun `an acknowledgement stops the ladder where it stands`() = runTest {
        var writes = 0
        val pacer = pacer(this) { writes++ }
        pacer.enqueue(FRAME, Ct5Constants.DEFAULT_LADDER_MS, expectReply = true)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("two rungs by t+1000", 2, writes)

        pacer.acknowledge()
        settle(Ct5Constants.stepTimeoutMs(Ct5Constants.DEFAULT_LADDER_MS))
        assertEquals("no further write after the reply arrived", 2, writes)
    }

    @Test
    fun `a frame written its whole ladder through reports exhaustion once`() = runTest {
        val exhausted = mutableListOf<Int>()
        val pacer = pacer(this, onExhausted = { exhausted += it }) {}
        pacer.enqueue(FRAME, Ct5Constants.DEFAULT_LADDER_MS, expectReply = true)

        // A reply may still be in flight behind the fifth write.
        settle(Ct5Constants.DEFAULT_LADDER_MS.last() + 1)
        assertTrue("exhaustion must not fire before the grace elapses", exhausted.isEmpty())

        settle(Ct5Constants.LADDER_GRACE_MS)
        assertEquals(listOf(FRAME[0].toInt()), exhausted)
    }

    @Test
    fun `a frame that expects no reply expires quietly`() = runTest {
        val exhausted = mutableListOf<Int>()
        val pacer = pacer(this, onExhausted = { exhausted += it }) {}
        // lowPower may legitimately draw nothing at all.
        pacer.enqueue(FRAME, Ct5Constants.DEFAULT_LADDER_MS, expectReply = false)

        settle(Ct5Constants.stepTimeoutMs(Ct5Constants.DEFAULT_LADDER_MS) + 1)
        assertTrue(exhausted.isEmpty())
    }

    @Test
    fun `a write the platform refuses ends the ladder rather than repeating it`() = runTest {
        var attempts = 0
        val rejected = mutableListOf<Int>()
        val pacer = Ct5WritePacer(
            scope = backgroundScope,
            write = { attempts++; false },
            onExhausted = { },
            onWriteRejected = { rejected += it },
        )
        pacer.start()
        pacer.enqueue(FRAME, Ct5Constants.DEFAULT_LADDER_MS, expectReply = true)

        settle(Ct5Constants.stepTimeoutMs(Ct5Constants.DEFAULT_LADDER_MS) + 1)
        // No rung can fix a stack that will not queue the bytes.
        assertEquals(1, attempts)
        assertEquals(listOf(FRAME[0].toInt()), rejected)
    }

    @Test
    fun `queued frames are written one at a time and in order`() = runTest {
        val log = mutableListOf<Pair<Int, Long>>()
        val pacer = pacer(this) { log += (it[0].toInt() and 0xFF) to currentTime }
        // Android permits one outstanding GATT operation at a time.
        pacer.enqueue(byteArrayOf(0x03, 0x03), Ct5Constants.DEFAULT_LADDER_MS, expectReply = true)
        pacer.enqueue(byteArrayOf(0x31, 0x31), Ct5Constants.DEFAULT_LADDER_MS, expectReply = true)

        settle(2 * Ct5Constants.stepTimeoutMs(Ct5Constants.DEFAULT_LADDER_MS) + 1)
        val opcodes = log.map { it.first }
        assertEquals("the first frame's whole ladder precedes the second's", 5, opcodes.indexOf(0x31))
        assertTrue(opcodes.take(5).all { it == 0x03 })
        assertTrue(opcodes.drop(5).all { it == 0x31 })
        assertEquals(log.map { it.second }, log.map { it.second }.sorted().distinct())
    }

    @Test
    fun `closing stops the pacer without writing again`() = runTest {
        var writes = 0
        val pacer = pacer(this) { writes++ }
        pacer.enqueue(FRAME, Ct5Constants.DEFAULT_LADDER_MS, expectReply = true)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, writes)

        pacer.close()
        settle(Ct5Constants.stepTimeoutMs(Ct5Constants.DEFAULT_LADDER_MS))
        assertEquals(1, writes)
    }

    /** `advanceUntilIdle()` does not run a TestScope's background work, where the pacer's pump lives. */
    private fun kotlinx.coroutines.test.TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun pacer(
        scope: kotlinx.coroutines.test.TestScope,
        onExhausted: (Int) -> Unit = {},
        write: (ByteArray) -> Unit,
    ): Ct5WritePacer = Ct5WritePacer(
        scope = scope.backgroundScope,
        write = { write(it); true },
        onExhausted = onExhausted,
        onWriteRejected = { },
    ).also { it.start() }

    private companion object {
        val FRAME = byteArrayOf(0x05, 0x55, 0xAA.toByte(), 0x04)
    }
}
