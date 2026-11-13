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
            "stature", "mass", "age", "male", "male_stature", "male_mass", "male_age", "muscle_up", "muscle_down",
        )
        assertEquals(wanted.toSet(), body.morphNames.toSet())
        assertTrue(body.indices.all { (it.toInt() and 0xFFFF) < body.vertexCount })
    }

    @Test fun `every vertex is skinned by weights summing to one, on bones that exist`() {
        for (v in 0 until body.vertexCount) {
            val sum = (0 until 4).sumOf { body.skinWeights[v * 4 + it].toDouble() }
            assertEquals("vertex $v", 1.0, sum, 1e-4)
            for (i in 0 until 4) assertTrue((body.skinBones[v * 4 + i].toInt() and 0xFF) < body.boneCount)
        }
        assertTrue(body.boneParents.withIndex().all { (i, p) -> p < i })
    }

    @Test fun `left bones sit on the body's left, at +x`() {
        val p = body.pivots(FloatArray(body.morphNames.size))
        for (name in listOf("clavicle", "upperarm01", "upperleg01", "foot")) {
            val l = body.boneIndex("$name.L")
            val r = body.boneIndex("$name.R")
            assertTrue("$name: L x ${p[l * 3]}, R x ${p[r * 3]}", p[l * 3] > 0f && p[r * 3] < 0f)
        }
        assertTrue(p[body.boneIndex("head") * 3 + 1] > p[body.boneIndex("spine01") * 3 + 1])
    }

    @Test fun `the rest pose skins to the body unchanged`() {
        val rest = body.blend(FloatArray(body.morphNames.size))
        val skin = TwinSkin(body)
        skin.pose(body.pivots(FloatArray(body.morphNames.size)), FloatArray(body.boneCount * 3), FloatArray(3))
        val out = FloatArray(rest.size)
        skin.apply(rest, out)
        assertArrayEquals(rest, out, 1e-4f)
    }

    @Test fun `a head turn moves the head and leaves the feet`() {
        val w = FloatArray(body.morphNames.size)
        val rest = body.blend(w)
        val rot = FloatArray(body.boneCount * 3).also { it[body.boneIndex("neck01") * 3 + 1] = 0.5f }
        val skin = TwinSkin(body)
        skin.pose(body.pivots(w), rot, FloatArray(3))
        val out = FloatArray(rest.size)
        skin.apply(rest, out)
        val headY = body.pivots(w)[body.boneIndex("head") * 3 + 1]
        val nose = (0 until body.vertexCount).filter { rest[it * 3 + 1] > headY }.maxBy { rest[it * 3 + 2] } * 3
        val sole = (0 until body.vertexCount).minBy { rest[it * 3 + 1] } * 3
        assertTrue("nose moved ${out[nose] - rest[nose]} dm", kotlin.math.abs(out[nose] - rest[nose]) > 0.2f)
        assertEquals(rest[sole], out[sole], 1e-5f)
    }

    @Test fun `every idle clip names bones the asset has`() {
        val idle = TwinIdle(body)
        idle.step(t = 1f, dt = 0.016f, still = false, idleNow = true, toFace = 0f)
        assertTrue(idle.rot.any { it != 0f })
    }

    @Test fun `zero weights blend to the neutral body`() {
        assertArrayEquals(body.neutral, body.blend(FloatArray(body.morphNames.size)), 0f)
    }

    @Test fun `the neutral stands at a human height`() {
        val h = body.heightCm(FloatArray(body.morphNames.size))
        assertTrue("neutral height $h cm", h in 150f..185f)
    }

    @Test fun `height fits within a centimetre across sex, mass and age`() {
        for (cm in listOf(145.0, 170.0, 190.0, 205.0)) for (sex in listOf(0.0, 1.0)) for (kg in listOf(45.0, 140.0)) {
            val w = TwinMorphs.weights(
                scan(TwinTrait.HEIGHT to cm, TwinTrait.SEX to sex, TwinTrait.WEIGHT to kg, TwinTrait.AGE to 70.0),
                body,
            )
            assertEquals("target $cm sex $sex $kg kg", cm.toFloat(), body.heightCm(w), 1f)
        }
    }

    @Test fun `no scan is the neutral, and the reference person is too`() {
        assertTrue(TwinMorphs.weights(null, body).all { it == 0f })
        val ref = TwinMorphs.weights(
            scan(TwinTrait.SEX to 0.5, TwinTrait.AGE to 40.0, TwinTrait.WEIGHT to 70.0, TwinTrait.MUSCLE to 0.5),
            body,
        )
        assertTrue(ref.all { it == 0f })
    }

    @Test fun `muscle stays on MakeHuman's own range`() {
        val w = TwinMorphs.weights(scan(TwinTrait.MUSCLE to 1.0), body)
        assertEquals(1f, w[body.morphIndex("muscle_up")])
        assertEquals(0f, w[body.morphIndex("muscle_down")])
    }

    @Test fun `more mass widens the waist`() {
        fun waistWidth(kg: Double): Float {
            val p = body.blend(TwinMorphs.weights(scan(TwinTrait.HEIGHT to 175.0, TwinTrait.WEIGHT to kg), body))
            val ys = (1 until p.size step 3).map { p[it] }
            val lo = ys.min()
            val hi = ys.max()
            val band = (0 until body.vertexCount).filter {
                val y = (p[it * 3 + 1] - lo) / (hi - lo)
                y in 0.58f..0.64f && kotlin.math.abs(p[it * 3]) < 0.15f * (hi - lo)
            }
            return band.maxOf { p[it * 3] } - band.minOf { p[it * 3] }
        }
        assertTrue(waistWidth(110.0) > waistWidth(60.0) * 1.15f)
    }
}
