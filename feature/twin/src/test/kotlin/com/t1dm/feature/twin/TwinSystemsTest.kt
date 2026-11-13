package com.t1dm.feature.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TwinSystemsTest {
    private fun asset(name: String): ByteArray =
        (File("src/main/assets/$name").takeIf { it.exists() } ?: File("feature/twin/src/main/assets/$name")).readBytes()

    private val body by lazy { TwinBody.parse(asset("twin_body.bin")) }
    private val systems by lazy { TwinSystem.parse(asset("twin_systems.bin")) }

    @Test fun `vessels, cord and nerves are there`() =
        assertEquals(listOf("vessels", "cord", "nerves"), systems.map { it.name })

    @Test fun `vessels carry arteries and veins, nerves only nerves`() {
        fun classes(name: String) = systems.first { it.name == name }.attrs.filterIndexed { i, _ -> i % 2 == 1 }.toSet()
        assertEquals(setOf(TwinSystem.ARTERY, TwinSystem.VEIN), classes("vessels"))
        assertEquals(setOf(TwinSystem.NERVE), classes("nerves"))
    }

    @Test fun `every system stays within the body's bounds`() {
        val lo = FloatArray(3) { a -> (a until body.neutral.size step 3).minOf { body.neutral[it] } }
        val hi = FloatArray(3) { a -> (a until body.neutral.size step 3).maxOf { body.neutral[it] } }
        for (s in systems) {
            val p = FloatArray(s.vertexCount * 3).also { s.positions(body.neutral, it) }
            for (v in 0 until s.vertexCount) for (a in 0 until 3) {
                assertTrue("${s.name} vertex $v axis $a at ${p[v * 3 + a]}", p[v * 3 + a] in lo[a] - 0.05f..hi[a] + 0.05f)
            }
            assertTrue(s.indices.all { (it.toInt() and 0xFFFF) < s.vertexCount })
        }
    }
}
