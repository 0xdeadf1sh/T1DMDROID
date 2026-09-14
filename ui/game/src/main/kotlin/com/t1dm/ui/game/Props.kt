package com.t1dm.ui.game

import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.GamePropDensity
import com.t1dm.core.model.Obstacle
import com.t1dm.core.model.ReadingFlag

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
    Fossil, Ammonite, Bone, Trilobite, Skull, Pipe, Manhole, Vein, Root, Chest,
    Tree, Pine, Birch, Palm, Willow, Bush, Tuft, Grass, Flowers, Cabin, Barn, Tent, WaterTower,
    Windmill, Scarecrow,
    Cloud, Bird, Balloon, Blimp, Kite,
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
    /** One per sign, "LO" or "HI"; a string per entry so the draw lays out once. */
    val signLabels: Array<String>,
) {
    companion object {
        val EMPTY = PropSet(
            PropField.EMPTY, PropField.EMPTY, PropField.EMPTY, PropField.EMPTY, PropField.EMPTY,
            PropField.EMPTY, emptyArray(),
        )
    }
}

/** Half width (m) of a ground or buried prop: its box, shared by glyph and collider. */
fun propHalfW(kind: PropKind, seed: Float): Float = propScale(seed) * when (kind) {
    PropKind.Tree, PropKind.Pine -> 10f
    PropKind.Birch -> 5f
    PropKind.Palm -> 8f
    PropKind.Willow -> 11f
    PropKind.Bush -> 4f
    PropKind.Tuft -> 1.5f
    PropKind.Grass, PropKind.Flowers -> 3f
    PropKind.Cabin -> 16f
    PropKind.Barn -> 20f
    PropKind.Tent -> 8f
    PropKind.WaterTower -> 6f
    PropKind.Windmill -> 3f
    PropKind.Scarecrow -> 2.5f
    PropKind.Fossil -> 7f
    PropKind.Skull -> 5f
    PropKind.Ammonite, PropKind.Bone, PropKind.Trilobite, PropKind.Manhole, PropKind.Chest -> 4f
    PropKind.Pipe -> 13f
    PropKind.Vein -> 11f
    PropKind.Root -> 6f
    else -> 0f
}

/** Height (m) of a ground prop up from the ground line, or of a buried prop's box. */
fun propH(kind: PropKind, seed: Float): Float = propScale(seed) * when (kind) {
    PropKind.Tree -> 40f
    PropKind.Pine, PropKind.Birch -> 36f
    PropKind.Palm -> 34f
    PropKind.Willow -> 32f
    PropKind.Windmill -> 20f
    PropKind.Bush -> 3.5f
    PropKind.Tuft -> 1.5f
    PropKind.Grass -> 2f
    PropKind.Flowers -> 3f
    PropKind.Cabin -> 28f
    PropKind.Barn -> 30f
    PropKind.Tent -> 12f
    PropKind.WaterTower -> 34f
    PropKind.Scarecrow -> 14f
    PropKind.Fossil, PropKind.Chest, PropKind.Skull -> 6f
    PropKind.Ammonite -> 8f
    PropKind.Bone -> 4f
    PropKind.Trilobite -> 5f
    PropKind.Pipe -> 5f
    PropKind.Root -> 10f
    PropKind.Vein -> 8f
    PropKind.Manhole -> 12f
    else -> 0f
}

private fun propScale(seed: Float): Float = 0.8f + 0.4f * seed

/** Anchored at the ground line and reaching DOWN; the rest are buried by their centre. */
fun hangsFromGround(kind: PropKind): Boolean = kind == PropKind.Manhole || kind == PropKind.Root

/** Metres of turf over a buried prop's box, at least. */
private const val COVER_M = 2f

/** Thinnest a box may go once inset; a tree's trunk still stands. */
private const val MIN_BOX_M = 0.5f

/** Solid props as boxes, skipping those between [behindM] before and [aheadM] past [teeX]. */
fun PropSet.obstacles(
    teeX: Float,
    behindM: Float,
    aheadM: Float,
    /** Off every face but a grounded base: the ball's radius, so the drawn ball meets the edge. */
    insetM: Float = 0f,
): List<Obstacle> {
    val f = ground
    val out = ArrayList<Obstacle>(f.size)
    fun box(x: Float, halfW: Float, h: Float, lift: Float) {
        val hw = (halfW - insetM).coerceAtLeast(MIN_BOX_M)
        if (lift > 0f) {
            out.add(Obstacle(x, hw, (h - 2f * insetM).coerceAtLeast(MIN_BOX_M), lift + insetM))
        } else {
            out.add(Obstacle(x, hw, (h - insetM).coerceAtLeast(MIN_BOX_M), 0f))
        }
    }
    for (i in 0 until f.size) {
        val kind = f.kindAt(i)
        val seed = f.seeds[i]
        val x = f.xs[i]
        val halfW = propHalfW(kind, seed)
        val h = propH(kind, seed)
        if (x + halfW >= teeX - behindM && x - halfW <= teeX + aheadM) continue
        // Each box traces the glyph: a trunk and a crown, a wall and a roof, never the whole frame.
        when (kind) {
            PropKind.Tree -> {
                box(x, halfW * 0.18f, h * 0.55f, 0f)
                box(x, halfW * 0.8f, h * 0.5f, h * 0.5f)
            }
            PropKind.Pine -> {
                box(x, halfW * 0.14f, h * 0.3f, 0f)
                box(x, halfW * 0.6f, h * 0.7f, h * 0.28f)
            }
            PropKind.Birch -> {
                box(x, halfW * 0.12f, h * 0.6f, 0f)
                box(x, halfW * 0.7f, h * 0.45f, h * 0.55f)
            }
            PropKind.Palm -> {
                box(x, halfW * 0.12f, h * 0.75f, 0f)
                box(x, halfW, h * 0.3f, h * 0.7f)
            }
            PropKind.Willow -> {
                box(x, halfW * 0.14f, h * 0.5f, 0f)
                box(x, halfW * 0.9f, h * 0.65f, h * 0.35f)
            }
            PropKind.Cabin -> {
                box(x, halfW, h * 0.6f, 0f)
                box(x, halfW * 0.6f, h * 0.4f, h * 0.6f)
            }
            PropKind.Barn -> {
                box(x, halfW, h * 0.65f, 0f)
                box(x, halfW * 0.75f, h * 0.35f, h * 0.65f)
            }
            PropKind.Tent -> box(x, halfW * 0.7f, h, 0f)
            PropKind.WaterTower -> {
                box(x, halfW * 0.5f, h * 0.6f, 0f)
                box(x, halfW, h * 0.4f, h * 0.6f)
            }
            PropKind.Windmill -> box(x, halfW * 0.6f, h * 0.9f, 0f)
            PropKind.Scarecrow -> box(x, halfW * 0.4f, h, 0f)
            else -> Unit
        }
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


/** Weighted kinds per layer; the weight is how many draws of the bag the kind holds. */
private val UNDERGROUND = intArrayOf(
    PropKind.Fossil.ordinal, PropKind.Fossil.ordinal, PropKind.Fossil.ordinal, PropKind.Fossil.ordinal,
    PropKind.Fossil.ordinal, PropKind.Fossil.ordinal, PropKind.Ammonite.ordinal, PropKind.Ammonite.ordinal,
    PropKind.Ammonite.ordinal, PropKind.Bone.ordinal, PropKind.Bone.ordinal, PropKind.Bone.ordinal,
    PropKind.Trilobite.ordinal, PropKind.Trilobite.ordinal, PropKind.Skull.ordinal, PropKind.Skull.ordinal,
    PropKind.Pipe.ordinal, PropKind.Manhole.ordinal, PropKind.Vein.ordinal, PropKind.Root.ordinal,
    PropKind.Chest.ordinal,
)
private val GROUND = intArrayOf(
    PropKind.Tree.ordinal, PropKind.Tree.ordinal, PropKind.Tree.ordinal, PropKind.Pine.ordinal,
    PropKind.Pine.ordinal, PropKind.Birch.ordinal, PropKind.Birch.ordinal, PropKind.Palm.ordinal,
    PropKind.Willow.ordinal, PropKind.Bush.ordinal, PropKind.Bush.ordinal, PropKind.Tuft.ordinal,
    PropKind.Tuft.ordinal, PropKind.Grass.ordinal, PropKind.Grass.ordinal, PropKind.Grass.ordinal,
    PropKind.Flowers.ordinal, PropKind.Flowers.ordinal, PropKind.Flowers.ordinal, PropKind.Cabin.ordinal,
    PropKind.Barn.ordinal, PropKind.Tent.ordinal, PropKind.WaterTower.ordinal, PropKind.Windmill.ordinal,
    PropKind.Scarecrow.ordinal,
)
private val SKY = intArrayOf(
    PropKind.Balloon.ordinal, PropKind.Balloon.ordinal, PropKind.Blimp.ordinal, PropKind.Kite.ordinal,
    PropKind.Kite.ordinal,
)

/** Deterministic in the track's start: the same day always grows the same world. */
fun buildProps(
    track: GameTrack,
    readings: List<CgmReading>,
    thresholds: AlertThresholds?,
    density: GamePropDensity,
): PropSet {
    if (!track.isPlayable) return PropSet.EMPTY
    val spacing = PropSpacing.of(density)
    val rng = Xorshift(track.startMs)
    val top = track.map.worldHeight

    val underground = scatter(track, rng, spacing.undergroundM, UNDERGROUND, needsFooting = true) { g, r, kind, seed ->
        val k = PropKind.entries[kind]
        if (hangsFromGround(k)) {
            g
        } else {
            val half = propH(k, seed) * 0.5f
            (g - (BURY_MIN_M + r * (BURY_MAX_M - BURY_MIN_M)).coerceAtLeast(half + COVER_M)).coerceAtLeast(half)
        }
    }
    val ground = scatter(track, rng, spacing.groundM, GROUND, needsFooting = true) { g, _, _, _ -> g }
    val clouds = scatter(track, rng, spacing.cloudM, intArrayOf(PropKind.Cloud.ordinal), needsFooting = false) { g, r, _, _ ->
        skyY(g, r, top)
    }
    val birds = scatter(track, rng, spacing.birdM, intArrayOf(PropKind.Bird.ordinal), needsFooting = false) { g, r, _, _ ->
        skyY(g, r, top)
    }
    val sky = scatter(track, rng, spacing.skyM, SKY, needsFooting = true) { g, r, kind, _ ->
        if (kind == PropKind.Kite.ordinal) g + KITE_MIN_M + r * (KITE_MAX_M - KITE_MIN_M) else skyY(g, r, top)
    }
    // A kite's amount is its string, so the draw can reach the ground without sampling it.
    for (i in 0 until sky.size) {
        if (sky.kinds[i] == PropKind.Kite.ordinal) sky.amounts[i] = sky.ys[i] - track.groundAt(sky.xs[i])
    }

    val (signs, labels) = if (thresholds == null) PropField.EMPTY to emptyArray() else signs(track, readings, thresholds)
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
    yOf: (ground: Float, r: Float, kind: Int, seed: Float) -> Float,
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
            ys[n] = yOf(g, rng.next(), kind, seed)
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
    val labels = Array(n) { signLabel(field.kinds[it]) }
    return field to labels
}

private fun signLabel(kind: Int): String = if (kind == PropKind.SignLow.ordinal) "LO" else "HI"

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
