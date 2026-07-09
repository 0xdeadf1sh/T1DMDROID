package com.t1dm.data.stats

import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.StatSample
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.TargetRange
import com.t1dm.core.model.UnitSpace
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The `:data`-side stats port (PLAN "Phase 6 — Stats", deliverable 2b): the two GLOBAL, kv-backed
 * settings — the stats TARGET RANGE (distinct from the alarm thresholds) and the display UNIT SPACE
 * — plus the LOCAL recompute that reads the windowed wide `sample` rows and hands them to the Rust
 * [NativeCore.advancedStats]. It deliberately holds NO server dependency (`:data` sits below
 * `:sync`); the server cached block is fetched separately and unioned upstream.
 *
 * The recompute is dispatched on [T1dmDispatchers.default] — the heavy Rust reduction must never
 * block the UI (PLAN §2.3). Fail-closed by construction: an empty/sparse window yields
 * [AdvancedStats.EMPTY] (the Rust guarantees no NaN), never a throw.
 */
class StatsRepository(
    private val repository: T1dmRepository,
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ── Settings: target range + unit space (kv-backed, global) ───────────────────────────────

    /** The stats TIR/TBR/TAR target range; default 70-180 mg/dL. Persisted as `"low:high"`. */
    val targetRange: Flow<TargetRange> = repository.observeKv(KV_TARGET_RANGE).map(::parseTargetRange)

    suspend fun currentTargetRange(): TargetRange = parseTargetRange(repository.getKv(KV_TARGET_RANGE))

    /** Persist the target range, clamped to the physical `[MIN, MAX]` with `low < high` enforced. */
    suspend fun setTargetRange(lowMgdl: Int, highMgdl: Int) {
        val lo = lowMgdl.coerceIn(TargetRange.MIN, TargetRange.MAX - 1)
        val hi = highMgdl.coerceIn(lo + 1, TargetRange.MAX)
        repository.putKv(KV_TARGET_RANGE, "$lo:$hi", clock())
    }

    /** The active glucose unit space for the axis + metric display; default mg/dL. */
    val unitSpace: Flow<UnitSpace> = repository.observeKv(KV_UNIT_SPACE).map(::parseUnitSpace)

    suspend fun currentUnitSpace(): UnitSpace = parseUnitSpace(repository.getKv(KV_UNIT_SPACE))

    suspend fun setUnitSpace(space: UnitSpace) {
        repository.putKv(KV_UNIT_SPACE, space.name, clock())
    }

    // ── Local recompute over the Room `sample` series ─────────────────────────────────────────

    /**
     * Recompute [AdvancedStats] for [window] locally from the wide `sample` rows in the trailing
     * `[now − window, now]` interval, against the current target range. [agpBins] must divide 1440
     * (default 48 = half-hourly). Runs on the default dispatcher; the DB read hops to IO internally.
     */
    suspend fun localStats(window: StatsWindow, agpBins: Int = DEFAULT_AGP_BINS): AdvancedStats {
        val target = currentTargetRange()
        val now = clock()
        val rows = repository.samplesInRange(now - window.millis, now)
        return withContext(dispatchers.default) {
            native.advancedStats(rows.map { it.toStatSample() }, target.lowMgdl, target.highMgdl, agpBins)
        }
    }

    private companion object {
        const val KV_TARGET_RANGE = "stats.target_range"
        const val KV_UNIT_SPACE = "stats.unit_space"
        const val DEFAULT_AGP_BINS = 48
    }
}

/** Parse the `"low:high"` kv string; any malformed/absent value falls back to [TargetRange.DEFAULT]. */
internal fun parseTargetRange(raw: String?): TargetRange {
    val parts = raw?.split(':') ?: return TargetRange.DEFAULT
    val lo = parts.getOrNull(0)?.toIntOrNull()
    val hi = parts.getOrNull(1)?.toIntOrNull()
    return if (lo != null && hi != null && lo < hi) TargetRange(lo, hi) else TargetRange.DEFAULT
}

/** Parse the persisted unit-space enum name; unknown/absent falls back to mg/dL. */
internal fun parseUnitSpace(raw: String?): UnitSpace =
    raw?.let { runCatching { UnitSpace.valueOf(it) }.getOrNull() } ?: UnitSpace.MgDl

/**
 * Project a wide sample row to a Rust [StatSample]. A null/absent BG maps to `0.0` (the Rust
 * excludes non-positive BG from every glucose metric) while the treatment/activity channels still
 * count toward the totals — matching `advanced_stats`' "channels over all samples" contract.
 */
internal fun SampleEntity.toStatSample(): StatSample = StatSample(
    tsMs = ts,
    bgMgdl = bgMgdl?.toDouble() ?: 0.0,
    carbsG = carbsG,
    bolusU = bolusU,
    basalU = basalU,
    steps = steps?.toLong(),
    mood = mood,
)
