package com.t1dm.ui.game

import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.GamePropDensity
import com.t1dm.core.model.Obstacle
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.UnitSpace
import java.util.Locale

/** Metres of trace between props, per layer; the jitter around it is ±40 %. */
class PropSpacing(
    val undergroundM: Float,
    val groundM: Float,
    val cloudM: Float,
    val birdM: Float,
    val skyM: Float,
) {
    companion object {
        fun of(density: GamePropDensity): PropSpacing = when (density) {
            GamePropDensity.Sparse -> PropSpacing(160f, 110f, 200f, 400f, 500f)
            GamePropDensity.Busy -> PropSpacing(70f, 45f, 90f, 180f, 220f)
        }
    }
}

enum class PropKind {
    Fossil, Ammonite, Bone, Pipe, Manhole, Aquifer, Vein, Root, Chest,
    Tree, Pine, Bush, Tuft, Rock, Cabin, Windmill, Fence, Scarecrow,
    Cloud, Bird, Balloon, Kite,
    SignLow, SignHigh,
}

/** Parallel primitive arrays in ascending world x; every field is read-only after the build. */
class PropField internal constructor(
    /** [PropKind.ordinal] per entry. */
    val kinds: IntArray,
    val xs: FloatArray,
    /** World y: the anchor the glyph hangs from — its feet, its centre, or its face. */
    val ys: FloatArray,
    /** [0, 1): size, phase and variant; the draw derives every per-prop difference from it. */
    val seeds: FloatArray,
    /** Kind-specific: a kite's tether (m), a sign's mg/dL at the extreme; 0 otherwise. */
    val amounts: FloatArray,
) {
    val size: Int get() = kinds.size

    fun kindAt(i: Int): PropKind = PropKind.entries[kinds[i]]

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
        val EMPTY = PropField(IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/** One field per draw pass, so each culls with one binary search and parallax shifts per field. */
class PropSet(
    val underground: PropField,
    val ground: PropField,
    /** Parallax [CLOUD_PARALLAX] and wind [CLOUD_DRIFT_MS]. */
    val clouds: PropField,
    /** Fly at [BIRD_SPEED_MS] toward −x. */
    val birds: PropField,
    /** Balloons and kites: world-anchored. */
    val sky: PropField,
    val signs: PropField,
    /** One per sign, in the trace's unit; formatted once so the draw never formats. */
    val signLabels: Array<String>,
) {
    companion object {
        val EMPTY = PropSet(
            PropField.EMPTY, PropField.EMPTY, PropField.EMPTY, PropField.EMPTY, PropField.EMPTY,
            PropField.EMPTY, emptyArray(),
        )
    }
}

/** Half width (m) of a ground prop: the box it stands in, shared by its glyph and collider. */
fun groundHalfW(kind: PropKind, seed: Float): Float = groundScale(seed) * when (kind) {
    PropKind.Tree, PropKind.Pine -> 5f
    PropKind.Bush -> 4f
    PropKind.Tuft -> 1.5f
    PropKind.Rock -> 4.5f
    PropKind.Cabin, PropKind.Fence -> 8f
    PropKind.Windmill -> 3f
    PropKind.Scarecrow -> 2.5f
    else -> 0f
}

/** Height (m) of a ground prop, from the ground line. */
fun groundH(kind: PropKind, seed: Float): Float = groundScale(seed) * when (kind) {
    PropKind.Tree, PropKind.Windmill -> 20f
    PropKind.Pine -> 18f
    PropKind.Bush, PropKind.Rock -> 3.5f
    PropKind.Tuft -> 1.5f
    PropKind.Cabin, PropKind.Scarecrow -> 14f
    PropKind.Fence -> 3f
    else -> 0f
}

private fun groundScale(seed: Float): Float = 0.8f + 0.4f * seed

/** Low enough for the car to bump over; the rest only stand in a golf ball's way. */
fun isLowProp(kind: PropKind): Boolean =
    kind == PropKind.Bush || kind == PropKind.Rock || kind == PropKind.Fence

/** Ground props as solid boxes, less those near [keepOutX] and, if asked, the tall ones. */
fun PropSet.obstacles(lowOnly: Boolean, keepOutX: Float, keepOutM: Float): List<Obstacle> {
    val f = ground
    val out = ArrayList<Obstacle>(f.size)
    for (i in 0 until f.size) {
        val kind = f.kindAt(i)
        if (kind == PropKind.Tuft) continue
        if (lowOnly && !isLowProp(kind)) continue
        val halfW = groundHalfW(kind, f.seeds[i])
        if (kotlin.math.abs(f.xs[i] - keepOutX) <= keepOutM + halfW) continue
        out.add(Obstacle(f.xs[i], halfW, groundH(kind, f.seeds[i])))
    }
    return out
}

/** Clouds cross the panel at half the camera's pace, and drift downwind on their own. */
const val CLOUD_PARALLAX = 0.5f
const val CLOUD_DRIFT_MS = 1.5f
const val BIRD_SPEED_MS = 6f

/** Metres either side of a sample the ground must be solid for a prop to stand there. */
private const val FOOTING_M = 2f

/** Metres below the ground line a buried prop's anchor may sit, low to high. */
private const val BURY_MIN_M = 5f
private const val BURY_MAX_M = 40f

/** Metres above the ground line the sky starts; a cloud never sits on a hill. */
private const val SKY_FLOOR_M = 15f

/** Kite string, metres; the kite's anchor is its diamond, the string reaches the ground. */
private const val KITE_MIN_M = 18f
private const val KITE_MAX_M = 32f

/** Two dropouts this far apart cut an excursion in two: sensor silence proves nothing. */
private const val MAX_GAP_MIN = 30f

private const val MGDL_PER_MMOLL = 18.0182

/** Weighted kinds per layer; the weight is how many draws of the bag the kind holds. */
private val UNDERGROUND = intArrayOf(
    PropKind.Fossil.ordinal, PropKind.Fossil.ordinal, PropKind.Ammonite.ordinal, PropKind.Bone.ordinal,
    PropKind.Bone.ordinal, PropKind.Pipe.ordinal, PropKind.Pipe.ordinal, PropKind.Manhole.ordinal,
    PropKind.Aquifer.ordinal, PropKind.Aquifer.ordinal, PropKind.Vein.ordinal, PropKind.Root.ordinal,
    PropKind.Root.ordinal, PropKind.Chest.ordinal,
)
private val GROUND = intArrayOf(
    PropKind.Tree.ordinal, PropKind.Tree.ordinal, PropKind.Tree.ordinal, PropKind.Pine.ordinal,
    PropKind.Pine.ordinal, PropKind.Bush.ordinal, PropKind.Bush.ordinal, PropKind.Tuft.ordinal,
    PropKind.Tuft.ordinal, PropKind.Tuft.ordinal, PropKind.Rock.ordinal, PropKind.Rock.ordinal,
    PropKind.Cabin.ordinal, PropKind.Windmill.ordinal, PropKind.Fence.ordinal, PropKind.Scarecrow.ordinal,
)
private val SKY = intArrayOf(PropKind.Balloon.ordinal, PropKind.Kite.ordinal, PropKind.Kite.ordinal)

/** Deterministic in the track's start: the same day always grows the same world. */
fun buildProps(
    track: GameTrack,
    readings: List<CgmReading>,
    thresholds: AlertThresholds?,
    unit: UnitSpace,
    density: GamePropDensity,
): PropSet {
    if (!track.isPlayable) return PropSet.EMPTY
    val spacing = PropSpacing.of(density)
    val rng = Xorshift(track.startMs)
    val top = track.map.worldHeight

    val underground = scatter(track, rng, spacing.undergroundM, UNDERGROUND, needsFooting = true) { g, r, kind ->
        if (kind == PropKind.Manhole.ordinal || kind == PropKind.Root.ordinal) g
        else (g - BURY_MIN_M - r * (BURY_MAX_M - BURY_MIN_M)).coerceAtLeast(1f)
    }
    val ground = scatter(track, rng, spacing.groundM, GROUND, needsFooting = true) { g, _, _ -> g }
    val clouds = scatter(track, rng, spacing.cloudM, intArrayOf(PropKind.Cloud.ordinal), needsFooting = false) { g, r, _ ->
        skyY(g, r, top)
    }
    val birds = scatter(track, rng, spacing.birdM, intArrayOf(PropKind.Bird.ordinal), needsFooting = false) { g, r, _ ->
        skyY(g, r, top)
    }
    val sky = scatter(track, rng, spacing.skyM, SKY, needsFooting = true) { g, r, kind ->
        if (kind == PropKind.Kite.ordinal) g + KITE_MIN_M + r * (KITE_MAX_M - KITE_MIN_M) else skyY(g, r, top)
    }
    // A kite's amount is its string, so the draw can reach the ground without sampling it.
    for (i in 0 until sky.size) {
        if (sky.kinds[i] == PropKind.Kite.ordinal) sky.amounts[i] = sky.ys[i] - track.groundAt(sky.xs[i])
    }

    val (signs, labels) = if (thresholds == null) PropField.EMPTY to emptyArray() else signs(track, readings, thresholds, unit)
    return PropSet(underground, ground, clouds, birds, sky, signs, labels)
}

/** Sky y as a share of what is left above the ground, never under [SKY_FLOOR_M] of it. */
private fun skyY(g: Float, r: Float, top: Float): Float {
    val floor = (if (g.isFinite()) g else 0f) + SKY_FLOOR_M
    return floor + r * (top + SKY_FLOOR_M - floor).coerceAtLeast(10f)
}

/** Walks the track at [spacingM] ± 40 %, skipping every spot whose footing is a gap. */
private inline fun scatter(
    track: GameTrack,
    rng: Xorshift,
    spacingM: Float,
    bag: IntArray,
    needsFooting: Boolean,
    yOf: (ground: Float, r: Float, kind: Int) -> Float,
): PropField {
    val length = track.length
    val cap = (length / (spacingM * 0.6f)).toInt() + 2
    val kinds = IntArray(cap)
    val xs = FloatArray(cap)
    val ys = FloatArray(cap)
    val seeds = FloatArray(cap)
    var n = 0
    var x = spacingM * rng.next()
    while (x < length && n < cap) {
        val g = track.groundAt(x)
        val footed = g.isFinite() && track.groundAt(x - FOOTING_M).isFinite() && track.groundAt(x + FOOTING_M).isFinite()
        if (footed || !needsFooting) {
            val kind = bag[(rng.next() * bag.size).toInt().coerceIn(0, bag.size - 1)]
            val seed = rng.next()
            kinds[n] = kind
            xs[n] = x
            ys[n] = yOf(g, rng.next(), kind)
            seeds[n] = seed
            n++
        }
        x += spacingM * (0.6f + 0.8f * rng.next())
    }
    return PropField(kinds.copyOf(n), xs.copyOf(n), ys.copyOf(n), seeds.copyOf(n), FloatArray(n))
}

/** One sign per EXCURSION, at its extreme; a dropout longer than [MAX_GAP_MIN] cuts a run. */
private fun signs(
    track: GameTrack,
    readings: List<CgmReading>,
    thresholds: AlertThresholds,
    unit: UnitSpace,
): Pair<PropField, Array<String>> {
    val scored = readings.asSequence()
        .filter { it.bgMgdl != null && it.flag == ReadingFlag.NORMAL }
        .sortedBy { it.tsMs }
        .toList()
    val map = track.map
    val kinds = ArrayList<Int>()
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val amounts = ArrayList<Float>()
    val gapMs = MAX_GAP_MIN.toDouble() * 60_000.0

    fun place(kind: PropKind, r: CgmReading) {
        if (r.tsMs < track.startMs || r.tsMs > track.endMs) return
        val x = map.worldXOf(r.tsMs)
        val g = track.groundAt(x)
        if (!g.isFinite()) return
        kinds.add(kind.ordinal)
        xs.add(x)
        ys.add(g)
        amounts.add(r.bgMgdl!!.toFloat())
    }

    var i = 0
    while (i < scored.size) {
        val bg = scored[i].bgMgdl!!
        val low = bg < thresholds.lowMgdl
        val high = bg > thresholds.highMgdl
        if (!low && !high) { i++; continue }
        var j = i
        var extreme = i
        while (j + 1 < scored.size && (scored[j + 1].tsMs - scored[j].tsMs) <= gapMs) {
            val next = scored[j + 1].bgMgdl!!
            if (low && next >= thresholds.lowMgdl) break
            if (high && next <= thresholds.highMgdl) break
            j++
            if (low && next < scored[extreme].bgMgdl!!) extreme = j
            if (high && next > scored[extreme].bgMgdl!!) extreme = j
        }
        place(if (low) PropKind.SignLow else PropKind.SignHigh, scored[extreme])
        i = j + 1
    }

    val n = kinds.size
    if (n == 0) return PropField.EMPTY to emptyArray()
    val order = (0 until n).sortedBy { xs[it] }
    val field = PropField(
        kinds = IntArray(n) { kinds[order[it]] },
        xs = FloatArray(n) { xs[order[it]] },
        ys = FloatArray(n) { ys[order[it]] },
        seeds = FloatArray(n),
        amounts = FloatArray(n) { amounts[order[it]] },
    )
    val labels = Array(n) { signLabel(field.kinds[it], field.amounts[it], unit) }
    return field to labels
}

private fun signLabel(kind: Int, mgdl: Float, unit: UnitSpace): String {
    val value = when (unit) {
        UnitSpace.MmolL -> String.format(Locale.ROOT, "%.1f", mgdl / MGDL_PER_MMOLL)
        else -> mgdl.toInt().toString()
    }
    return if (kind == PropKind.SignLow.ordinal) "LOW $value" else "HIGH $value"
}

/** Marsaglia xorshift64*, seeded once; `next()` is uniform in [0, 1). */
private class Xorshift(seed: Long) {
    private var s = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun next(): Float {
        s = s xor (s ushr 12)
        s = s xor (s shl 25)
        s = s xor (s ushr 27)
        val v = (s * 2685821657736338717L) ushr 40
        return v.toFloat() / (1L shl 24).toFloat()
    }
}
