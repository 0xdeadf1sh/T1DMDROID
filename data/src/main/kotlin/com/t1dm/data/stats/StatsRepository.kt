package com.t1dm.data.stats

import com.t1dm.core.model.ReadingProvenance
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
 * No server dependency: `:data` sits below `:sync`, and the server's cached block is unioned
 * upstream. The reduction runs on [T1dmDispatchers.default]; an empty or sparse window yields
 * [AdvancedStats.EMPTY], never a throw.
 */
class StatsRepository(
    private val repository: T1dmRepository,
    private val native: NativeCore,
    private val dispatchers: T1dmDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Persisted as `"low:high"`, mg/dL. */
    val targetRange: Flow<TargetRange> = repository.observeKv(KV_TARGET_RANGE).map(::parseTargetRange)

    suspend fun currentTargetRange(): TargetRange = parseTargetRange(repository.getKv(KV_TARGET_RANGE))

    suspend fun setTargetRange(lowMgdl: Int, highMgdl: Int) {
        val lo = lowMgdl.coerceIn(TargetRange.MIN, TargetRange.MAX - 1)
        val hi = highMgdl.coerceIn(lo + 1, TargetRange.MAX)
        repository.putKv(KV_TARGET_RANGE, "$lo:$hi", clock())
    }

    val unitSpace: Flow<UnitSpace> = repository.observeKv(KV_UNIT_SPACE).map(::parseUnitSpace)

    suspend fun currentUnitSpace(): UnitSpace = parseUnitSpace(repository.getKv(KV_UNIT_SPACE))

    suspend fun setUnitSpace(space: UnitSpace) {
        repository.putKv(KV_UNIT_SPACE, space.name, clock())
    }

    /**
     * [AdvancedStats] over the trailing `[now − window, now]` rows. [agpBins] must divide 1440.
     * Memoized per window; [force] recomputes regardless.
     */
    suspend fun localStats(
        window: StatsWindow,
        agpBins: Int = DEFAULT_AGP_BINS,
        force: Boolean = false,
    ): AdvancedStats {
        val target = currentTargetRange()
        // Snap the window's upper edge DOWN to the grid: without it the interval slides on every
        // call, no two are comparable, and the memo is unsound rather than merely useless.
        val to = clock() / T1dmRepository.GRID_MS * T1dmRepository.GRID_MS
        val from = to - window.millis
        val key = CacheKey(window, target, to, agpBins)

        // One lock across the whole read-compute-store, so two callers cannot start the same
        // reduction; the second waits and is served the first one's answer.
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

    /** Everything the reduction is a function of; equality of this and the window's
     *  [SampleWindowFingerprint] is equality of the answer. */
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

    /** One memo per [StatsWindow]: the push loop asks for all three in turn and the screen for
     *  whichever is on show, so a single slot would have each evict the other's. */
    private val statsCache = mutableMapOf<StatsWindow, CacheEntry>()
    private val cacheLock = Mutex()

    /** For the process-preserving wipe: derived PATIENT data on an app-lifetime object. Correctness
     *  does not depend on it — residency does. */
    suspend fun invalidateCache() = cacheLock.withLock { statsCache.clear() }

    private companion object {
        const val KV_TARGET_RANGE = "stats.target_range"
        const val KV_UNIT_SPACE = "stats.unit_space"
        const val DEFAULT_AGP_BINS = 48
    }
}

internal fun parseTargetRange(raw: String?): TargetRange {
    val parts = raw?.split(':') ?: return TargetRange.DEFAULT
    val lo = parts.getOrNull(0)?.toIntOrNull()
    val hi = parts.getOrNull(1)?.toIntOrNull()
    return if (lo != null && hi != null && lo < hi) TargetRange(lo, hi) else TargetRange.DEFAULT
}

internal fun parseUnitSpace(raw: String?): UnitSpace =
    raw?.let { runCatching { UnitSpace.valueOf(it) }.getOrNull() } ?: UnitSpace.MgDl

/**
 * A null or RECONSTRUCTED BG maps to `0.0`, which the crate excludes from every glucose metric: a
 * promoted reconstruction may never enter a statistic as a measurement (`SPEC/invariants.md` §1).
 * Carbs/bolus/basal are curve events, so they are null here.
 */
internal fun SampleEntity.toStatSample(): StatSample = StatSample(
    tsMs = ts,
    // The offset stamped on the ROW, never the phone's now: a 90-day window may straddle a DST
    // change or a flight.
    tzOffsetMin = tzOffsetMin,
    bgMgdl = bgMgdl?.takeIf { bgProvenance != ReadingProvenance.RECONSTRUCTED }?.toDouble() ?: 0.0,
    carbsG = null,
    bolusU = null,
    basalU = null,
    steps = steps?.toLong(),
    mood = mood,
)
