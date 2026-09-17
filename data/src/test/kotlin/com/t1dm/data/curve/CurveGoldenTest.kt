package com.t1dm.data.curve

import com.t1dm.core.nativecore.StubNativeCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The JVM stub and the Kotlin GI mapping against `T1DMSIM`, via the Rust core's curve fixture. */
class CurveGoldenTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.classLoader.getResourceAsStream("curve_golden.json")) {
            "curve_golden.json missing from the test classpath (data/build.gradle.kts wires it in)"
        }.bufferedReader().readText(),
    ).jsonObject

    private val stub = StubNativeCore()

    private fun cases(key: String) = golden.getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.d(key: String) = getValue(key).jsonPrimitive.double

    private fun JsonObject.values() = getValue("values").jsonArray.map { it.jsonPrimitive.double }

    private fun assertClose(want: List<Double>, got: List<Double>, what: String) {
        assertEquals("$what length", want.size, got.size)
        want.zip(got).forEachIndexed { i, (w, g) -> assertEquals("$what[$i]", w, g, 1e-12) }
    }

    @Test
    fun stubGammaMatchesSimulator() = cases("gamma").forEach {
        assertClose(it.values(), stub.gamma(it.d("total"), it.d("k"), it.d("theta"), it.d("dur")), "gamma")
    }

    @Test
    fun stubBatemanMatchesSimulator() = cases("bateman").forEach {
        assertClose(it.values(), stub.bateman(it.d("total"), it.d("dur"), it.d("ka"), it.d("ke")), "bateman")
    }

    @Test
    fun stubBolusPkMatchesSimulator() = cases("bolus_pk").forEach {
        val pk = stub.bolusPkForDose(it.d("dose"), it.d("base_k"), it.d("base_theta"), it.d("base_dia_h"))
        assertClose(listOf(it.d("k"), it.d("theta"), it.d("dur")), listOf(pk.k, pk.theta, pk.durationMin), "bolus_pk")
    }

    @Test
    fun giGammaMatchesSimulator() = cases("gi_gamma").forEach {
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(it.d("gi"))
        assertClose(listOf(it.d("k"), it.d("theta"), it.d("dur")), listOf(k, theta, dur), "gi_gamma")
    }
}
