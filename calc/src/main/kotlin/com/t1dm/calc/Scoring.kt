package com.t1dm.calc

import kotlin.math.abs
import kotlin.math.min

/** Lower is better. Every objective scores off [FanStep.medianBg]: the band influences dose
 *  selection nowhere. An ineligible fan returns [Double.POSITIVE_INFINITY] and can never win. */
object Scoring {

    fun scoreFan(fan: PredFan, config: CalcConfig): Double {
        if (!fan.eligible || fan.steps.isEmpty()) return Double.POSITIVE_INFINITY
        return when (val obj = config.objective) {
            Objective.MinTimeOutOfRange -> scoreTimeOutOfRange(fan, config)
            Objective.MinKovatchevRisk -> scoreKovatchev(fan, config)
            is Objective.HitTargetAtTime -> scoreHitTarget(fan, config, obj)
            is Objective.HitTargetBg -> scoreHitTargetBg(fan, config, obj)
        }
    }

    private fun weightAt(index: Int, fan: PredFan, config: CalcConfig): Double =
        if (index < fan.validatedSteps) 1.0 else config.horizon.beyondWindowWeight

    private fun scoreTimeOutOfRange(fan: PredFan, config: CalcConfig): Double {
        val a = config.asymmetry
        val t = config.target
        var acc = 0.0
        fan.steps.forEachIndexed { i, s ->
            val w = weightAt(i, fan, config)
            if (s.medianBg < t.lowMgdl) acc += a.hypoWeight * w
            if (s.medianBg > t.highMgdl) acc += a.hyperWeight * w
        }
        return acc
    }

    private fun scoreKovatchev(fan: PredFan, config: CalcConfig): Double {
        val a = config.asymmetry
        var acc = 0.0
        fan.steps.forEachIndexed { i, s ->
            val w = weightAt(i, fan, config)
            acc += w * (a.hypoWeight * KovatchevRisk.lbgi(s.medianBg) + a.hyperWeight * KovatchevRisk.hbgi(s.medianBg))
        }
        return acc
    }

    /** The intrinsic lbgi term is load-bearing: Rails.predictedLowVeto is user-disableable and this
     *  is the objective the Bolus advisor forces, so with the rail off this term is the only thing
     *  keeping the primary path off a dose predicted to cause hypoglycaemia. */
    private fun scoreHitTargetBg(fan: PredFan, config: CalcConfig, obj: Objective.HitTargetBg): Double {
        val a = config.asymmetry
        val target = obj.targetMgdl
        val hypoFloor = config.target.lowMgdl
        var acc = 0.0
        fan.steps.forEachIndexed { i, s ->
            val w = weightAt(i, fan, config)
            val dev = s.medianBg - target
            acc += (if (dev < 0.0) a.hypoWeight else a.hyperWeight) * w * abs(dev)
            if (s.medianBg < hypoFloor) acc += a.hypoWeight * w * KovatchevRisk.lbgi(s.medianBg)
        }
        return acc
    }

    private fun scoreHitTarget(fan: PredFan, config: CalcConfig, obj: Objective.HitTargetAtTime): Double {
        val idx = (obj.atMsFromNow / fan.stepMs).toInt()
        if (idx !in fan.steps.indices) return Double.POSITIVE_INFINITY // target time is off the roll
        val target = config.target.targetMgdl
        // Squared deviation at the requested time, plus a hypo regulariser against overshoot.
        val dev = fan.steps[idx].medianBg - target
        var acc = dev * dev
        val a = config.asymmetry
        val until = min(idx + 1, fan.steps.size)
        for (i in 0 until until) {
            if (fan.steps[i].medianBg < config.target.lowMgdl) {
                acc += a.hypoWeight * KovatchevRisk.lbgi(fan.steps[i].medianBg)
            }
        }
        return acc
    }
}
