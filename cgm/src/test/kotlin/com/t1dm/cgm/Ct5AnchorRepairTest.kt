package com.t1dm.cgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The witness that re-dates a wear. Wrong here moves every reading of that wear. */
class Ct5AnchorRepairTest {

    private val bind = 1_755_000_000_000L
    private val interval = Ct5Constants.SAMPLE_INTERVAL_MS

    /** A frame carrying sample [id], arriving [lateMs] after the sample it carries. */
    private fun arrival(id: Int, lateMs: Long = 0L) = Ct5AnchorRepair.Arrival(
        rxWallMs = bind + id * interval + lateMs,
        minFromStart = id * Ct5Constants.SAMPLE_INTERVAL_MIN,
    )

    private fun healthy(count: Int, lateMs: Long = 0L) = (1..count).map { arrival(it, lateMs) }

    @Test
    fun `a healthy wear's arrivals name the bind instant exactly`() {
        assertEquals(bind, Ct5AnchorRepair.anchorFrom(healthy(200)))
    }

    @Test
    fun `a constant delivery lag shifts the answer by exactly that lag and no more`() {
        assertEquals(bind + 4_000L, Ct5AnchorRepair.anchorFrom(healthy(200, lateMs = 4_000L)))
    }

    /**
     * The case this exists for. A stopped counter repeats one index while the clock runs on, so its
     * arrivals imply a bind instant that walks forward without limit. They must not carry the answer.
     */
    @Test
    fun `arrivals from a stopped counter cannot move the answer`() {
        val stuck = 200
        val frozen = (1..80).map {
            Ct5AnchorRepair.Arrival(
                rxWallMs = bind + stuck * interval + it * interval,
                minFromStart = stuck * Ct5Constants.SAMPLE_INTERVAL_MIN,
            )
        }
        assertEquals(bind, Ct5AnchorRepair.anchorFrom(healthy(stuck) + frozen))
    }

    /**
     * The poisoning this exists to survive. A stopped counter repeats ONE index for as long as the
     * sensor is worn, and every one of those frames is written to the witness store, so counting
     * frames would let that index outvote the whole wear and reproduce the very anchor being undone.
     */
    @Test
    fun `a stopped counter cannot outvote the wear however many frames it sends`() {
        val stuck = 200
        val frozen = (1..5_000).map {
            Ct5AnchorRepair.Arrival(
                rxWallMs = bind + stuck * interval + it * interval,
                minFromStart = stuck * Ct5Constants.SAMPLE_INTERVAL_MIN,
            )
        }
        assertEquals(bind, Ct5AnchorRepair.anchorFrom(healthy(stuck) + frozen))
    }

    /** One index, one vote: a retransmission is not a second witness either. */
    @Test
    fun `repeats of one index count once, at its earliest arrival`() {
        val once = healthy(200)
        val withRepeats = once + once.map { it.copy(rxWallMs = it.rxWallMs + 30 * 60_000L) }
        assertEquals(bind, Ct5AnchorRepair.anchorFrom(withRepeats))
        assertEquals(
            "a wear of repeats is still one wear's worth of witnesses",
            Ct5AnchorRepair.anchorFrom(once),
            Ct5AnchorRepair.anchorFrom(withRepeats),
        )
    }

    @Test
    fun `a wear of one repeated index is not enough witnesses at all`() {
        val onlyFrozen = (1..5_000).map {
            Ct5AnchorRepair.Arrival(rxWallMs = bind + it * interval, minFromStart = 600)
        }
        assertNull("one index is one witness, whatever it is repeated", Ct5AnchorRepair.anchorFrom(onlyFrozen))
    }

    /** A minority of nonsense must not drag it either, which is why this is a median. */
    @Test
    fun `a run of wild arrivals does not drag the answer`() {
        val wild = (1..40).map { Ct5AnchorRepair.Arrival(rxWallMs = bind + it * 999_999L, minFromStart = 0) }
        assertEquals(bind, Ct5AnchorRepair.anchorFrom(healthy(200) + wild))
    }

    /** Witnesses that disagree are describing different things, and no single anchor is the answer.
     *  `null` here means DELETE NOTHING. */
    @Test
    fun `witnesses that disagree name nothing`() {
        val half = Ct5AnchorRepair.MIN_WITNESSES
        val hereAndThere = (1..half).map { arrival(it) } +
            (half + 1..2 * half).map { arrival(it, lateMs = 10 * 60_000L) }
        assertNull("a 10-minute split is not agreement", Ct5AnchorRepair.anchorFrom(hereAndThere))
    }

    @Test
    fun `witnesses agreeing inside one sample interval still name it`() {
        val spread = (1..200).map { arrival(it, lateMs = (it % 2) * (interval - 1)) }
        assertNotNull(Ct5AnchorRepair.anchorFrom(spread))
    }

    @Test
    fun `too few witnesses name nothing rather than guessing`() {
        for (n in 0 until Ct5AnchorRepair.MIN_WITNESSES) {
            assertNull("$n arrivals", Ct5AnchorRepair.anchorFrom(healthy(n)))
        }
        assertEquals(bind, Ct5AnchorRepair.anchorFrom(healthy(Ct5AnchorRepair.MIN_WITNESSES)))
    }

    @Test
    fun `a negative sample index is not a witness`() {
        val bad = List(Ct5AnchorRepair.MIN_WITNESSES) { Ct5AnchorRepair.Arrival(bind, minFromStart = -1) }
        assertNull(Ct5AnchorRepair.anchorFrom(bad))
    }

    /** Real deliveries scatter over one sample interval; the answer must stay inside that scatter. */
    @Test
    fun `ordinary jitter leaves the answer within one sample interval of the truth`() {
        val jittered = (1..300).map { arrival(it, lateMs = (it * 37L) % interval) }
        val got = requireNotNull(Ct5AnchorRepair.anchorFrom(jittered))
        assertTrue("off by ${got - bind} ms", got - bind in 0 until interval)
    }
}
