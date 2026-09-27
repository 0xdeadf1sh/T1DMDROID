package com.t1dm.data.meals

import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.core.nativecore.StubNativeCore
import com.t1dm.data.curve.CurveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsulinPkCurveTest {

    private val dispatchers = DefaultT1dmDispatchers(
        main = Dispatchers.Unconfined,
        default = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
        inference = Dispatchers.Unconfined,
    )
    private val engine = CurveEngine(StubNativeCore(), dispatchers)

    private fun bolus(k: Double?, theta: Double?, durationMin: Double) =
        InsulinType(0L, "t", InsulinKind.BOLUS, durationMin, k = k, theta = theta)

    private fun basal(ka: Double, ke: Double, durationMin: Double) =
        InsulinType(0L, "t", InsulinKind.BASAL, durationMin, kaPerHour = ka, kePerHour = ke)

    private suspend fun catalogBasal(label: String): InsulinType {
        val spec = engine.presetCatalog().first { it.label == label }
        return basal(spec.kaPerHour, spec.kePerHour, spec.actionMin)
    }

    private suspend fun encodes(type: InsulinType, units: Double = 8.0) =
        encodesDose(pkCurveOf(engine, type, units), units)

    @Test
    fun `a zero theta encodes no insulin`() = runTest {
        assertFalse(encodes(bolus(k = 2.0, theta = 0.0, durationMin = 300.0)))
    }

    @Test
    fun `a window shorter than one step encodes no insulin`() = runTest {
        assertFalse(encodes(bolus(k = 2.0, theta = 30.0, durationMin = 4.0)))
        assertFalse(encodes(basal(ka = 0.5, ke = 0.05, durationMin = 5.0)))
    }

    @Test
    fun `an overflowing shape encodes no insulin`() = runTest {
        assertFalse(encodes(bolus(k = 1000.0, theta = 30.0, durationMin = 300.0)))
    }

    @Test
    fun `a flat drawn curve encodes no insulin`() = runTest {
        val flat = InsulinType(0L, "t", InsulinKind.BOLUS, 300.0, customCurve = List(60) { 0.0 })
        assertFalse(encodes(flat))
    }

    @Test
    fun `builtins and a sane custom gamma encode their dose`() = runTest {
        assertTrue(encodes(bolus(k = null, theta = null, durationMin = 240.0)))
        assertTrue(encodes(bolus(k = 2.0, theta = 30.0, durationMin = 300.0)))
        assertTrue(encodes(catalogBasal(InsulinController.BUILTIN_PRESETS.first { it.first == "Lantus" }.second)))
    }

    @Test
    fun `action past the dose lookback is refused, the longest builtin is not`() = runTest {
        assertFalse(encodes(basal(ka = 0.1, ke = 0.01, durationMin = 336 * 60.0)))
        assertTrue(encodes(catalogBasal(InsulinController.BUILTIN_PRESETS.first { it.first == "Tresiba" }.second)))
    }
}
