package com.t1dm.feature.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FaceBakeTest {
    private val body: TwinBody by lazy {
        val f = File("src/main/assets/twin_body.bin").takeIf { it.exists() }
            ?: File("feature/twin/src/main/assets/twin_body.bin")
        TwinBody.parse(f.readBytes())
    }
    private val n = 400
    private val size = 128

    /** A face seen flat-on whose landmarks sit at their own UVs, over a red/green gradient. */
    private fun shot(brightness: Float = 1f): FaceShot {
        val pts = FloatArray(478 * 3)
        for (v in 0 until 468) {
            pts[v * 3] = body.canonUv[v * 2]
            pts[v * 3 + 1] = 1f - body.canonUv[v * 2 + 1]
        }
        val px = IntArray(n * n) { i ->
            val r = ((i % n) * 255 / n * brightness).toInt()
            val g = ((i / n) * 255 / n * brightness).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or (128 * brightness).toInt()
        }
        return FaceShot(px, n, n, pts)
    }

    private fun bake(vararg shots: FaceShot) = FaceBake(body.canonUv, body.canonTris, size).bake(shots.toList())

    @Test fun `a texel takes the image colour under its UV`() {
        val tex = bake(shot())
        var checked = 0
        for (y in 0 until size) for (x in 0 until size) {
            val c = tex[y * size + x]
            if (c ushr 24 != 255) continue
            assertEquals("r at $x,$y", (x + 0.5f) / size * 255f, (c shr 16 and 0xFF).toFloat(), 4f)
            assertEquals("g at $x,$y", (y + 0.5f) / size * 255f, (c shr 8 and 0xFF).toFloat(), 4f)
            checked++
        }
        assertTrue("opaque texels $checked", checked > size * size / 4)
    }

    @Test fun `alpha is zero off the face and fades in at its outline`() {
        val tex = bake(shot())
        assertEquals(0, tex[0] ushr 24)
        val alphas = tex.map { it ushr 24 }.toSet()
        assertTrue(alphas.any { it in 1..254 })
    }

    @Test fun `a darker second shot is gained up to match the first`() {
        val one = bake(shot())
        val two = bake(shot(), shot(0.8f))
        for (i in one.indices) {
            if (one[i] ushr 24 != 255) continue
            assertEquals((one[i] shr 16 and 0xFF).toFloat(), (two[i] shr 16 and 0xFF).toFloat(), 4f)
        }
    }
}
