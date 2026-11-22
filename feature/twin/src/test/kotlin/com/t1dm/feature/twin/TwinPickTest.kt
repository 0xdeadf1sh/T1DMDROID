package com.t1dm.feature.twin

import com.t1dm.core.model.SensorPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwinPickTest {

    private val body: TwinBody by lazy {
        val f = File("src/main/assets/twin_body.bin").takeIf { it.exists() }
            ?: File("feature/twin/src/main/assets/twin_body.bin")
        TwinBody.parse(f.readBytes())
    }

    private val quad = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f)
    private val quadIdx = shortArrayOf(0, 1, 2, 1, 3, 2)

    @Test fun `a ray through a triangle returns barycentrics that rebuild the hit point`() {
        val hit = TwinPick.ray(quad, quadIdx, floatArrayOf(0.2f, 0.3f, 5f), floatArrayOf(0f, 0f, -1f))
        assertNotNull(hit)
        val out = FloatArray(6)
        TwinPick.anchor(quad, quadIdx, hit!!.tri, hit.u, hit.v, out)
        assertEquals(0.2f, out[0], 1e-5f)
        assertEquals(0.3f, out[1], 1e-5f)
        assertEquals(0f, out[2], 1e-5f)
        assertEquals(1f, out[5], 1e-5f)
    }

    @Test fun `a ray that misses, or points away, finds nothing`() {
        assertNull(TwinPick.ray(quad, quadIdx, floatArrayOf(2f, 2f, 5f), floatArrayOf(0f, 0f, -1f)))
        assertNull(TwinPick.ray(quad, quadIdx, floatArrayOf(0.2f, 0.3f, 5f), floatArrayOf(0f, 0f, 1f)))
    }

    @Test fun `the nearest of two layers wins`() {
        val two = quad + floatArrayOf(0f, 0f, -1f, 1f, 0f, -1f, 0f, 1f, -1f)
        val idx = shortArrayOf(4, 5, 6, 0, 1, 2)
        val hit = TwinPick.ray(two, idx, floatArrayOf(0.2f, 0.2f, 5f), floatArrayOf(0f, 0f, -1f))
        assertEquals(1, hit!!.tri)
    }

    private fun mid(a: String, b: String): FloatArray {
        val i = body.boneIndex(a) * 3
        val j = body.boneIndex(b) * 3
        return FloatArray(3) { (body.bonePivots[i + it] + body.bonePivots[j + it]) / 2f }
    }

    private fun spotFrom(target: FloatArray, dz: Float): String? {
        val o = floatArrayOf(target[0], target[1], target[2] + dz * 20f)
        val hit = TwinPick.ray(body.neutral, body.indices, o, floatArrayOf(0f, 0f, -dz))!!
        return TwinPick.region(body, SensorPlacement("s", body.topologyHash, hit.tri, hit.u, hit.v))
    }

    @Test fun `the rest body reads back the region a ray lands on`() {
        assertEquals("Upper arm L", spotFrom(mid("upperarm01.L", "lowerarm01.L"), 1f))
        assertEquals("Upper arm R", spotFrom(mid("upperarm01.R", "lowerarm01.R"), 1f))
        assertEquals("Abdomen", spotFrom(mid("root", "spine05"), 1f))
        assertEquals("Lower back", spotFrom(mid("root", "spine05"), -1f))
    }

    @Test fun `a placement from another mesh or out of range is refused`() {
        val ok = SensorPlacement("s", body.topologyHash, 0, 0.2f, 0.2f)
        assertTrue(TwinPick.valid(ok, body))
        assertNull(TwinPick.region(body, ok.copy(meshHash = body.topologyHash + 1)))
        assertNull(TwinPick.region(body, ok.copy(tri = body.indices.size / 3)))
        assertNull(TwinPick.region(body, ok.copy(u = 0.8f, v = 0.4f)))
    }
}
