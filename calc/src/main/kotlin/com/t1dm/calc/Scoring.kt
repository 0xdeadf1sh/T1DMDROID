package com.t1dm.calc

import kotlin.math.abs
import kotlin.math.min

/**
 * Scores a candidate's forecast fan under the selected [Objective] (Phase 4 §5). Lower is
 * better. Three invariants hold across every objective:
 *
 *  1. **Horizon weighting** — a step inside the validated window weighs 1.0; a step beyond it weighs
 *     [HorizonPolicy.beyondWindowWeight], so dose *selection* is dominated by the validated ≤2 h
 *     horizon while the far roll still nudges ties (SPEC § 5h-roll finding).
 *  2. **Hypo off the lower band** — hypo penalties read [FanStep.lowerBg] (the widening quantile),
 *     hyper penalties read [FanStep.medianBg]. A calculator must never trade a real hypo tail for a
 *     prettier median.
 *  3. **Configurable asymmetry** — hypo and hyper contributions scale by [Asymmetry.hypoWeight] /
 *     [Asymmetry.hyperWeight], unbounded and independent.
 *
 * An ineligible fan is unscoreable and returns [Double.POSITIVE_INFINITY] — it can never win the
 * grid search (fail-closed at the objective layer, orthogonal to the rails).
 */
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
            if (s.lowerBg < t.lowMgdl) acc += a.hypoWeight * w
            if (s.medianBg > t.highMgdl) acc += a.hyperWeight * w
        }
        return acc
    }

    private fun scoreKovatchev(fan: PredFan, config: CalcConfig): Double {
        val a = config.asymmetry
        var acc = 0.0
        fan.steps.forEachIndexed { i, s ->
            val w = weightAt(i, fan, config)
            acc += w * (a.hypoWeight * KovatchevRisk.lbgi(s.lowerBg) + a.hyperWeight * KovatchevRisk.hbgi(s.medianBg))
        }
        return acc
    }

    /**
     * Scores a candidate by how close its horizon-weighted MEDIAN sits to a single scalar target BG
     * (Objective.HitTargetBg). It is the point-target analogue of [scoreTimeOutOfRange]: every step
     * contributes the absolute median-to-target distance, discounted beyond the validated window and
     * scaled asymmetrically — an under-target median leans on [Asymmetry.hypoWeight], an over-target
     * one on [Asymmetry.hyperWeight].
     *
     * On top of that median-hit term it carries its OWN intrinsic hypo penalty off the τ=.05 lower
     * band — the same horizon-weighted [KovatchevRisk.lbgi] term as [scoreTimeOutOfRange] /
     * [scoreKovatchev] — so a candidate whose lower tail dips below [TargetRange.lowMgdl] is
     * penalised here directly. Hypo protection must NOT rest solely on the predicted-low VETO rail
     * (Rails.predictedLowVeto): that rail is user-disableable, and HitTargetBg is the objective the
     * Bolus advisor forces, so with the rail off this term is what keeps the primary advisor path
     * from recommending a dose predicted to cause hypoglycaemia. Every other objective is likewise
     * self-protecting off the lower band (object invariant 2).
     */
    private fun scoreHitTargetBg(fan: PredFan, config: CalcConfig, obj: Objective.HitTargetBg): Double {
        val a = config.asymmetry
        val target = obj.targetMgdl
        val hypoFloor = config.target.lowMgdl
        var acc = 0.0
        fan.steps.forEachIndexed { i, s ->
            val w = weightAt(i, fan, config)
            val dev = s.medianBg - target
            acc += (if (dev < 0.0) a.hypoWeight else a.hyperWeight) * w * abs(dev)
            if (s.lowerBg < hypoFloor) acc += a.hypoWeight * w * KovatchevRisk.lbgi(s.lowerBg)
        }
        return acc
    }

    private fun scoreHitTarget(fan: PredFan, config: CalcConfig, obj: Objective.HitTargetAtTime): Double {
        val idx = (obj.atMsFromNow / fan.stepMs).toInt()
        if (idx !in fan.steps.indices) return Double.POSITIVE_INFINITY // the target time is off the roll — unscoreable
        val target = config.target.targetMgdl
        // Primary term: squared deviation of the median at the requested time. A light asymmetric
        // hypo regulariser off the lower band keeps the search from overshooting into a low tail.
        val dev = fan.steps[idx].medianBg - target
        var acc = dev * dev
        val a = config.asymmetry
        val until = min(idx + 1, fan.steps.size)
        for (i in 0 until until) {
            if (fan.steps[i].lowerBg < config.target.lowMgdl) {
                acc += a.hypoWeight * KovatchevRisk.lbgi(fan.steps[i].lowerBg)
            }
        }
        return acc
    }
}
