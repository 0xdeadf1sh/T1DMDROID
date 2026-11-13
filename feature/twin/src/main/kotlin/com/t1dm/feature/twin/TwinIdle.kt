package com.t1dm.feature.twin

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** Idle pose per frame: breathing always, and once untouched a random clip every 4-8 s. */
internal class TwinIdle(private val body: TwinBody) {
    /** Pitch, yaw, roll (rad) per bone; see [TwinSkin.pose]. */
    val rot = FloatArray(body.boneCount * 3)
    val shift = FloatArray(3)

    private val clips = CLIPS.map { Resolved(it, body) }
    private val breath = Resolved(BREATH, body)
    private val rng = java.util.Random()
    private var clip: Resolved? = null
    private var startS = 0f
    private var side = 1f
    private var weight = 0f
    private var nextS = 1f

    /** [toFace] 0..1: how far the camera has zoomed to the face, which damps the clips. */
    fun step(t: Float, dt: Float, still: Boolean, idleNow: Boolean, toFace: Float) {
        rot.fill(0f)
        shift.fill(0f)
        if (still) {
            clip = null
            return
        }
        val b = 0.5f - 0.5f * cos(t * 2f * PI.toFloat() / BREATH_S)
        breath.add(rot, shift, 0f, 1f, b)
        val c = clip
        if (c != null) {
            val ct = t - startS
            weight = if (idleNow) 1f else max(0f, weight - dt * FADE_PER_S)
            if (ct >= c.clip.seconds || weight <= 0f) {
                clip = null
                nextS = t + GAP_MIN_S + rng.nextFloat() * (GAP_MAX_S - GAP_MIN_S)
            } else {
                c.add(rot, shift, ct, side, weight * (1f - toFace))
            }
        } else if (!idleNow) {
            nextS = max(nextS, t + 1f)
        } else if (t >= nextS) {
            clip = clips[rng.nextInt(clips.size)]
            startS = t
            side = if (rng.nextBoolean()) 1f else -1f
            weight = 1f
        }
    }

    /** One rotation channel; [keys] are (seconds, rad) pairs, eased between. */
    private class Track(val bone: String, val axis: Int, vararg val keys: Float)

    /** [shift]: root x (dm) as (seconds, dm) pairs. [sided]: may be mirrored left-right. */
    private class Clip(val seconds: Float, val sided: Boolean, val shift: FloatArray?, val tracks: List<Track>)

    private class Resolved(val clip: Clip, body: TwinBody) {
        private val bone = IntArray(clip.tracks.size) { body.boneIndex(clip.tracks[it].bone) }
        private val mirrored = IntArray(clip.tracks.size) { body.boneIndex(mirror(clip.tracks[it].bone)) }

        init {
            require((bone + mirrored).all { it >= 0 }) { "a clip names a bone the asset lacks" }
        }

        /** Mirroring swaps .L and .R and flips yaw, roll and the sideways shift. */
        fun add(rot: FloatArray, shift: FloatArray, at: Float, side: Float, gain: Float) {
            for ((i, tr) in clip.tracks.withIndex()) {
                val b = if (side < 0f) mirrored[i] else bone[i]
                val flip = if (side < 0f && tr.axis != PITCH) -1f else 1f
                rot[b * 3 + tr.axis] += sample(tr.keys, at) * flip * gain
            }
            clip.shift?.let { shift[0] += sample(it, at) * side * gain }
        }
    }

    private companion object {
        const val PITCH = 0
        const val YAW = 1
        const val ROLL = 2
        const val BREATH_S = 4.5f
        const val FADE_PER_S = 3f
        const val GAP_MIN_S = 4f
        const val GAP_MAX_S = 8f

        fun mirror(name: String) = when {
            name.endsWith(".L") -> name.dropLast(2) + ".R"
            name.endsWith(".R") -> name.dropLast(2) + ".L"
            else -> name
        }

        fun sample(keys: FloatArray, t: Float): Float {
            if (t <= keys[0]) return keys[1]
            for (k in 2 until keys.size step 2) {
                if (t < keys[k]) {
                    val u = (t - keys[k - 2]) / (keys[k] - keys[k - 2])
                    val e = u * u * (3f - 2f * u)
                    return keys[k - 1] + (keys[k + 1] - keys[k - 1]) * e
                }
            }
            return keys[keys.size - 1]
        }

        /** Rises over 1.4 s, holds to 4.4 s, settles by 6 s. */
        fun hold(a: Float) = floatArrayOf(0f, 0f, 1.4f, a, 4.4f, a, 6f, 0f)

        fun glance(a: Float) = floatArrayOf(0f, 0f, 1.1f, a, 2.8f, a, 4.2f, 0f)

        fun shrug(a: Float) = floatArrayOf(0f, 0f, 0.7f, a, 1.5f, a, 2.3f, 0f)

        fun roll(a: Float) = floatArrayOf(0f, 0f, 0.9f, a, 1.7f, 0f, 2.5f, -0.6f * a, 3.4f, 0f)

        /** Breathing at full inhale; scaled by the breath phase each frame. */
        val BREATH = Clip(
            1f, false, null,
            listOf(
                Track("spine03", PITCH, 0f, -0.02f),
                Track("spine01", PITCH, 0f, -0.02f),
                Track("neck01", PITCH, 0f, 0.04f),
                Track("clavicle.L", ROLL, 0f, 0.03f),
                Track("clavicle.R", ROLL, 0f, -0.03f),
                Track("upperarm01.L", ROLL, 0f, 0.02f),
                Track("upperarm01.R", ROLL, 0f, -0.02f),
            ),
        )

        /** Onto the left leg: hips slide and tilt, the spine counters, the right knee eases. */
        val WEIGHT_SHIFT = Clip(
            6f, true, hold(0.25f),
            listOf(
                Track("root", ROLL, *hold(0.06f)),
                Track("spine05", ROLL, *hold(-0.035f)),
                Track("spine01", ROLL, *hold(-0.03f)),
                Track("neck01", ROLL, *hold(0.005f)),
                Track("upperleg01.L", ROLL, *hold(-0.091f)),
                Track("upperleg01.R", ROLL, *hold(-0.091f)),
                Track("upperleg01.R", PITCH, *hold(-0.12f)),
                Track("lowerleg01.R", PITCH, *hold(0.24f)),
                Track("foot.R", PITCH, *hold(-0.1f)),
            ),
        )

        /** Head and upper spine turn to the left, then back. */
        val LOOK = Clip(
            4.2f, true, null,
            listOf(
                Track("spine03", YAW, *glance(0.04f)),
                Track("spine01", YAW, *glance(0.08f)),
                Track("neck01", YAW, *glance(0.32f)),
                Track("head", YAW, *glance(0.22f)),
                Track("head", PITCH, *glance(0.06f)),
            ),
        )

        /** Both shoulders up, back, down and forward. */
        val SHOULDER_ROLL = Clip(
            3.4f, false, null,
            listOf(
                Track("clavicle.L", ROLL, *shrug(0.13f)),
                Track("clavicle.R", ROLL, *shrug(-0.13f)),
                Track("upperarm01.L", ROLL, *shrug(-0.06f)),
                Track("upperarm01.R", ROLL, *shrug(0.06f)),
                Track("clavicle.L", YAW, *roll(0.16f)),
                Track("clavicle.R", YAW, *roll(-0.16f)),
                Track("spine01", PITCH, *roll(-0.03f)),
            ),
        )

        val CLIPS = listOf(WEIGHT_SHIFT, LOOK, SHOULDER_ROLL)
    }
}
