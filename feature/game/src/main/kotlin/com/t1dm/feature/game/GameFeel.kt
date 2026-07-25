package com.t1dm.feature.game

import com.t1dm.core.design.HapticCue
import com.t1dm.core.design.HapticMixer
import com.t1dm.core.model.CarState
import com.t1dm.core.model.RunState
import kotlin.math.abs
import kotlin.math.min

/**
 * What one simulated frame should sound and feel like — the whole mapping from physics to the ear and
 * the hand, and the only part of the sensory layer that is neither an actuator nor a synthesiser.
 *
 * It is kept pure and separate from both because it is the part with an opinion. Whether a landing
 * fires at all is an edge over two frames; how hard it hits is a saturating function of an impulse in
 * newton-seconds; whether the wheels are on the ground decides not the volume of the rumble but its
 * existence. None of that belongs in a renderer, and all of it can be got wrong in a way that reads as
 * "the game feels off" and never as a stack trace — so it lives here, and it is tested.
 *
 * **Weightlessness reads by ABSENCE.** Airborne is not a quieter bed, it is no bed: the actuator stops
 * (see `HapticMixCore`, where silence deliberately bypasses the control-period pacing), and the
 * landing that follows lands against that hush. The engine keeps SOUNDING while airborne — a throttle
 * held in the air spins the wheels up and the note climbs — so the two modalities diverge exactly
 * where a real car's do, and the contrast belongs to the hand alone.
 */

/** One frame's worth of demands, reused in place so the frame path allocates nothing. */
internal class FeelFrame {
    /** Sustained haptic amplitude in `[0, 1]`. */
    var bed = 0f

    /** Sustained haptic graininess in `[0, 1]` — the surface, not its loudness. */
    var texture = 0f

    /** Engine voice level in `[0, 1]`. */
    var engine = 0f

    var cue: HapticCue? = null
    var cueIntensity = 0f
    var sfx: GameSfx? = null
    var sfxIntensity = 0f

    fun clearEvents() {
        cue = null
        cueIntensity = 0f
        sfx = null
        sfxIntensity = 0f
    }
}

/**
 * The frame-to-frame state the mapping needs: two edges (leaving the ground, and the run ending) and
 * one level crossing (a bump hard enough to be an event rather than texture).
 *
 * Edges rather than levels throughout, because every one of these is a moment. A landing rendered from
 * `!airborne` alone would fire every frame the car sat still.
 */
internal class FeelTracker {
    private val out = FeelFrame()
    private var airborne = false
    private var run = RunState.Running
    private var bumping = false
    private var seeded = false

    fun observe(s: CarState): FeelFrame {
        out.clearEvents()

        val speed = min(1f, abs(s.vx) / SPEED_FULL_MS)
        val rev = ((s.rpm - IDLE_RPM) / (MAX_RPM - IDLE_RPM)).coerceIn(0f, 1f)

        // The bed is two summed sources with quite different characters: the engine, which is always
        // there and rises with revs and effort, and the ground, which exists only while the wheels are
        // on it and only while they are turning — a car parked on a jagged slope is not vibrating.
        val motor = (ENGINE_IDLE_BED + (ENGINE_FULL_BED - ENGINE_IDLE_BED) * rev) *
            (0.62f + 0.38f * s.throttleApplied.coerceIn(0f, 1f))
        val ground = s.roughness.coerceIn(0f, 1f) * TERRAIN_BED * speed
        out.bed = if (s.airborne || s.run != RunState.Running) 0f else min(1f, motor + ground)
        out.texture = if (s.airborne) 0f else (s.roughness.coerceIn(0f, 1f) * speed)
        out.engine = if (s.run == RunState.Running) 1f else 0f

        if (!seeded) {
            seeded = true
            airborne = s.airborne
            run = s.run
            bumping = s.impactImpulse > BUMP_IMPULSE
            return out
        }

        // Terminal first, and exclusively: a crash IS a landing, and firing both would put an Impact
        // in the same window as the Shock only for the mixer to drop it — correct, but a coincidence
        // rather than a decision.
        if (s.run != run) {
            val was = run
            run = s.run
            airborne = s.airborne
            bumping = s.impactImpulse > BUMP_IMPULSE
            if (was == RunState.Running && s.run == RunState.Crashed) {
                out.cue = HapticCue.Shock
                out.cueIntensity = 1f
                out.sfx = GameSfx.Crash
                out.sfxIntensity = 1f
            }
            return out
        }

        val landed = airborne && !s.airborne
        airborne = s.airborne

        // A bump the suspension takes without the wheels ever leaving: hitting the lip of a rise at
        // speed is an impact by any measure the hand cares about, and it is the commonest one.
        val bump = s.impactImpulse > BUMP_IMPULSE
        val bumped = bump && !bumping
        bumping = bump

        if (landed || bumped) {
            val hit = impactIntensity(s.impactImpulse)
            out.cue = HapticCue.Impact
            out.cueIntensity = hit
            // Touchdown only. A wheels-down bump re-arms every time the impulse falls back under the
            // threshold, which on terrain cut from a real trace is several times a second: the hand
            // reads that as texture, but the speaker renders it as tapping.
            if (landed) {
                out.sfx = GameSfx.Landing
                out.sfxIntensity = hit
            }
        }
        return out
    }

    internal companion object {
        /** The REACHABLE rev range, so `rev` below spans [0, 1] over what the car can actually do
         *  rather than over the solver's absurd-tuning rail of 9 000, against which the engine bed
         *  never left its first fifth. See [TOP_RPM] for why both are transcribed. */
        const val IDLE_RPM = 800f
        const val MAX_RPM = TOP_RPM

        /** Where ground texture reaches full strength. Six metres a second is a brisk climb on this
         *  terrain, and above it roughness saturates on its own. */
        const val SPEED_FULL_MS = 6f

        const val ENGINE_IDLE_BED = 0.10f
        const val ENGINE_FULL_BED = 0.52f
        const val TERRAIN_BED = 0.55f

        /** Newton-seconds of normal impulse, in excess of carrying the car's weight, that separate a
         *  bump from the ordinary business of resting on a slope. `CarState.impactImpulse` sits under
         *  5 at rest and peaks past 100 on a 10 m/s landing. */
        const val BUMP_IMPULSE = 45f

        /** Half-intensity impulse for the saturating curve. */
        const val IMPACT_HALF = 140f

        /** Even the gentlest touchdown is felt; only its scale varies. */
        const val IMPACT_FLOOR = 0.28f

        /**
         * Impulse → cue amplitude, saturating. A hyperbola rather than a clamped ratio because the
         * interesting range spans a decade — a kerb and a forty-metre drop are both landings — and a
         * linear map would put every real impact either at the floor or at the ceiling.
         */
        fun impactIntensity(impulse: Float): Float {
            if (!impulse.isFinite() || impulse <= 0f) return IMPACT_FLOOR
            val k = impulse / (impulse + IMPACT_HALF)
            return (IMPACT_FLOOR + (1f - IMPACT_FLOOR) * k).coerceIn(0f, 1f)
        }
    }
}

/**
 * All the frame loop may do to the senses: describe a frame, or say that there is not one.
 *
 * Narrower than [GameFeel] on purpose. The lifecycle verbs — above all [GameFeel.release], the alarm
 * interlock — belong to the composition, which is where the alarm is observed and where the surface's
 * lifetime is known; handing the loop a type that could release the actuator would make "who gave the
 * vibrator back, and when" a question about two files instead of one.
 */
internal interface FeelSink {
    /** One simulated frame. */
    fun frame(s: CarState)

    /** No frame: the world is held. */
    fun hold()

    companion object {
        val None = object : FeelSink {
            override fun frame(s: CarState) = Unit
            override fun hold() = Unit
        }
    }
}

/**
 * The sensory layer, entire: the frame mapping above plus the lifecycle the two surfaces need.
 *
 * [release] is **the alarm interlock**, and it is why this class exists rather than the screen poking
 * the mixer and the synth separately: an alarm has to take BOTH surfaces in one place, and the
 * vibrator has to be handed back rather than turned down. See [HapticMixer.release] for why "handed
 * back" is the whole of it and there is deliberately no `cancel()` anywhere in the path.
 */
internal class GameFeel(
    private val audio: GameAudio?,
    private val haptics: HapticMixer,
) : FeelSink {
    private val tracker = FeelTracker()

    /** One simulated frame. Called from the game thread only. */
    override fun frame(s: CarState) {
        val f = tracker.observe(s)
        haptics.bed(f.bed, f.texture)
        f.cue?.let { haptics.cue(it, f.cueIntensity) }

        val synth = audio?.synth ?: return
        synth.rpm = s.rpm
        synth.load = s.throttleApplied
        synth.engine = f.engine
        f.sfx?.let { synth.trigger(it, f.sfxIntensity) }
    }

    /** The world is frozen but the surface is still ours — a modal, the terminal card, a placement
     *  frame. Silence both layers without giving anything back. */
    override fun hold() {
        haptics.bed(0f)
        audio?.let { it.synth.engine = 0f }
    }

    /** An alarm is up: silence the sound and hand the actuator back entirely. */
    fun release() {
        hold()
        haptics.release()
        audio?.release()
    }

    /** The alarm cleared and the user came back to the run. */
    fun resume() {
        haptics.resume()
        audio?.resume()
    }

    /** The screen is going away. The mixer and the track are closed by their own composition scopes;
     *  this only makes sure the last frame does not linger while that happens. */
    fun close() {
        hold()
        haptics.release()
        audio?.release()
    }

    companion object {
        /** The mute feel layer: what a device that refused an `AudioTrack` and has no vibrator gets.
         *  Every call is a branch. */
        val None = GameFeel(null, HapticMixer.None)
    }
}
