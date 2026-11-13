package com.t1dm.feature.twin

import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinSource
import com.t1dm.core.model.TwinTrait
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwinBodyTest {

    private val body: TwinBody by lazy {
        val f = File("src/main/assets/twin_body.bin").takeIf { it.exists() }
            ?: File("feature/twin/src/main/assets/twin_body.bin")
        TwinBody.parse(f.readBytes())
    }

    private fun scan(vararg kv: Pair<TwinTrait, Double>) =
        TwinScan(1, 0, TwinSource.MOCK, kv.associate { it.first.key to it.second })

    @Test fun `the asset carries every morph the mapping writes`() {
        val wanted = listOf(
            "sex", "age", "weight_up", "weight_down", "muscle_up", "muscle_down", "height_up", "height_down",
        )
        assertEquals(wanted.toSet(), body.morphNames.toSet())
        assertTrue(body.indices.all { (it.toInt() and 0xFFFF) < body.vertexCount })
    }

    @Test fun `zero weights blend to the neutral body`() {
        assertArrayEquals(body.neutral, body.blend(FloatArray(body.morphNames.size)), 0f)
    }

    @Test fun `the neutral stands at a human height`() {
        val h = body.heightCm(FloatArray(body.morphNames.size))
        assertTrue("neutral height $h cm", h in 150f..185f)
    }

    @Test fun `height fits within a centimetre across the range`() {
        for (cm in listOf(150.0, 170.0, 190.0, 205.0)) {
            val w = TwinMorphs.weights(scan(TwinTrait.HEIGHT to cm, TwinTrait.SEX to 0.5), body)
            assertEquals("target $cm", cm.toFloat(), body.heightCm(w), 1f)
        }
    }

    @Test fun `no scan is the neutral, and weights stay in range`() {
        assertTrue(TwinMorphs.weights(null, body).all { it == 0f })
        val w = TwinMorphs.weights(
            scan(
                TwinTrait.SEX to 1.0, TwinTrait.AGE to 90.0, TwinTrait.HEIGHT to 205.0,
                TwinTrait.WEIGHT to 150.0, TwinTrait.MUSCLE to 1.0,
            ),
            body,
        )
        val shape = setOf("weight_up", "weight_down", "muscle_up", "muscle_down")
        body.morphNames.forEachIndexed { i, n -> assertTrue(n, w[i] in if (n in shape) -3f..3f else -1f..1f) }
        assertEquals(3f, w[body.morphIndex("muscle_up")])
        assertEquals(1f, w[body.morphIndex("sex")])
        assertEquals(1f, w[body.morphIndex("age")])
    }
}
