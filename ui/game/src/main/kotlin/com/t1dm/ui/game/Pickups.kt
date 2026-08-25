package com.t1dm.ui.game

import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.TargetRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PickupKind {
    Coin,

    Hazard,
}

/** Metres above the ground, clear of the car's roof. Hazards sit on the ground and get none. */
private const val PICKUP_HOVER_M = 1.6f

/** Only bites on a scale coarser than the 5-min grid's 5 m at one metre per minute. */
private const val COIN_MIN_SPACING_M = 3f

/** Parallel primitive arrays, in ascending world x. Immutable: whether a pickup has been taken is run
 *  state and belongs to whoever owns the frame loop. */
class PickupField internal constructor(
    /** [PickupKind.ordinal] per entry. */
    val kinds: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
    /** [PickupKind.Coin]: a count, always 1. [PickupKind.Hazard]: mg/dL below the low alarm threshold
     *  at the excursion's nadir. */
    val amounts: FloatArray,
    val tsMs: LongArray,
) {
    val size: Int get() = kinds.size
    val isEmpty: Boolean get() = kinds.isEmpty()

    fun kindAt(i: Int): PickupKind = PickupKind.entries[kinds[i]]

    /** Binary search on the ascending [xs]; [size] when past the end. */
    fun firstFrom(x: Float): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = PickupField(IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0), LongArray(0))
    }
}

suspend fun pickupsOf(
    track: GameTrack,
    readings: List<CgmReading>,
    targetRange: TargetRange,
    thresholds: AlertThresholds,
    maxGapMin: Float = 30f,
): PickupField = withContext(Dispatchers.Default) {
    buildPickups(track, readings, targetRange, thresholds, maxGapMin)
}

/** Coins use [TargetRange] with `stats.rs`'s own inclusive `low <= bg <= high`; hazards use
 *  [AlertThresholds.lowMgdl], the user's danger line, unbounded under the §3.6 lock. Neither band
 *  derives from the other; classify in mg/dL off the reading, never off terrain heights. */
fun buildPickups(
    track: GameTrack,
    readings: List<CgmReading>,
    targetRange: TargetRange,
    thresholds: AlertThresholds,
    maxGapMin: Float = 30f,
): PickupField {
    if (!track.isPlayable) return PickupField.EMPTY
    val map = track.map
    val kinds = ArrayList<Int>()
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val amounts = ArrayList<Float>()
    val stamps = ArrayList<Long>()

    fun place(kind: PickupKind, tsMs: Long, amount: Float, onGround: Boolean) {
        if (tsMs < track.startMs || tsMs > track.endMs) return
        val x = map.worldXOf(tsMs)
        val ground = track.groundAt(x)
        if (!ground.isFinite()) return
        kinds.add(kind.ordinal)
        xs.add(x)
        ys.add(if (onGround) ground else ground + PICKUP_HOVER_M)
        amounts.add(amount)
        stamps.add(tsMs)
    }

    val scored = readings.asSequence()
        .filter { it.bgMgdl != null && it.flag == ReadingFlag.NORMAL }
        .sortedBy { it.tsMs }
        .toList()

    var lastCoinX = Float.NEGATIVE_INFINITY
    for (r in scored) {
        val bg = r.bgMgdl!!
        if (bg < targetRange.lowMgdl || bg > targetRange.highMgdl) continue
        val x = map.worldXOf(r.tsMs)
        if (x - lastCoinX < COIN_MIN_SPACING_M) continue
        val before = kinds.size
        place(PickupKind.Coin, r.tsMs, 1f, false)
        if (kinds.size != before) lastCoinX = x
    }

    // One hazard per EXCURSION, at its nadir. Runs are cut at a dropout as well as at a recovery:
    // nothing says the glucose stayed low across an hour the sensor was silent.
    val gapMs = maxGapMin.toDouble() * 60_000.0
    var i = 0
    while (i < scored.size) {
        if (scored[i].bgMgdl!! >= thresholds.lowMgdl) { i++; continue }
        var j = i
        var nadir = i
        while (j + 1 < scored.size &&
            scored[j + 1].bgMgdl!! < thresholds.lowMgdl &&
            (scored[j + 1].tsMs - scored[j].tsMs) <= gapMs
        ) {
            j++
            if (scored[j].bgMgdl!! < scored[nadir].bgMgdl!!) nadir = j
        }
        place(PickupKind.Hazard, scored[nadir].tsMs, (thresholds.lowMgdl - scored[nadir].bgMgdl!!).toFloat(), true)
        i = j + 1
    }

    return sortByX(kinds, xs, ys, amounts, stamps)
}

private fun sortByX(
    kinds: List<Int>, xs: List<Float>, ys: List<Float>, amounts: List<Float>, stamps: List<Long>,
): PickupField {
    val n = kinds.size
    if (n == 0) return PickupField.EMPTY
    val order = (0 until n).sortedBy { xs[it] }
    return PickupField(
        kinds = IntArray(n) { kinds[order[it]] },
        xs = FloatArray(n) { xs[order[it]] },
        ys = FloatArray(n) { ys[order[it]] },
        amounts = FloatArray(n) { amounts[order[it]] },
        tsMs = LongArray(n) { stamps[order[it]] },
    )
}
