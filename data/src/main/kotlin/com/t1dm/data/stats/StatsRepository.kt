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
import com.t1dm.data.db.SampleWindowFingerprint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * block the UI (SPEC §2.3). Fail-closed by construction: an empty/sparse window yields
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
     * [AdvancedStats] for [window] over the wide `sample` rows in the trailing `[now − window, now]`
     * interval, against the current target range. [agpBins] must divide 1440 (default 48 =
     * half-hourly). The reduction runs on the default dispatcher; the DB read hops to IO internally.
     *
     * **Memoized per window** — see [statsCache]. Pass [force] to recompute regardless, which is what
     * the Stats screen's own Recompute button means.
     */
    suspend fun localStats(
        window: StatsWindow,
        agpBins: Int = DEFAULT_AGP_BINS,
        force: Boolean = false,
    ): AdvancedStats {
        val target = currentTargetRange()
        // Snap the window's upper edge DOWN to the 5-min grid. The samples sit on that grid, so every
        // instant inside one bucket selects the same rows and must yield the same block — without the
        // snap the interval slides on every call and no two are comparable, which would make the
        // memo unsound rather than merely useless.
        val to = clock() / T1dmRepository.GRID_MS * T1dmRepository.GRID_MS
        val from = to - window.millis
        val key = CacheKey(window, target, to, agpBins)

        // One lock across the whole read-compute-store, so the 30-minute push loop walking all three
        // windows and the screen asking for one cannot both start the same reduction: the second
        // caller waits and is then served the first one's answer.
        return cacheLock.withLock {
            val fingerprint = repository.sampleWindowFingerprint(from, to)
            if (!force) {
                statsCache[window]?.takeIf { it.key == key && it.fingerprint == fingerprint }
                    ?.let { return@withLock it.stats }
            }
            val rows = repository.samplesInRange(from, to)
            withContext(dispatchers.default) {
                native.advancedStats(rows.map { it.toStatSample() }, target.lowMgdl, target.highMgdl, agpBins)
            }.also { statsCache[window] = CacheEntry(key, fingerprint, it) }
        }
    }

    /**
     * Everything the reduction is a function of. Equality of this AND of the window's
     * [SampleWindowFingerprint] is equality of the answer — there is no other input, so a hit cannot
     * serve a stale block:
     *
     *  - [target] and [agpBins] are the reduction's own arguments.
     *  - [toMs] is the grid-snapped window edge, so it changes at every grid tick and the interval a
     *    hit was computed over is exactly the interval being asked for.
     *  - the fingerprint moves on any insert, in-place merge, or delete inside the window.
     */
    private data class CacheKey(
        val window: StatsWindow,
        val target: TargetRange,
        val toMs: Long,
        val agpBins: Int,
    )

    private data class CacheEntry(
        val key: CacheKey,
        val fingerprint: SampleWindowFingerprint,
        val stats: AdvancedStats,
    )

    /**
     * One memo per [StatsWindow], because the two callers interleave: the 30-minute push loop asks
     * for all three in turn, and the screen asks for whichever is on show. A single slot would have
     * each evict the other's, so the screen would recompute a 90-day window on a re-entry that
     * changed nothing — which is the case this exists for. Three entries, a few KB each.
     *
     * The reduction it is memoizing is the expensive one in the app outside inference: a 90-day
     * window is ~26 000 rows materialised, projected, and carried across the uniffi boundary.
     */
    private val statsCache = mutableMapOf<StatsWindow, CacheEntry>()
    private val cacheLock = Mutex()

    /**
     * Drop every memoized block. For the process-preserving wipe: these are derived PATIENT data —
     * mean glucose, GMI, the AGP curve, the weekday grid — held on an app-lifetime object, so a reset
     * that leaves them resident leaves the patient's statistics in memory after their data is gone.
     * Correctness does not depend on this (the fingerprint's `n` cannot match an emptied table), but
     * residency is the point.
     */
    suspend fun invalidateCache() = cacheLock.withLock { statsCache.clear() }

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
 * excludes non-positive BG from every glucose metric); `steps`/`mood` still feed the activity
 * channels. Carbs/bolus/basal were demoted off the sample row to self-describing curve events
 * (§5), so they are `null` here.
 */
internal fun SampleEntity.toStatSample(): StatSample = StatSample(
    tsMs = ts,
    // The offset stamped on the ROW when it was written, never the phone's offset now: the window
    // runs up to 90 days and may straddle a DST change or a flight, and reading today's offset onto
    // an old row would key it to a day the patient did not live.
    tzOffsetMin = tzOffsetMin,
    bgMgdl = bgMgdl?.toDouble() ?: 0.0,
    carbsG = null,
    bolusU = null,
    basalU = null,
    steps = steps?.toLong(),
    mood = mood,
)
