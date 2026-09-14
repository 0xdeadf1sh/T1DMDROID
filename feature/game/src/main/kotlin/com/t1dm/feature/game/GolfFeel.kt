package com.t1dm.feature.game

import com.t1dm.core.design.HapticCue
import com.t1dm.core.design.HapticMixer
import com.t1dm.core.model.BallState
import com.t1dm.core.model.GolfRun
import kotlin.math.min
import kotlin.math.sqrt

/** Edges, not levels: a splash read off `penalties > 0` would fire every frame after the first. */
internal class GolfFeelTracker(private val maxLaunchSpeed: Float) {
    private val out = FeelFrame()
    private var airborne = false
    private var run = GolfRun.Playing
    private var strokes = 0
    private var penalties = 0
    private var bumping = false
    private var seeded = false

    fun observe(s: BallState): FeelFrame {
        out.clearEvents()

        val speed = sqrt(s.vx * s.vx + s.vy * s.vy)
        val rolling = !s.airborne && !s.atRest && s.run == GolfRun.Playing
        // Ground roll only: a ball in the air has nothing to be felt through.
        out.bed = if (rolling) min(1f, ROLL_BED * (speed / ROLL_FULL_MS).coerceIn(0f, 1f)) else 0f
        out.texture = out.bed
        // The engine voice is the car's; golf is one-shots over silence.
        out.engine = 0f

        if (!seeded) {
            seeded = true
            airborne = s.airborne
            run = s.run
            strokes = s.strokes
            penalties = s.penalties
            bumping = s.impact > BUMP_IMPULSE
            return out
        }

        // Terminal first, exclusively: dropping in IS a landing, and it must not read as one.
        if (s.run != run) {
            val was = run
            run = s.run
            airborne = s.airborne
            strokes = s.strokes
            penalties = s.penalties
            bumping = s.impact > BUMP_IMPULSE
            if (was == GolfRun.Playing && s.run == GolfRun.Holed) {
                out.cue = HapticCue.Blip
                out.cueIntensity = 1f
                out.sfx = GameSfx.Holed
                out.sfxIntensity = 1f
            }
            return out
        }

        // Then the drop-back, for the same reason: the re-placement is not a bounce either.
        if (s.penalties != penalties) {
            penalties = s.penalties
            strokes = s.strokes
            airborne = s.airborne
            bumping = s.impact > BUMP_IMPULSE
            out.cue = HapticCue.Impact
            out.cueIntensity = SPLASH_CUE
            out.sfx = GameSfx.Splash
            out.sfxIntensity = 1f
            return out
        }

        if (s.strokes != strokes) {
            strokes = s.strokes
            airborne = s.airborne
            bumping = s.impact > BUMP_IMPULSE
            val hit = if (maxLaunchSpeed > 0f) (speed / maxLaunchSpeed).coerceIn(0f, 1f) else 1f
            out.cue = HapticCue.Impact
            out.cueIntensity = (STRIKE_FLOOR + (1f - STRIKE_FLOOR) * hit).coerceIn(0f, 1f)
            out.sfx = GameSfx.Strike
            out.sfxIntensity = out.cueIntensity
            return out
        }

        val landed = airborne && !s.airborne
        airborne = s.airborne

        // A knock the turf takes without the ball ever clearing it; the commonest impact.
        val bump = s.impact > BUMP_IMPULSE
        val bumped = bump && !bumping
        bumping = bump

        if (landed || bumped) {
            val hit = FeelTracker.impactIntensity(s.impact)
            out.cue = HapticCue.Impact
            out.cueIntensity = hit
            // Touchdown only: a grounded knock re-arms several times a second, reads as tapping.
            if (landed) {
                out.sfx = GameSfx.Bounce
                out.sfxIntensity = hit
            }
        }
        return out
    }

    internal companion object {
        /** Where the roll rumble reaches full strength (m/s). */
        const val ROLL_FULL_MS = 18f

        /** Quieter than the car's engine bed: a ball on turf is a whisper, not a motor. */
        const val ROLL_BED = 0.30f

        /** N·s over the ball's own 6 per substep: a steady roll knocks ~2, a trace notch ~100. */
        const val BUMP_IMPULSE = 60f

        /** Even a tap putt is felt; only its scale varies, and it scales with the PULL. */
        const val STRIKE_FLOOR = 0.30f

        /** Water costs a stroke: felt, but not as hard as a crash. */
        const val SPLASH_CUE = 0.8f
    }
}

/** Narrower than GolfFeel on purpose: lifecycle verbs (release) belong to the composition. */
internal interface GolfFeelSink {
    fun frame(s: BallState)

    fun hold()

    companion object {
        val None = object : GolfFeelSink {
            override fun frame(s: BallState) = Unit
            override fun hold() = Unit
        }
    }
}

/** release is the alarm interlock: BOTH surfaces at once, vibrator handed back, not lowered. */
internal class GolfFeel(
    private val audio: GameAudio?,
    private val haptics: HapticMixer,
    maxLaunchSpeed: Float,
) : GolfFeelSink, GameSenses {
    private val tracker = GolfFeelTracker(maxLaunchSpeed)

    /** Game thread only. */
    override fun frame(s: BallState) {
        val f = tracker.observe(s)
        haptics.bed(f.bed, f.texture)
        f.cue?.let { haptics.cue(it, f.cueIntensity) }

        val synth = audio?.synth ?: return
        // The engine voice stays the car's; silencing it here keeps the one-shots on their own.
        synth.engine = 0f
        f.sfx?.let { synth.trigger(it, f.sfxIntensity) }
    }

    /** Silence both layers without giving the surface back. */
    override fun hold() {
        haptics.bed(0f)
        audio?.let { it.synth.engine = 0f }
    }

    override fun release() {
        hold()
        haptics.release()
        audio?.release()
    }

    override fun resume() {
        haptics.resume()
        audio?.resume()
    }

    /** The mixer and the track are closed by their own composition scopes. */
    override fun close() {
        hold()
        haptics.release()
        audio?.release()
    }

    companion object {
        /** For a device that refused an `AudioTrack` and has no vibrator. */
        val None = GolfFeel(null, HapticMixer.None, 1f)
    }
}
