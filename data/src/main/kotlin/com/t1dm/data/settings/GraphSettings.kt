package com.t1dm.data.settings

import com.t1dm.data.T1dmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The kv-backed BG-panel display settings (PLAN.private.md Phase 7A — BG-panel overhaul, items 1 &
 * 5). Two knobs live here so the graph module and the dashboard stay stateless and so a restart
 * restores the user's choice:
 *  - the glucose Y-axis floor/ceiling ([BgRange]) the axis must always span (default 20–250 mg/dL);
 *    the axis grows ABOVE the ceiling to never clip a high reading (enforced in `:ui:graph`);
 *  - the default visible past-window length in hours (the 6h / 12h / 24h buttons).
 *
 * Everything is stored as mg/dL / whole-hours; the graph converts to the active unit space at paint
 * time. No server dependency — `:data` sits below `:sync`; this is a pure local preference.
 */

/** The always-spanned glucose axis window, in mg/dL. `min` is the floor; `max` the *minimum* ceiling
 *  (the axis grows above it for highs). Invariant `0 < min < max` is enforced on write. */
data class BgRange(val minMgdl: Int, val maxMgdl: Int) {
    companion object {
        const val DEFAULT_MIN = 20
        const val DEFAULT_MAX = 250

        /** Hard physical clamps so a fat-fingered value can never invert or vanish the axis. */
        const val FLOOR = 0
        const val CEIL = 600

        val DEFAULT = BgRange(DEFAULT_MIN, DEFAULT_MAX)

        /** Clamp to `[FLOOR, CEIL]` with `min < max` enforced — the single sanitizer for reads/writes. */
        fun of(min: Int, max: Int): BgRange {
            val lo = min.coerceIn(FLOOR, CEIL - 1)
            val hi = max.coerceIn(lo + 1, CEIL)
            return BgRange(lo, hi)
        }
    }
}

class GraphSettingsStore(
    private val repository: T1dmRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The Y-axis span floor/ceiling; default 20–250 mg/dL. Persisted as `"min:max"`. */
    val range: Flow<BgRange> =
        combine(
            repository.observeKv(KV_RANGE_MIN),
            repository.observeKv(KV_RANGE_MAX),
        ) { rawMin, rawMax ->
            val min = rawMin?.toIntOrNull() ?: BgRange.DEFAULT_MIN
            val max = rawMax?.toIntOrNull() ?: BgRange.DEFAULT_MAX
            sanitize(min, max)
        }

    suspend fun currentRange(): BgRange = sanitize(
        repository.getKv(KV_RANGE_MIN)?.toIntOrNull() ?: BgRange.DEFAULT_MIN,
        repository.getKv(KV_RANGE_MAX)?.toIntOrNull() ?: BgRange.DEFAULT_MAX,
    )

    /** Persist the axis span, clamped to `[FLOOR, CEIL]` with `min < max` enforced. */
    suspend fun setRange(minMgdl: Int, maxMgdl: Int) {
        val r = sanitize(minMgdl, maxMgdl)
        val now = clock()
        repository.putKv(KV_RANGE_MIN, r.minMgdl.toString(), now)
        repository.putKv(KV_RANGE_MAX, r.maxMgdl.toString(), now)
    }

    /** The default visible past-window length, in whole hours; default 6. One of the three presets. */
    val windowHours: Flow<Int> = repository.observeKv(KV_WINDOW_HOURS).map { raw ->
        (raw?.toIntOrNull() ?: DEFAULT_WINDOW_HOURS).coerceIn(WINDOW_MIN_HOURS, WINDOW_MAX_HOURS)
    }

    suspend fun currentWindowHours(): Int =
        (repository.getKv(KV_WINDOW_HOURS)?.toIntOrNull() ?: DEFAULT_WINDOW_HOURS)
            .coerceIn(WINDOW_MIN_HOURS, WINDOW_MAX_HOURS)

    suspend fun setWindowHours(hours: Int) {
        repository.putKv(KV_WINDOW_HOURS, hours.coerceIn(WINDOW_MIN_HOURS, WINDOW_MAX_HOURS).toString(), clock())
    }

    private fun sanitize(min: Int, max: Int): BgRange = BgRange.of(min, max)

    companion object {
        private const val KV_RANGE_MIN = "graph.range_min_mgdl"
        private const val KV_RANGE_MAX = "graph.range_max_mgdl"
        private const val KV_WINDOW_HOURS = "graph.window_hours"

        const val DEFAULT_WINDOW_HOURS = 6
        const val WINDOW_MIN_HOURS = 1
        const val WINDOW_MAX_HOURS = 72

        /** The three window presets the dashboard buttons offer (item 5). */
        val WINDOW_PRESETS = listOf(6, 12, 24)
    }
}
