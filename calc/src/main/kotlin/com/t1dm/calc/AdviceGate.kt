package com.t1dm.calc

import com.t1dm.data.curve.CurveEngine
import kotlin.math.roundToInt

/** Whether a computed recommendation may still be accepted; any doubt expires it. */
object AdviceGate {

    /** One grid step. */
    const val TTL_MS = CurveEngine.STEP_MS

    enum class Stale { AGED, LOG_CHANGED, CLOCK_BACK }

    /** Null = fresh. [lastCurveWriteMs] null = no dose, meal or exercise write this process. */
    fun staleness(computedAtMs: Long, lastCurveWriteMs: Long?, nowMs: Long): Stale? {
        val age = nowMs - computedAtMs
        return when {
            age < 0L -> Stale.CLOCK_BACK
            lastCurveWriteMs != null && lastCurveWriteMs >= computedAtMs -> Stale.LOG_CHANGED
            age >= TTL_MS -> Stale.AGED
            else -> null
        }
    }

    fun fresh(computedAtMs: Long, lastCurveWriteMs: Long?, nowMs: Long): Boolean =
        staleness(computedAtMs, lastCurveWriteMs, nowMs) == null

    /** Whole mg/dL, the grain a target travels to the search at; null = the Settings objective. */
    fun sameTarget(computedMgdl: Double?, inForceMgdl: Double?): Boolean =
        computedMgdl?.roundToInt() == inForceMgdl?.roundToInt()
}
