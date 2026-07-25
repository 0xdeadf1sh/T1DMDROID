package com.t1dm.data.curve

import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.ResolvedMealCurve
import kotlin.math.ceil

/**
 * Mixes several [MealComponent]s into ONE combined carb **appearance (Ra)** curve (
 * Phase 4 — `MealCurveResolver`; [model-io-curves]). Each component becomes a [CurveEvent] — a
 * per-food gamma from its GI ([GiToGamma]), or its user-drawn normalized [MealComponent.customCurve]
 * scaled to the portion's carbs — and the events are summed onto a shared 5-min grid via the Rust
 * [CurveEngine.bucketize]. The result is what the model consumes on its carb channel and what the
 * builder previews live; on log it becomes the `logged_meal` appearance override.
 *
 * All work is on [CurveEngine]'s `Dispatchers.default`, so this is safe to call from a preview
 * `produceState`.
 */
class MealCurveResolver(private val engine: CurveEngine) {

    /** One component → its carb-appearance [CurveEvent] at [startMs] (custom shape wins over GI). */
    suspend fun resolveComponent(component: MealComponent, startMs: Long): CurveEvent {
        val carbs = component.carbs
        val shape = component.customCurve
        return if (shape != null && shape.isNotEmpty()) {
            // Normalized shape (sums ~1.0) scaled to this portion's carbs.
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

    /**
     * Combine [components] into one [ResolvedMealCurve] over `[startMs, startMs + nSteps·STEP_MS)`.
     * Zero-carb components (protein/fat/fibre) contribute nothing to the Ra curve but are still
     * summed into `totalCarbs` as 0.
     */
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

    /**
     * Same as [resolveCombined] but auto-sizes the grid to cover the longest component's absorption
     * (plus a small margin), so the preview shows the full appearance tail without the caller
     * guessing a horizon. Used by the live meal-builder preview.
     */
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
