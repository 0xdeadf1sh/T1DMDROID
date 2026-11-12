package com.t1dm.feature.twin

import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinSource
import com.t1dm.core.model.TwinTrait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MockTwinTest {

    @Test fun `every trait is emitted, in range`() {
        val out = MockTwin.next(null, Random(1))
        assertEquals(TwinTrait.entries.map { it.key }.toSet(), out.keys)
        for (t in TwinTrait.entries) {
            val v = out.getValue(t.key)
            assertTrue("${t.key}=$v outside [${t.min}, ${t.max}]", v in t.min..t.max)
        }
    }

    @Test fun `survival falls with horizon`() {
        val out = MockTwin.next(null, Random(2))
        val s = TwinTrait.SURVIVAL.map { out.getValue(it.key) }
        assertTrue("not decreasing: $s", s.zipWithNext().all { (a, b) -> a > b })
        assertTrue(s.first() < 1.0 && s.last() > 0.0)
    }

    @Test fun `a long walk stays in range`() {
        val rng = Random(3)
        var prev: TwinScan? = null
        repeat(5_000) { i ->
            val out = MockTwin.next(prev, rng)
            for (t in TwinTrait.entries) assertTrue(out.getValue(t.key) in t.min..t.max)
            prev = TwinScan(i.toLong(), i.toLong(), TwinSource.MOCK, out)
        }
    }

    @Test fun `older means lower survival`() {
        assertTrue(MockTwin.gompertzSurvival(70.0, 10.0, 20.0) < MockTwin.gompertzSurvival(30.0, 10.0, 20.0))
    }
}
