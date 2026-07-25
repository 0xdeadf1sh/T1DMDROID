package com.t1dm.ui.game

import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.TargetRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a thing on the track does when the car reaches it. */
enum class PickupKind {
    /** A reading inside the target range. */
    Coin,

    /** A hypo excursion — the one thing on the track that is not a joke. */
    Hazard,
}

/** How far above the ground a collectable floats, in metres — clear of the car's roof so the sprite
 *  reads as suspended rather than as bodywork. Hazards sit ON the ground and get none of this. */
private const val PICKUP_HOVER_M = 1.6f

/** Minimum world-x separation between two coins. At one metre per minute the 5-min grid already puts
 *  them 5 m apart, so this only ever bites on a coarser scale — but two coins in one place is one
 *  coin the player never sees. */
private const val COIN_MIN_SPACING_M = 3f

/**
 * Everything the run's own record left on the track, in ascending world x. Parallel primitive arrays
 * and no per-item object: a day's in-range readings alone are ~288 entries, and the collect test runs
 * every frame against a window found by [firstFrom].
 *
 * Immutable — which is deliberate. Whether a given pickup has been taken is run state, and belongs to
 * whoever owns the frame loop; the derivation is a fact about the record and is shared, cached and
 * re-read after a reset without being rebuilt.
 */
class PickupField internal constructor(
    /** [PickupKind.ordinal] per entry. */
    val kinds: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
    /**
     * What the entry is worth, read against its kind: [PickupKind.Coin] is a count (always 1),
     * [PickupKind.Hazard] is how many mg/dL below the low alarm threshold the excursion's nadir reached
     * — so a deeper hypo is a bigger obstacle, which is the correct sign.
     */
    val amounts: FloatArray,
    /** The instant in the record each entry came from — for the "what actually happened here" readout. */
    val tsMs: LongArray,
) {
    val size: Int get() = kinds.size
    val isEmpty: Boolean get() = kinds.isEmpty()

    fun kindAt(i: Int): PickupKind = PickupKind.entries[kinds[i]]

    /** First index at or beyond [x] (binary search on the ascending [xs]); [size] when past the end. */
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

/** Derive the pickups off the main thread. */
suspend fun pickupsOf(
    track: GameTrack,
    readings: List<CgmReading>,
    targetRange: TargetRange,
    thresholds: AlertThresholds,
    maxGapMin: Float = 30f,
): PickupField = withContext(Dispatchers.Default) {
    buildPickups(track, readings, targetRange, thresholds, maxGapMin)
}

/**
 * Pure CPU transform — safe from a `@Preview` or a test.
 *
 * THREE DIFFERENT mg/dL BANDS meet here and none of them may be derived from another. Coins use
 * [TargetRange], the clamped stats band behind TIR, with `stats.rs`'s own inclusive predicate
 * `low <= bg <= high` so a coin means exactly what a green minute in the time-in-range figure means.
 * Hazards use [AlertThresholds.lowMgdl], the user's actual danger line, which is deliberately
 * UNBOUNDED under the §3.6 safety lock — a user who has set it to 40 gets fewer hazards, and that is
 * the correct answer, not a bug to clamp away. (The third band, the graph's axis span, sets the world
 * height and appears nowhere in this file.) The two coincide at 70 only by default and diverge the
 * moment either is edited.
 *
 * Classification is always in mg/dL, off the raw reading — never off the terrain, whose heights are in
 * whatever unit the panel was showing. Placement is the other way round: the world knows where a thing
 * is, so every entry is dropped onto the ground at its own instant. The over-a-chasm guard in [place] is
 * now unreachable — every remaining entry comes from a reading, and a reading is solid ground by
 * construction — but it is kept because it is what makes [place] total for any future kind.
 *
 * Readings are taken at [ReadingFlag.NORMAL] only: WARMUP is suppressed from alarm evaluation by §3.6
 * and it would be perverse for the game to score it. Interpolated values are kept — the gap-fill
 * bridged them, the panel draws them, and they are solid ground.
 */
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

    // One hazard per EXCURSION, at its nadir — not one per reading. A two-hour hypo is a single event
    // the record remembers as a single event, and strewing twenty obstacles over it would say the
    // opposite. Runs are cut at a dropout as well as at a recovery: a hypo either side of an hour the
    // sensor was silent is two excursions, because nothing says it stayed low in between.
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

/** Merge the two passes into one ascending-x field. Index sort, so the four payload arrays are
 *  permuted once rather than a list of objects being built and thrown away. */
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
