package com.t1dm.feature.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class FaceQualityTest {
    private val n = 400

    private val face = FaceFrame(
        FloatArray(478 * 3).also { p ->
            fun put(i: Int, x: Float, y: Float) {
                p[i * 3] = x
                p[i * 3 + 1] = y
            }
            put(FaceQuality.CHEEK_L, 0.3f, 0.5f)
            put(FaceQuality.CHEEK_R, 0.7f, 0.5f)
            put(FaceQuality.FOREHEAD_TOP, 0.5f, 0.25f)
            put(FaceQuality.CHIN, 0.5f, 0.8f)
            put(FaceQuality.FOREHEAD, 0.5f, 0.3f)
            put(FaceQuality.BRIDGE, 0.5f, 0.42f)
            put(FaceQuality.CHEEK_IN_R, 0.38f, 0.55f)
            put(FaceQuality.CHEEK_IN_L, 0.62f, 0.55f)
            put(FaceQuality.EYE_R_TOP, 0.42f, 0.40f)
            put(FaceQuality.EYE_R_BOTTOM, 0.42f, 0.44f)
            put(FaceQuality.EYE_L_TOP, 0.58f, 0.40f)
            put(FaceQuality.EYE_L_BOTTOM, 0.58f, 0.44f)
            put(FaceQuality.EYE_OUT_R, 0.36f, 0.42f)
            put(FaceQuality.EYE_OUT_L, 0.64f, 0.42f)
            put(FaceQuality.LIP_UP, 0.5f, 0.66f)
            put(FaceQuality.LIP_LOW, 0.5f, 0.68f)
        },
        n, n,
    )

    /** Skin-toned frame with per-pixel noise; [paint] overrides (x, y fractions) → RGB. */
    private fun image(noise: Int = 10, paint: (Float, Float) -> Triple<Int, Int, Int>? = { _, _ -> null }): IntArray {
        val rnd = Random(7)
        return IntArray(n * n) { i ->
            val x = (i % n) / n.toFloat()
            val y = (i / n) / n.toFloat()
            val (r, g, b) = paint(x, y) ?: Triple(200, 150, 120)
            val d = if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0
            fun c(v: Int) = (v + d).coerceIn(0, 255)
            (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
    }

    private fun issue(px: IntArray) = FaceQuality(px, n, n, face).measure().issue()

    private fun scaled(k: Float) = Triple((200 * k).toInt(), (150 * k).toInt(), (120 * k).toInt())

    @Test fun `an even, sharp, bare face passes`() = assertNull(issue(image()))

    @Test fun `a dim face is too dark`() = assertEquals(FaceIssue.DARK, issue(image { _, _ -> scaled(0.25f) }))

    @Test fun `a blown-out face is too bright`() =
        assertEquals(FaceIssue.BRIGHT, issue(image(noise = 0) { _, _ -> Triple(255, 255, 255) }))

    @Test fun `a bright background behind a dim face is backlit`() {
        val px = image { x, y -> if (x in 0.28f..0.72f && y in 0.22f..0.82f) scaled(0.42f) else Triple(250, 250, 250) }
        assertEquals(FaceIssue.BACKLIT, issue(px))
    }

    @Test fun `light from one side is uneven`() =
        assertEquals(FaceIssue.UNEVEN, issue(image { x, _ -> if (x < 0.5f) scaled(0.4f) else null }))

    @Test fun `a frame across the nose bridge reads as glasses`() {
        val px = image { x, y -> if (x in 0.3f..0.7f && y in 0.415f..0.425f) Triple(20, 20, 20) else null }
        assertEquals(FaceIssue.GLASSES, issue(px))
    }

    @Test fun `dark lenses read as glasses`() {
        val px = image { x, y -> if (y in 0.37f..0.47f && (x in 0.38f..0.46f || x in 0.54f..0.62f)) Triple(25, 25, 30) else null }
        assertEquals(FaceIssue.GLASSES, issue(px))
    }

    @Test fun `a light-blue mask covers the lower face`() =
        assertEquals(FaceIssue.COVERED, issue(image { _, y -> if (y > 0.6f) Triple(120, 160, 230) else null }))

    @Test fun `a dark beard does not count as covered`() =
        assertNull(issue(image { _, y -> if (y > 0.6f) Triple(60, 45, 35) else null }))

    @Test fun `a flat, textureless face is blurry`() = assertEquals(FaceIssue.BLUR, issue(image(noise = 0)))
}
