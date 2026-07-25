package com.t1dm.feature.game

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * The minigame's synthesiser: every sound the game makes, computed a sample at a time.
 *
 * The app ships ZERO audio assets and this is what keeps it that way — the same choice
 * `FuneralToll` made for the DEATH toll, but a running engine is not a struck bell, so almost none of
 * its architecture carries over. A bell is one buffer rendered once; an engine has to sweep
 * continuously with the throttle, which means a real generator: fixed voices, a phase accumulator per
 * voice, and per-sample interpolation of every parameter that moves.
 *
 * **The whole class is deliberately free of Android types**, so the arithmetic — which is the part
 * that can be wrong without anyone hearing why — is unit-testable, and [GameAudio] is left holding
 * nothing but an `AudioTrack` and a thread.
 *
 * Three constraints shape the code and are worth stating, because each looks like premature
 * optimisation until the first underrun:
 *
 *  - **[render] allocates nothing.** Every voice is a slot in a parallel primitive array and the
 *    output buffer is the caller's. A GC pause inside the generator is a click; a GC pause caused BY
 *    the generator is a click sixty times a minute.
 *  - **No parameter is ever stepped at a block boundary.** Gain, engine pitch and the master fade are
 *    all interpolated per sample toward their targets. A block-rate step in any of them is an audible
 *    edge, and at 5.3 ms bursts there are 188 of those edges a second.
 *  - **Phase is kept, never recomputed from a time base.** `sin(2πft)` from an absolute `t` — the
 *    idiom `FuneralToll` uses, correct for a one-shot — loses float precision within seconds and
 *    clicks whenever `f` changes. Here every oscillator carries its own phase in `[0, 1)`.
 *
 * Control comes from the game thread through volatile fields and one atomic bitmask; the audio thread
 * owns everything else. There are no locks anywhere on the render path, because a lock the audio
 * thread can block on is an underrun waiting for a scheduling accident.
 */
class GameSynth(private val sampleRate: Int) {

    // ── the control surface: written by the game thread, read once per block ───────────────────

    /** Crank speed driving pitch and harmonic content. See `CarState.rpm` — a synthesised audio
     *  driver, and the only thing that makes acceleration sweep rather than step. */
    @Volatile
    var rpm = 0f

    /** Throttle in `[0, 1]`: how hard the engine is working, which is a timbre, not a volume. */
    @Volatile
    var load = 0f

    /** The engine voice's own level in `[0, 1]`. Zero silences it without stopping the generator. */
    @Volatile
    var engine = 0f

    /** The output fade target in `[0, 1]`. Ramped, never switched — this is the alarm interlock's
     *  and the focus listener's only control, and both must be inaudible in the taking. */
    @Volatile
    var master = 0f

    private val pending = AtomicInteger(0)
    private val sfxGain = FloatArray(GameSfx.entries.size) { 1f }

    /**
     * Ask for a one-shot. Cheap enough to call from the frame loop, and lossy by design: two of the
     * same sound inside one buffer collapse into one, because the buffer is 5 ms and nobody can hear
     * the difference between one landing and two 5 ms apart.
     */
    fun trigger(sfx: GameSfx, intensity: Float = 1f) {
        sfxGain[sfx.ordinal] = intensity.coerceIn(0f, 1f)
        pending.getAndUpdate { it or (1 shl sfx.ordinal) }
    }

    // ── voice pool ─────────────────────────────────────────────────────────────────────────────

    private val phase = FloatArray(VOICES)
    private val freq = FloatArray(VOICES)
    private val glide = FloatArray(VOICES)
    private val peak = FloatArray(VOICES)
    private val decay = FloatArray(VOICES)
    private val timbre = FloatArray(VOICES)
    private val noise = FloatArray(VOICES)
    private val lowpass = FloatArray(VOICES)
    private val env = FloatArray(VOICES)
    private val attack = IntArray(VOICES)
    private val delay = IntArray(VOICES)
    private val life = IntArray(VOICES)
    private val rng = IntArray(VOICES)
    private var rngSeed = 0x1234_5678

    // ── engine voice state ─────────────────────────────────────────────────────────────────────

    private var enginePhase = 0f
    private var engineF0 = IDLE_RPM / RPM_PER_HZ
    private var engineLevel = 0f
    private var engineLoad = 0f
    private var engineGrit = 0f
    private var masterNow = 0f

    /** Drop every voice and every ramp. Used when the surface is handed back and taken again, so a
     *  run does not resume mid-crash. */
    fun reset() {
        life.fill(0)
        pending.set(0)
        engineLevel = 0f
        masterNow = 0f
        engineGrit = 0f
    }

    /**
     * Fill [frames] mono samples of [out].
     *
     * Called from the audio thread only, once per HAL burst. The per-block work is deliberately tiny:
     * read the four control fields, compute the per-sample smoothing targets, drain the trigger mask.
     * Everything else is the inner loop.
     */
    fun render(out: FloatArray, frames: Int) {
        drainTriggers()

        val rev = ((rpm - IDLE_RPM) / (REV_FULL_RPM - IDLE_RPM)).coerceIn(0f, 1f)
        val f0Target = (rpm.coerceIn(0f, MAX_RPM) / RPM_PER_HZ).coerceAtLeast(12f)
        val levelTarget = engine.coerceIn(0f, 1f)
        val loadTarget = load.coerceIn(0f, 1f)
        val masterTarget = master.coerceIn(0f, 1f)

        // Harmonic content is a function of revs and load, not of volume: a labouring engine near the
        // limiter is BRIGHTER, and it is that shift — not the pitch alone — that makes a sweep read as
        // acceleration rather than as a siren.
        val h2 = 0.55f + 0.30f * loadTarget
        val h3 = 0.28f + 0.42f * rev
        val h4 = 0.12f + 0.38f * rev * loadTarget
        val h6 = 0.04f + 0.30f * rev * rev
        val norm = ENGINE_TRIM / (1f + h2 + h3 + h4 + h6)

        for (i in 0 until frames) {
            engineF0 += (f0Target - engineF0) * PITCH_GLIDE
            engineLevel += (levelTarget - engineLevel) * GAIN_GLIDE
            engineLoad += (loadTarget - engineLoad) * GAIN_GLIDE
            masterNow += (masterTarget - masterNow) * MASTER_GLIDE

            var s = 0f
            if (engineLevel > 1e-4f) {
                enginePhase += engineF0 / sampleRate
                if (enginePhase >= 1f) enginePhase -= floor(enginePhase)
                val p = enginePhase
                var e = sine(p) + h2 * sine(p * 2f) + h3 * sine(p * 3f) + h4 * sine(p * 4f) + h6 * sine(p * 6f)
                // A breath of induction roar under the harmonics, one-poled so it is texture and not
                // hiss. It tracks load, which is what distinguishes coasting from climbing.
                engineGrit += (white() - engineGrit) * 0.28f
                e += engineGrit * (0.06f + 0.16f * engineLoad)
                s += e * norm * engineLevel
            }

            for (v in 0 until VOICES) {
                if (life[v] <= 0) continue
                if (delay[v] > 0) {
                    delay[v]--
                    continue
                }
                life[v]--
                val age = attack[v]
                // A 2 ms rise on every voice. Without it a landing starts with a step discontinuity,
                // which is a click layered over the very sound meant to be a thump. The reclaim test
                // belongs to the DECAY branch alone: a voice one sample into its attack is still at
                // zero, and killing it there would silence every one-shot in the game.
                if (age > 0) {
                    attack[v] = age - 1
                    env[v] = peak[v] * (1f - (age - 1).toFloat() / ATTACK_FRAMES)
                } else {
                    env[v] *= decay[v]
                    if (env[v] < 1e-5f) {
                        life[v] = 0
                        continue
                    }
                }
                val e = env[v]
                val n = noise[v]
                var value = 0f
                if (n < 1f) {
                    phase[v] += freq[v] / sampleRate
                    if (phase[v] >= 1f) phase[v] -= floor(phase[v])
                    freq[v] *= glide[v]
                    val t = timbre[v]
                    value += (1f - n) * (sine(phase[v]) + t * sine(phase[v] * 2f)) / (1f + t)
                }
                if (n > 0f) {
                    lowpass[v] += (voiceNoise(v) - lowpass[v]) * 0.42f
                    value += n * lowpass[v]
                }
                s += value * e
            }

            // Hard-limit rather than soft-clip: the levels above are budgeted to stay inside unity and
            // a limiter that colours everything to save a rare overshoot is the wrong trade.
            out[i] = (s * masterNow).coerceIn(-1f, 1f)
        }
    }

    // ── one-shots ──────────────────────────────────────────────────────────────────────────────

    private fun drainTriggers() {
        var bits = pending.getAndSet(0)
        while (bits != 0) {
            val ordinal = Integer.numberOfTrailingZeros(bits)
            bits = bits and (bits - 1)
            spawn(GameSfx.entries[ordinal], sfxGain[ordinal])
        }
    }

    /**
     * The four sounds, as a handful of glided partials each.
     *
     * They are pitched and shaped to stay legible against the engine bed rather than to be pretty in
     * isolation: the two acquisitions sit an octave-and-a-half above anything the engine reaches, and
     * the two collisions live under it, so neither is ever masked by the thing it interrupts.
     */
    private fun spawn(sfx: GameSfx, gain: Float) = when (sfx) {
        // A rising major third, the arcade idiom, because it is the one everybody already knows.
        GameSfx.Coin -> {
            voice(f0 = 988f, f1 = 988f, durS = 0.07f, amp = 0.26f * gain, timbre = 0.35f)
            voice(f0 = 1319f, f1 = 1319f, durS = 0.20f, amp = 0.28f * gain, timbre = 0.30f, delayS = 0.055f)
        }
        // Weight arriving: a pitch dropping most of an octave in a fifth of a second, with a scuff of
        // filtered noise for the tyres.
        GameSfx.Landing -> {
            voice(f0 = 150f, f1 = 52f, durS = 0.24f, amp = 0.62f * gain, timbre = 0.25f)
            voice(f0 = 0f, f1 = 0f, durS = 0.07f, amp = 0.22f * gain, noise = 1f)
        }
        // The same gesture taken to its end — a longer boom under a burst of broadband noise.
        GameSfx.Crash -> {
            voice(f0 = 110f, f1 = 36f, durS = 0.50f, amp = 0.68f * gain, timbre = 0.40f)
            voice(f0 = 0f, f1 = 0f, durS = 0.34f, amp = 0.52f * gain, noise = 1f)
        }
    }

    private fun voice(
        f0: Float,
        f1: Float,
        durS: Float,
        amp: Float,
        timbre: Float = 0f,
        noise: Float = 0f,
        delayS: Float = 0f,
    ) {
        val v = freeVoice()
        val frames = (durS * sampleRate).toInt().coerceAtLeast(1)
        phase[v] = 0f
        freq[v] = f0
        // Glide is a per-sample RATIO, so a sweep is exponential — which is what a pitch drop has to be
        // to sound like one interval rather than like a fall off a cliff.
        this.glide[v] = if (f0 > 0f && f1 > 0f && f0 != f1) exp(ln(f1 / f0) / frames) else 1f
        peak[v] = amp
        env[v] = 0f
        // -60 dB by the nominal duration; `life` then reclaims the slot a little after.
        decay[v] = exp(DECAY_LN / frames)
        this.timbre[v] = timbre
        this.noise[v] = noise
        lowpass[v] = 0f
        attack[v] = ATTACK_FRAMES
        delay[v] = (delayS * sampleRate).toInt()
        life[v] = (frames * 1.3f).toInt() + delay[v]
        rng[v] = nextSeed()
    }

    /** The quietest slot, or a free one. A fixed pool with no allocation and no failure mode: a voice
     *  stolen from the tail of a decay is inaudible, a voice not played at all is not. */
    private fun freeVoice(): Int {
        var quietest = 0
        var quietestEnv = Float.MAX_VALUE
        for (v in 0 until VOICES) {
            if (life[v] <= 0) return v
            if (env[v] < quietestEnv) {
                quietestEnv = env[v]
                quietest = v
            }
        }
        return quietest
    }

    private fun nextSeed(): Int {
        rngSeed = rngSeed * 1_664_525 + 1_013_904_223
        return rngSeed or 1
    }

    /** xorshift32, per voice: deterministic, allocation-free, and good enough that a noise burst does
     *  not have an audible period. */
    private fun voiceNoise(v: Int): Float {
        var x = rng[v]
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng[v] = x
        return x * INV_INT
    }

    private fun white(): Float {
        rngSeed = rngSeed xor (rngSeed shl 13)
        rngSeed = rngSeed xor (rngSeed ushr 17)
        rngSeed = rngSeed xor (rngSeed shl 5)
        return rngSeed * INV_INT
    }

    private companion object {
        const val VOICES = 8

        /** Idle, and the solver's own rail on `rpm` — a guard against an absurd tuning, not a range
         *  anything reaches. It bounds the fundamental and nothing else. */
        const val IDLE_RPM = 800f
        const val MAX_RPM = 9_000f

        /** Where `rev` — which opens the harmonic content, not the volume — reaches 1. This has to be
         *  the REACHABLE ceiling and not [MAX_RPM], or the engine never leaves the first fifth of its
         *  brightness however hard the car is driven. See [TOP_RPM]. */
        const val REV_FULL_RPM = TOP_RPM

        /** Crank speed to fundamental. Chosen so idle sits at 40 Hz: low enough to be an engine, high
         *  enough that a phone speaker reproduces the fundamental at all rather than leaving only the
         *  harmonics. [REV_FULL_RPM] then lands at 132 Hz — an octave and a fifth of sweep, which is
         *  all a rev range of 3.3:1 affords under a linear map. */
        const val RPM_PER_HZ = 20f

        const val ENGINE_TRIM = 0.34f

        /** Per-sample one-pole coefficients. At 48 kHz these are roughly 25 ms for pitch, 12 ms for
         *  gain and 30 ms for the master fade — slow enough to be smooth, fast enough that a throttle
         *  stab is not late. */
        const val PITCH_GLIDE = 0.0008f
        const val GAIN_GLIDE = 0.0018f
        const val MASTER_GLIDE = 0.0007f

        const val ATTACK_FRAMES = 96
        val DECAY_LN = ln(0.001f)

        const val TABLE_BITS = 10
        const val TABLE_SIZE = 1 shl TABLE_BITS
        const val INV_INT = 1f / Int.MAX_VALUE

        val SINE = FloatArray(TABLE_SIZE + 1) { sin(2.0 * PI * it / TABLE_SIZE).toFloat() }

        /** Linear-interpolated table lookup. Five of these per sample for the engine alone, which is
         *  why it is a table and not `kotlin.math.sin`. */
        fun sine(phase: Float): Float {
            val p = phase - floor(phase)
            val x = p * TABLE_SIZE
            val i = x.toInt().coerceIn(0, TABLE_SIZE - 1)
            return SINE[i] + (SINE[i + 1] - SINE[i]) * (x - i)
        }
    }
}

/** The one-shot vocabulary. Ordinals index [GameSynth]'s trigger mask, so the order is load-bearing
 *  only to itself — nothing persists it. */
enum class GameSfx {
    Coin,
    Landing,
    Crash,
}
