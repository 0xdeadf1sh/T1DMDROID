package com.t1dm.data.curve

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.ResolvedMealCurve
import kotlin.math.ceil

/** All work runs on [CurveEngine]'s default dispatcher; safe from a preview `produceState`. */
class MealCurveResolver(private val engine: CurveEngine) {

    suspend fun resolveComponent(component: MealComponent, startMs: Long): CurveEvent {
        val carbs = component.carbs
        val shape = component.customCurve
        return if (shape != null && shape.isNotEmpty()) {
            // customCurve is normalized: sums ~1.0.
            val total = shape.sum()
            val scale = if (total > 0.0) carbs / total else 0.0
            CurveEvent(
                startMs = startMs,
                stepMs = CurveEngine.STEP_MS,
                kind = CurveKind.CARB,
                total = carbs,
                values = shape.map { it * scale },
            )
        } else {
            val g = GiToGamma.paramsForGiOrDefault(component.giOrNull)
            engine.carbEvent(carbs, startMs, g.k, g.theta, g.durationMin)
        }
    }

    /** Zero-carb components add nothing to the curve but still count in `totalCarbs`. */
    suspend fun resolveCombined(
        components: List<MealComponent>,
        startMs: Long,
        nSteps: Int,
    ): ResolvedMealCurve {
        if (components.isEmpty()) return ResolvedMealCurve.EMPTY
        val events = components
            .filter { it.carbs > 0.0 }
            .map { resolveComponent(it, startMs) }
        val values = if (events.isEmpty()) {
            List(nSteps) { 0.0 }
        } else {
            engine.bucketize(events, startMs, nSteps, CurveKind.CARB).toList()
        }
        return ResolvedMealCurve(
            totalCarbs = components.sumOf { it.carbs },
            values = values,
            stepMs = CurveEngine.STEP_MS,
        )
    }

    /** Sizes the grid to the longest component's absorption. */
    suspend fun resolveCombined(components: List<MealComponent>, startMs: Long): ResolvedMealCurve {
        if (components.isEmpty()) return ResolvedMealCurve.EMPTY
        val events = components.filter { it.carbs > 0.0 }.map { resolveComponent(it, startMs) }
        if (events.isEmpty()) {
            return ResolvedMealCurve(components.sumOf { it.carbs }, emptyList(), CurveEngine.STEP_MS)
        }
        val nSteps = events.maxOf { it.values.size }.coerceAtLeast(1)
        val values = engine.bucketize(events, startMs, nSteps, CurveKind.CARB).toList()
        return ResolvedMealCurve(components.sumOf { it.carbs }, values, CurveEngine.STEP_MS)
    }
}
