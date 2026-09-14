package com.t1dm.feature.game

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/** [render] allocates nothing; phase stays `[0,1)`. Control via volatile fields/bitmask, no lock */
class GameSynth(private val sampleRate: Int) {


    /** Crank speed; drives pitch and harmonic content. */
    @Volatile
    var rpm = 0f

    /** Throttle in `[0, 1]`; a timbre, not a volume. */
    @Volatile
    var load = 0f

    /** Engine voice level in `[0, 1]`. Zero silences it without stopping the generator. */
    @Volatile
    var engine = 0f

    /** Output fade target in `[0, 1]`. Ramped, never switched. */
    @Volatile
    var master = 0f

    private val pending = AtomicInteger(0)
    private val sfxGain = FloatArray(GameSfx.entries.size) { 1f }

    /** Lossy by design: two of the same sound inside one buffer collapse into one. */
    fun trigger(sfx: GameSfx, intensity: Float = 1f) {
        sfxGain[sfx.ordinal] = intensity.coerceIn(0f, 1f)
        pending.getAndUpdate { it or (1 shl sfx.ordinal) }
    }


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


    private var enginePhase = 0f
    private var engineF0 = IDLE_RPM / RPM_PER_HZ
    private var engineLevel = 0f
    private var engineLoad = 0f
    private var engineGrit = 0f
    private var masterNow = 0f

    /** Drop every voice and every ramp, so a resumed run does not restart mid-crash. */
    fun reset() {
        life.fill(0)
        pending.set(0)
        engineLevel = 0f
        masterNow = 0f
        engineGrit = 0f
    }

    /** [frames] mono samples into [out]. Audio thread only, once per HAL burst. */
    fun render(out: FloatArray, frames: Int) {
        drainTriggers()

        val rev = ((rpm - IDLE_RPM) / (REV_FULL_RPM - IDLE_RPM)).coerceIn(0f, 1f)
        val f0Target = (rpm.coerceIn(0f, MAX_RPM) / RPM_PER_HZ).coerceAtLeast(12f)
        val levelTarget = engine.coerceIn(0f, 1f)
        val loadTarget = load.coerceIn(0f, 1f)
        val masterTarget = master.coerceIn(0f, 1f)

        // Harmonic content follows revs and load, not volume: a labouring engine is brighter.
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
                // Induction roar, one-poled so it is texture and not hiss.
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
                // 2 ms rise, else a landing steps; reclaim test is the decay branch below.
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

            // Hard limit, not soft clip: the levels above are budgeted to stay inside unity.
            out[i] = (s * masterNow).coerceIn(-1f, 1f)
        }
    }


    private fun drainTriggers() {
        var bits = pending.getAndSet(0)
        while (bits != 0) {
            val ordinal = Integer.numberOfTrailingZeros(bits)
            bits = bits and (bits - 1)
            spawn(GameSfx.entries[ordinal], sfxGain[ordinal])
        }
    }

    /** Pitched clear of the engine bed: acquisitions above its reach, collisions below it. */
    private fun spawn(sfx: GameSfx, gain: Float) = when (sfx) {
        GameSfx.Coin -> {
            voice(f0 = 988f, f1 = 988f, durS = 0.07f, amp = 0.26f * gain, timbre = 0.35f)
            voice(f0 = 1319f, f1 = 1319f, durS = 0.20f, amp = 0.28f * gain, timbre = 0.30f, delayS = 0.055f)
        }
        GameSfx.Landing -> {
            voice(f0 = 150f, f1 = 52f, durS = 0.24f, amp = 0.62f * gain, timbre = 0.25f)
            voice(f0 = 0f, f1 = 0f, durS = 0.07f, amp = 0.22f * gain, noise = 1f)
        }
        GameSfx.Crash -> {
            voice(f0 = 110f, f1 = 36f, durS = 0.50f, amp = 0.68f * gain, timbre = 0.40f)
            voice(f0 = 0f, f1 = 0f, durS = 0.34f, amp = 0.52f * gain, noise = 1f)
        }
        // A click, not a thud: the club meets the ball for a couple of milliseconds.
        GameSfx.Strike -> {
            voice(f0 = 1_760f, f1 = 620f, durS = 0.05f, amp = 0.40f * gain, timbre = 0.55f)
            voice(f0 = 0f, f1 = 0f, durS = 0.02f, amp = 0.30f * gain, noise = 1f)
        }
        // Shorter and higher than Landing: turf under a small ball, and it repeats.
        GameSfx.Bounce -> {
            voice(f0 = 320f, f1 = 160f, durS = 0.09f, amp = 0.34f * gain, timbre = 0.20f)
            voice(f0 = 0f, f1 = 0f, durS = 0.03f, amp = 0.16f * gain, noise = 1f)
        }
        // Noise that opens then shuts, with a low gulp under it.
        GameSfx.Splash -> {
            voice(f0 = 0f, f1 = 0f, durS = 0.26f, amp = 0.42f * gain, noise = 1f)
            voice(f0 = 240f, f1 = 90f, durS = 0.22f, amp = 0.28f * gain, timbre = 0.15f, delayS = 0.02f)
        }
        // A rattle in the cup: two knocks, the second quieter.
        GameSfx.Holed -> {
            voice(f0 = 660f, f1 = 660f, durS = 0.06f, amp = 0.30f * gain, timbre = 0.30f)
            voice(f0 = 494f, f1 = 494f, durS = 0.10f, amp = 0.22f * gain, timbre = 0.25f, delayS = 0.07f)
            voice(f0 = 988f, f1 = 988f, durS = 0.26f, amp = 0.26f * gain, timbre = 0.20f, delayS = 0.16f)
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
        // Per-sample ratio, so the sweep is exponential.
        this.glide[v] = if (f0 > 0f && f1 > 0f && f0 != f1) exp(ln(f1 / f0) / frames) else 1f
        peak[v] = amp
        env[v] = 0f
        // -60 dB by the nominal duration; `life` reclaims the slot a little after.
        decay[v] = exp(DECAY_LN / frames)
        this.timbre[v] = timbre
        this.noise[v] = noise
        lowpass[v] = 0f
        attack[v] = ATTACK_FRAMES
        delay[v] = (delayS * sampleRate).toInt()
        life[v] = (frames * 1.3f).toInt() + delay[v]
        rng[v] = nextSeed()
    }

    /** Steals the quietest slot: a fixed pool, no allocation, no failure mode. */
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

    /** xorshift32, per voice. */
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

        /** Idle; [MAX_RPM] is a guard against an absurd tuning, not a range anything reaches. */
        const val IDLE_RPM = 800f
        const val MAX_RPM = 9_000f

        /** REACHABLE ceiling, not [MAX_RPM]: else it never leaves its first brightness fifth. */
        const val REV_FULL_RPM = TOP_RPM

        /** Crank speed to fundamental. Idle lands at 40 Hz; [REV_FULL_RPM] lands at 132 Hz. */
        const val RPM_PER_HZ = 20f

        const val ENGINE_TRIM = 0.34f

        /** One-pole per-sample: at 48 kHz, ~25 ms pitch, ~12 ms gain, ~30 ms master. */
        const val PITCH_GLIDE = 0.0008f
        const val GAIN_GLIDE = 0.0018f
        const val MASTER_GLIDE = 0.0007f

        const val ATTACK_FRAMES = 96
        val DECAY_LN = ln(0.001f)

        const val TABLE_BITS = 10
        const val TABLE_SIZE = 1 shl TABLE_BITS
        const val INV_INT = 1f / Int.MAX_VALUE

        val SINE = FloatArray(TABLE_SIZE + 1) { sin(2.0 * PI * it / TABLE_SIZE).toFloat() }

        /** Table, not `kotlin.math.sin`: five lookups per sample for the engine alone. */
        fun sine(phase: Float): Float {
            val p = phase - floor(phase)
            val x = p * TABLE_SIZE
            val i = x.toInt().coerceIn(0, TABLE_SIZE - 1)
            return SINE[i] + (SINE[i + 1] - SINE[i]) * (x - i)
        }
    }
}

/** Ordinals index [GameSynth]'s trigger mask; nothing persists them. */
enum class GameSfx {
    Coin,
    Landing,
    Crash,
    Strike,
    Bounce,
    Splash,
    Holed,
}
