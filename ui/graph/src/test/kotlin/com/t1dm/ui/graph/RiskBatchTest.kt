package com.t1dm.ui.graph

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow

/** The risk axis crosses JNI once per build, landing where the scalar transform puts each point. */
class RiskBatchTest {

    private val STEP = 300_000L
    private val T0 = 1_700_000_000_000L
    private val NQ = 7
    private val H = 24

    /** `t1dm-core::kovatchev_f` transcribed, so the test needs no JNI. */
    private val f: (Double) -> Double = { g -> 1.509 * (ln(g.coerceIn(20.0, 500.0)).pow(1.084) - 5.381) }

    private var calls = 0
    private val batch: (DoubleArray) -> DoubleArray = { a -> calls++; DoubleArray(a.size) { f(a[it]) } }

    private fun risk(mgdl: Double): Float = f(mgdl).toFloat()

    private fun reading(ts: Long, bg: Int) = CgmReading(
        sourceId = CgmSourceId("t"), tsMs = ts, bgMgdl = bg, trendTenthsPerMin = 0,
        minFromStart = 5, quality = 100, provenance = ReadingProvenance.MEASURED, flag = ReadingFlag.NORMAL,
        tzOffsetMin = 0, rxWallMs = ts, rssi = -60,
    )

    /** Every τ column distinct, so a swapped lane shows. */
    private fun pred(c: Int): ModelPrediction {
        val level = 120.0 + 7 * c
        return ModelPrediction(
            modelId = "m", cycleTsMs = T0 + c * STEP, anchorTsMs = T0 + c * STEP, stepMs = STEP,
            medianBg = List(H) { level + it }, bandsMgdl = List(H * NQ) { level + (it % NQ - 3) * 9.0 + it / NQ },
            nQuantiles = NQ, lastBg = level - 4, status = ForecastStatus.OK,
            backend = BackendId.EXECUTORCH_XNNPACK_FP32, selected = true, stale = false, latencyMs = null,
        )
    }

    @Test fun graphFrameConvertsInOneCall() {
        val rs = (0 until 500).map { reading(T0 + it * STEP, 40 + (it * 37) % 400) }
        val frame = buildGraphFrame(rs, UnitSpace.Kovatchev, maxPoints = rs.size + 1, kovatchevFClinicalBatch = batch)
        assertEquals(1, calls)
        for (i in rs.indices) assertEquals(risk(rs[i].bgMgdl!!.toDouble()), frame.ys[i], 0f)
    }

    @Test fun aShortBatchWithholdsTheTrace() {
        val rs = (0 until 10).map { reading(T0 + it * STEP, 100) }
        val frame = buildGraphFrame(rs, UnitSpace.Kovatchev, kovatchevFClinicalBatch = { it.copyOf(it.size - 1) })
        assertTrue(frame.isEmpty)
    }

    @Test fun predSeriesConvertsEveryLaneInOneCall() {
        val p = pred(0)
        val s = buildPredSeries(p, UnitSpace.Kovatchev, batch)!!
        assertEquals(1, calls)
        val anchor = risk(p.lastBg)
        assertEquals(anchor, s.median[0], 0f)
        for (b in 0 until 3) {
            assertEquals(anchor, s.lo[b][0], 0f)
            assertEquals(anchor, s.hi[b][0], 0f)
        }
        for (i in 1..H) {
            assertEquals(risk(p.medianBg[i - 1]), s.median[i], 0f)
            for (b in 0 until 3) {
                assertEquals(risk(p.bandsMgdl[(i - 1) * NQ + b]), s.lo[b][i], 0f)
                assertEquals(risk(p.bandsMgdl[(i - 1) * NQ + NQ - 1 - b]), s.hi[b][i], 0f)
            }
        }
    }

    @Test fun hindsightConvertsTheWholeSweepInOneCall() {
        val rows = (0 until 12).map { pred(it) }
        val hf = runBlocking { hindsightFrameOf(rows, UnitSpace.Kovatchev, batch) }!!
        assertEquals(1, calls)
        assertEquals(rows.size, hf.cycles)
        val span = hf.span
        for ((c, p) in rows.withIndex()) {
            val s = buildPredSeries(p, UnitSpace.Kovatchev, batch)!!
            for (i in 0 until span) {
                assertEquals(s.median[i], hf.median[c * span + i], 0f)
                for (b in 0 until 3) {
                    assertEquals(s.lo[b][i], hf.lo[(b * rows.size + c) * span + i], 0f)
                    assertEquals(s.hi[b][i], hf.hi[(b * rows.size + c) * span + i], 0f)
                }
            }
        }
    }

    @Test fun rolledSeriesConvertsInOneCall() {
        val n = 48
        val medians = DoubleArray(n) { 110.0 + it }
        val bands = DoubleArray(n * NQ) { medians[it / NQ] + (it % NQ - 3) * 11.0 }
        val rf = RolledForecast(
            anchorTsMs = T0, stepMs = STEP, medianBg = medians,
            lowerBg = DoubleArray(n) { medians[it] - 30 }, upperBg = DoubleArray(n) { medians[it] + 30 },
            bandsMgdl = bands, validatedSteps = 24, requestedHours = 4.0, eligible = true,
            degenerate = false, reason = null, completedRolls = 2, requestedRolls = 2,
        )
        val s = buildRolledSeries(rf, UnitSpace.Kovatchev, batch)!!
        assertEquals(1, calls)
        for (i in 0 until n) {
            assertEquals(risk(medians[i]), s.median[i], 0f)
            for (b in 0 until 3) {
                assertEquals(risk(bands[i * NQ + b]), s.lo[b][i], 0f)
                assertEquals(risk(bands[i * NQ + NQ - 1 - b]), s.hi[b][i], 0f)
            }
        }
        val bare = buildRolledSeries(rf.copy(bandsMgdl = DoubleArray(0)), UnitSpace.Kovatchev, batch)!!
        assertEquals(2, calls)
        assertEquals(1, bare.lo.size)
        for (i in 0 until n) {
            assertEquals(risk(medians[i] - 30), bare.lo[0][i], 0f)
            assertEquals(risk(medians[i] + 30), bare.hi[0][i], 0f)
        }
    }

    @Test fun aUnitSwitchReusesTheSmoothing() {
        val rs = (0 until 50).map { reading(T0 + it * STEP, 90 + it) }
        var smooths = 0
        val sm = buildSmoothedMgdl(rs, { smooths++; DoubleArray(it.size) { i -> it[i] + 5.0 } })
        val mmol = sm.inUnit(UnitSpace.MmolL, batch)
        val kov = sm.inUnit(UnitSpace.Kovatchev, batch)
        assertEquals(1, smooths)
        assertEquals(1, calls)
        for (i in rs.indices) {
            val v = rs[i].bgMgdl!! + 5.0
            assertEquals((v / 18.0182).toFloat(), mmol.ys[i], 0f)
            assertEquals(risk(v), kov.ys[i], 0f)
        }
    }
}
