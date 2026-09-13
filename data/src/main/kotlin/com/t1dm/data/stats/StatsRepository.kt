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

/** :data below :sync; runs on default dispatcher; sparse window yields EMPTY. */
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

    /** [AdvancedStats] over trailing [now−window,now]; [agpBins] divides 1440; memoized/window. */
    suspend fun localStats(
        window: StatsWindow,
        agpBins: Int = DEFAULT_AGP_BINS,
        force: Boolean = false,
    ): AdvancedStats {
        val target = currentTargetRange()
        // Snap upper edge DOWN to grid: else the interval slides each call, memo unsound.
        val to = clock() / T1dmRepository.GRID_MS * T1dmRepository.GRID_MS
        val from = to - window.millis
        val key = CacheKey(window, target, to, agpBins)

        // One lock across read-compute-store: callers can't race; the second gets first's answer.
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

    /** Everything the reduction is a function of; equal this+fingerprint means an equal answer. */
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

    /** One memo per [StatsWindow]: push loop asks all three, screen one; a slot evicts others. */
    private val statsCache = mutableMapOf<StatsWindow, CacheEntry>()
    private val cacheLock = Mutex()

    /** Process-preserving wipe: PATIENT data on an app-lifetime object; residency needs it. */
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

/** null/RECONSTRUCTED BG maps to 0.0, excluded from metrics (§1); carbs/bolus/basal null here. */
internal fun SampleEntity.toStatSample(): StatSample = StatSample(
    tsMs = ts,
    // The offset stamped on the ROW, never phone's now: a 90-day window may cross DST or a flight.
    tzOffsetMin = tzOffsetMin,
    bgMgdl = bgMgdl?.takeIf { bgProvenance != ReadingProvenance.RECONSTRUCTED }?.toDouble() ?: 0.0,
    carbsG = null,
    bolusU = null,
    basalU = null,
    steps = steps?.toLong(),
    mood = mood,
)
