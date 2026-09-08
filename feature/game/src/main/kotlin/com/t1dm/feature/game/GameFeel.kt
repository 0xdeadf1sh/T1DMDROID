package com.t1dm.feature.game

import com.t1dm.core.design.HapticCue
import com.t1dm.core.design.HapticMixer
import com.t1dm.core.model.CarState
import com.t1dm.core.model.RunState
import kotlin.math.abs
import kotlin.math.min

/** Reused in place; the frame path allocates nothing. */
internal class FeelFrame {
    /** Sustained haptic amplitude in `[0, 1]`. */
    var bed = 0f

    /** Graininess in `[0, 1]` — the surface, not its loudness. */
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

/** Edges, not levels: a landing from !airborne alone would fire every frame the car sat still. */
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

        // Engine plus ground; the ground only while the wheels are on it and turning.
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

        // Terminal first, exclusively: a crash IS a landing, both would double an Impact in Shock.
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

        // A bump the suspension takes without the wheels ever leaving; the commonest impact.
        val bump = s.impactImpulse > BUMP_IMPULSE
        val bumped = bump && !bumping
        bumping = bump

        if (landed || bumped) {
            val hit = impactIntensity(s.impactImpulse)
            out.cue = HapticCue.Impact
            out.cueIntensity = hit
            // Touchdown only: a wheels-down bump re-arms several times a sec, reads as tapping.
            if (landed) {
                out.sfx = GameSfx.Landing
                out.sfxIntensity = hit
            }
        }
        return out
    }

    internal companion object {
        /** REACHABLE rev range, so rev spans [0,1] over the car, not the solver's 9000 rail. */
        const val IDLE_RPM = 800f
        const val MAX_RPM = TOP_RPM

        /** Where ground texture reaches full strength; above it roughness saturates on its own. */
        const val SPEED_FULL_MS = 6f

        const val ENGINE_IDLE_BED = 0.10f
        const val ENGINE_FULL_BED = 0.52f
        const val TERRAIN_BED = 0.55f

        /** Newton-seconds above car's weight; impactImpulse under 5 at rest, 100+ on landing. */
        const val BUMP_IMPULSE = 45f

        /** Half-intensity impulse for the saturating curve. */
        const val IMPACT_HALF = 140f

        /** Even the gentlest touchdown is felt; only its scale varies. */
        const val IMPACT_FLOOR = 0.28f

        /** Impulse to cue amplitude, saturating; a hyperbola, interesting range spans a decade. */
        fun impactIntensity(impulse: Float): Float {
            if (!impulse.isFinite() || impulse <= 0f) return IMPACT_FLOOR
            val k = impulse / (impulse + IMPACT_HALF)
            return (IMPACT_FLOOR + (1f - IMPACT_FLOOR) * k).coerceIn(0f, 1f)
        }
    }
}

/** Narrower than GameFeel on purpose: lifecycle verbs like release belong to the composition. */
internal interface FeelSink {
    fun frame(s: CarState)

    fun hold()

    companion object {
        val None = object : FeelSink {
            override fun frame(s: CarState) = Unit
            override fun hold() = Unit
        }
    }
}

/** release is the alarm interlock: alarm takes BOTH surfaces, vibrator handed back not off. */
internal class GameFeel(
    private val audio: GameAudio?,
    private val haptics: HapticMixer,
) : FeelSink {
    private val tracker = FeelTracker()

    /** Game thread only. */
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

    /** Silence both layers without giving the surface back. */
    override fun hold() {
        haptics.bed(0f)
        audio?.let { it.synth.engine = 0f }
    }

    /** An alarm is up: hand the actuator back. */
    fun release() {
        hold()
        haptics.release()
        audio?.release()
    }

    fun resume() {
        haptics.resume()
        audio?.resume()
    }

    /** The mixer and the track are closed by their own composition scopes. */
    fun close() {
        hold()
        haptics.release()
        audio?.release()
    }

    companion object {
        /** For a device that refused an `AudioTrack` and has no vibrator. */
        val None = GameFeel(null, HapticMixer.None)
    }
}
