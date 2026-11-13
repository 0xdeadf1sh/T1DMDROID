package com.t1dm.feature.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwinOrgansTest {
    private fun asset(name: String): ByteArray =
        (File("src/main/assets/$name").takeIf { it.exists() } ?: File("feature/twin/src/main/assets/$name")).readBytes()

    private val body by lazy { TwinBody.parse(asset("twin_body.bin")) }
    private val organs by lazy { TwinOrgan.parse(asset("twin_organs.bin")) }

    private fun centre(name: String) = FloatArray(3).also { organs.getValue(name).centre(body.neutral, it) }

    private val top get() = (1 until body.neutral.size step 3).maxOf { body.neutral[it] }
    private val floor get() = (1 until body.neutral.size step 3).minOf { body.neutral[it] }

    @Test fun `heart and brain are there`() = assertEquals(setOf("heart", "brain"), organs.keys)

    @Test fun `the heart sits in the chest, on the body's left`() {
        val c = centre("heart")
        val h = (c[1] - floor) / (top - floor)
        assertTrue("heart at $h of stature", h in 0.68f..0.78f)
        assertTrue("heart x ${c[0]}", c[0] > 0f)
    }

    @Test fun `the brain sits in the top of the head`() {
        val c = centre("brain")
        assertTrue("brain ${top - c[1]} dm below the crown", top - c[1] in 0.3f..1.2f)
    }

    /** Largest axis-aligned extent in cm. */
    private fun extentCm(o: TwinOrgan): Float =
        (0 until 3).maxOf { a -> (a until o.pos.size step 3).let { r -> r.maxOf { o.pos[it] } - r.minOf { o.pos[it] } } } * 10f

    @Test fun `organs are life-sized`() {
        val heart = extentCm(organs.getValue("heart"))
        val brain = extentCm(organs.getValue("brain"))
        assertTrue("heart $heart cm", heart in 10f..18f)
        assertTrue("brain $brain cm", brain in 14f..20f)
    }

    @Test fun `the heart has atria and ventricles to squeeze`() {
        val classes = organs.getValue("heart").motion.filterIndexed { i, _ -> i % 4 == 3 }.toSet()
        assertTrue("classes $classes", 0f in classes && 1f in classes)
    }

    @Test fun `every part of the brain is reached by a seed`() {
        val brain = organs.getValue("brain")
        val d = brain.seedDistances(brain.edges())
        for (v in 0 until brain.vertexCount) {
            val nearest = (0 until TwinOrgan.SEEDS).minOf { d[v * TwinOrgan.SEEDS + it] }
            assertTrue("vertex $v unreached", nearest < 10f)
        }
    }
}
