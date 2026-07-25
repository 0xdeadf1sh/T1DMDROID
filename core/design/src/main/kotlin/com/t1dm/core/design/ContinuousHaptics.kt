package com.t1dm.core.design

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The app's **second** haptic modality: a SUSTAINED layer, and a mixer that owns the actuator for as
 * long as one is wanted.
 *
 * `Haptics.kt` renders punctuation — a tap, a detent, a commit — and every effect it builds is a
 * one-shot (`createWaveform(…, repeat = -1)`); nothing there can hold. A driving surface needs the
 * opposite: a bed that is *always on* and whose amplitude tracks a continuous quantity, against which
 * discrete events (a landing, a collision) read as punctuation. That is a different problem with a
 * different failure mode, so it is a different file — but the SAME user intensity ([HapticStrength],
 * `ui.haptics`) governs both, and [HapticStrength.OFF] is a hard, allocation-free gate here too.
 *
 * Four decisions are load-bearing:
 *
 *  1. **The sustained layer is FINITE and re-armed, never an infinite `repeat >= 0` loop.** A looping
 *     waveform ends only at an explicit `Vibrator.cancel()`, which puts a droning actuator one wedged
 *     thread — a stalled frame loop, a process frozen mid-frame, a dropped teardown — away from
 *     masking a §3.6-A alarm buzz. Each re-arm here issues at most [SUSTAIN_MS] of vibration and the
 *     mixer re-issues every [CONTROL_PERIOD_MS]; stop re-arming, for any reason at all, and the
 *     actuator falls silent by itself inside a fifth of a second. Continuity costs nothing, because
 *     the platform supersedes a running effect on the next `vibrate()` and the [HapticRenderer]
 *     re-enters at the amplitude it left off at.
 *
 *  2. **Nothing here ever calls [Vibrator.cancel].** `cancel()` is per-*uid*, not per-effect: the
 *     public SDK exposes no usage filter (`cancel(int)` is `@SystemApi`), so a game cancelling "its"
 *     bed would equally cancel an urgent-low buzz from `:alerts`' `VibrationActuator` that had already
 *     superseded it. Releasing by *ceasing to re-arm* (decision 1) is strictly weaker in what it can
 *     touch and therefore strictly safer — it is incapable of shortening an alarm. This is why
 *     [HapticMixer.release] can be called from anywhere at any time without a §3.6 argument.
 *
 *  3. **The mixer is the sole writer for the run, and ducking IS suppression.** One LRA has no
 *     streams: every `vibrate()` from the process supersedes whatever the process was playing. So a
 *     transient is not mixed under the bed, it *replaces* it — and the bed is held off the actuator
 *     while the cue plays and then held down to [DUCK_LEVEL] for the rest of [DUCK_WINDOW_MS], so a
 *     landing arrives against a hush instead of on top of a rumble. For the same reason a later cue is
 *     DROPPED rather than queued unless it outranks the one holding the window: a haptic delivered
 *     after the moment it described is worse than no haptic.
 *
 *  4. **It declares itself as media.** Every `vibrate()` elsewhere in the app goes out bare, and the
 *     platform then guesses (`usage: UNKNOWN` for multi-step effects, `TOUCH` for single-step ones).
 *     An always-on bed must not arbitrate as UNKNOWN — the lowest-importance class — against the app's
 *     own UI ticks, so it carries [VibrationAttributes] built from `AudioAttributes.USAGE_GAME`
 *     (⇒ `USAGE_MEDIA`). The side effect is deliberate: the OS media-vibration intensity now scales
 *     the game layer, which is right for entertainment and would not be right for an alarm.
 *
 * ONE API, three render paths ([HapticRenderer]): an API-36 envelope, a granular `PRIMITIVE_LOW_TICK`
 * stream, and an amplitude-stepped waveform. The selection is [pickRenderer]'s and the caller never
 * sees it — see that function for which hardware actually lands where.
 */

// ── the transient vocabulary ───────────────────────────────────────────────────────────────────

/**
 * The punctuation of the sustained layer, ordered by [priority] — the ONLY thing the mixer arbitrates
 * on. Deliberately generic (`:core:design` knows nothing about cars): a pickup is a [Blip], a landing
 * is an [Impact], a collision is a [Shock], and any other continuous surface gets the same three.
 *
 * The recipes escalate in weight and in length, so the ranking is legible under the thumb and not only
 * in the source: a blip is one near-weightless tick, an impact is a single lump of weight, and a shock
 * is the only thing in the app that ends in a churn.
 */
enum class HapticCue(internal val priority: Int, internal val steps: List<HapticStep>) {
    /** Something small was acquired — a collectable, a checkpoint. */
    Blip(0, listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.35f))),

    /** A body arriving: a landing, a hard bump. Amplitude is scaled by the impulse at the call site. */
    Impact(1, listOf(HapticStep(HapticPrimitive.THUD, 0.85f))),

    /** A collision — terminal, and the loudest thing this layer can say. */
    Shock(
        2,
        listOf(
            HapticStep(HapticPrimitive.CLICK, 1.00f),
            HapticStep(HapticPrimitive.THUD, 1.00f, delayMs = 30),
            HapticStep(HapticPrimitive.SPIN, 0.80f, delayMs = 20),
        ),
    ),
    ;

    /** How long the cue occupies the actuator — the contention window's lower bound. */
    internal val durationMs: Long = steps.sumOf { it.delayMs + it.primitive.nominalMs }
}

// ── the pure mixing kernel (unit-tested; no Android types) ─────────────────────────────────────

/** ~2 frames at 60 fps. Fast enough that a change of surface is felt inside a car length; slow enough
 *  that the bed is 17 binder calls a second rather than 60. */
internal const val CONTROL_PERIOD_MS = 60L

/** One re-arm's worth of vibration, generously longer than [CONTROL_PERIOD_MS] so successive arms
 *  overlap and the bed has no gaps — and short enough that a missed re-arm is inaudible, not a drone
 *  (decision 1). */
internal const val SUSTAIN_MS = 220L

/** The hush around a cue. */
internal const val DUCK_WINDOW_MS = 120L

/** What the bed is held to for the remainder of the hush once the cue itself has finished. Not zero:
 *  a bed that vanishes entirely and reappears reads as a fault, where a bed pressed almost flat reads
 *  as the impact having taken the room. */
internal const val DUCK_LEVEL = 0.06f

/** Below this a scaled bed is not worth an actuator cycle, so it is rendered as silence. */
internal const val BED_FLOOR = 0.04f

/** A scaled cue step is floored here rather than lost, exactly as `Haptics.kt` floors a recipe. */
private const val CUE_MIN_AMPLITUDE = 0.05f

/** What the kernel decided the sustained layer should do. Returned rather than performed so the whole
 *  arbitration can be driven by a test with no vibrator anywhere in sight. */
internal sealed interface BedAction {
    /** Re-arm, entering at [from] and settling at [to]; [texture] is the graininess in `[0, 1]`. */
    class Arm(val from: Float, val to: Float, val texture: Float) : BedAction

    /** Stop re-arming. The actuator falls silent on its own within [SUSTAIN_MS]. */
    data object Lapse : BedAction

    /** Leave the actuator exactly as it is. */
    data object Idle : BedAction
}

/**
 * The arbitration, entire. Single-threaded by contract: every method except [release]/[resume] must be
 * called from the ONE thread driving the surface (for the minigame, `t1dm-game`), which is also the
 * only thread that may touch the renderer.
 *
 * [nowMs] is injected for the same reason `T1dmHaptics` injects it — the ducking window and the drop
 * rule are time-dependent, and a test that has to sleep to observe them is a test that will flake.
 */
internal class HapticMixCore(private val nowMs: () -> Long) {

    private var texture = 0f
    private var rendered = 0f
    private var armedDucked = false
    private var nextArmMs = Long.MIN_VALUE
    private var cueEndsMs = 0L
    private var duckUntilMs = 0L
    private var cuePriority = Int.MIN_VALUE

    @Volatile
    var released = false
        private set

    /** Written from the main thread (the alarm interlock); read by the driving thread each frame. */
    fun release() {
        released = true
    }

    /**
     * The plain fields are cleared BEFORE the volatile flag, and that order is the whole of this
     * class's cross-thread contract: `resume` runs on the main thread while the driving thread may be
     * inside [bed], and the volatile write is what publishes the reset state to the acquire-read of
     * `released` on the other side. Reversed, the driving thread could resume against the duck window
     * the alarm interrupted.
     */
    fun resume() {
        // Re-enter from silence rather than from whatever amplitude was current when the alarm cut in,
        // so the bed swells back instead of snapping to the level the run left off at.
        rendered = 0f
        armedDucked = false
        nextArmMs = Long.MIN_VALUE
        cuePriority = Int.MIN_VALUE
        duckUntilMs = 0L
        cueEndsMs = 0L
        released = false
    }

    /**
     * Drive the bed toward [level] with graininess [texture]. Called every frame; issues at most one
     * re-arm per [CONTROL_PERIOD_MS], and exactly one [BedAction.Lapse] on the way down to silence.
     */
    fun bed(level: Float, texture: Float, strength: HapticStrength): BedAction {
        if (released || strength == HapticStrength.OFF) return lapse()
        this.texture = texture.coerceIn(0f, 1f)
        val now = nowMs()
        // While the cue itself is on the actuator, nothing may be issued: one LRA has no mixing, so a
        // re-arm here would supersede the very transient the hush exists to expose (decision 3).
        if (now < cueEndsMs) return BedAction.Idle

        val want = (level.coerceIn(0f, 1f) * strength.scale).coerceAtMost(1f)
        val ducking = now < duckUntilMs
        val target = when {
            want < BED_FLOOR -> 0f
            ducking -> max(want * DUCK_LEVEL, CUE_MIN_AMPLITUDE)
            else -> want
        }
        // Silence is not paced: airborne must read as an ABSENCE that begins the instant the wheels
        // leave, and a bed lingering into the drop is exactly the contrast the landing depends on.
        if (target <= 0f) return lapse()
        // Entering and leaving the hush likewise bypass the pacing — a duck that took up to a control
        // period to lift would put the recovery a frame or two after the impact that caused it.
        if (now < nextArmMs && ducking == armedDucked) return BedAction.Idle
        val from = rendered
        rendered = target
        armedDucked = ducking
        nextArmMs = now + CONTROL_PERIOD_MS
        return BedAction.Arm(from, target, this.texture)
    }

    /**
     * Fire [cue] at [intensity], or refuse. Returns the scaled steps to play, or `null` when the cue
     * was DROPPED — which is the only thing that ever happens to a cue that cannot play now.
     */
    fun cue(cue: HapticCue, intensity: Float, strength: HapticStrength): List<HapticStep>? {
        if (released || strength == HapticStrength.OFF) return null
        val now = nowMs()
        // Anything arriving inside the window and no louder than what claimed it is dropped, never
        // deferred: by the time a queue drained, the landing it described would be a car length back.
        if (now < duckUntilMs && cue.priority <= cuePriority) return null
        cuePriority = cue.priority
        cueEndsMs = now + cue.durationMs
        duckUntilMs = now + DUCK_WINDOW_MS
        // The cue owns the actuator now, so the bed's next arm re-enters from silence, and may not do
        // so until the cue has finished sounding.
        rendered = 0f
        armedDucked = true
        nextArmMs = cueEndsMs
        val gain = intensity.coerceIn(0f, 1f) * strength.scale
        return cue.steps.map {
            it.copy(amplitude = (it.amplitude * gain).coerceIn(CUE_MIN_AMPLITUDE, 1f))
        }
    }

    private fun lapse(): BedAction {
        if (rendered <= 0f) return BedAction.Idle
        rendered = 0f
        armedDucked = false
        nextArmMs = Long.MIN_VALUE
        return BedAction.Lapse
    }

    /** Test seam: what the bed was last armed at. */
    internal val armedAt: Float get() = rendered
}

// ── the render paths ───────────────────────────────────────────────────────────────────────────

/**
 * How a decided [BedAction] and a scaled cue reach hardware. Three implementations exist and the
 * kernel above knows about none of them; [HapticMixer] holds exactly one, chosen once at construction
 * by [pickRenderer].
 */
internal interface HapticRenderer {
    /** Re-arm the sustained layer for [SUSTAIN_MS], entering at [from] and settling at [to]. */
    fun arm(from: Float, to: Float, texture: Float)

    fun oneShot(steps: List<HapticStep>)
}

private val GAME_ATTRIBUTES: VibrationAttributes by lazy {
    VibrationAttributes.Builder(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
    ).build()
}

/** Shared plumbing: the attributed emit (decision 4) and the one-shot path, which is `T1dmHaptics.play`
 *  applied to a [HapticCue]'s steps rather than a [HapticEvent]'s recipe. */
private abstract class VibratorRenderer(
    protected val vibrator: Vibrator,
    private val composes: Boolean,
) : HapticRenderer {

    protected fun emit(effect: VibrationEffect) {
        // Never throws: a vibrator that is absent, busy or policy-blocked must not take a frame with it.
        runCatching { vibrator.vibrate(effect, GAME_ATTRIBUTES) }
    }

    override fun oneShot(steps: List<HapticStep>) {
        val effect = runCatching {
            if (composes) {
                val c = VibrationEffect.startComposition()
                steps.forEach { c.addPrimitive(androidId(it.primitive), it.amplitude, it.delayMs) }
                c.compose()
            } else {
                val w = steps.toWaveform()
                VibrationEffect.createWaveform(w.timingsMs, w.amplitudes, -1)
            }
        }.getOrNull() ?: return
        emit(effect)
    }
}

/**
 * API 36's amplitude envelope — the only path that sweeps rather than steps.
 *
 * `BasicEnvelopeBuilder` is used in preference to `WaveformEnvelopeBuilder` because its control points
 * are (intensity, sharpness) and need no carrier frequency: the frequency variant demands a value
 * inside the vibrator's `frequencyProfile`, which a great many LRAs (the K90's included) report as
 * `NaN` — i.e. the richer-looking builder is the one that cannot be built. The envelope must end at
 * intensity 0, which is decision 1 for free: every arm is self-expiring by construction.
 */
@RequiresApi(36)
private class EnvelopeRenderer(
    vibrator: Vibrator,
    composes: Boolean,
    private val minPointMs: Long,
    private val maxPointMs: Long,
) : VibratorRenderer(vibrator, composes) {

    override fun arm(from: Float, to: Float, texture: Float) {
        val rise = point(CONTROL_PERIOD_MS)
        val hold = point(SUSTAIN_MS - 2 * CONTROL_PERIOD_MS)
        val fall = point(CONTROL_PERIOD_MS)
        // Sharpness is the envelope's one timbral control: a smooth surface is a dull swell, a rough
        // one is bright and edgy. It maps texture directly because nothing else here can express it.
        val sharp = (0.25f + 0.65f * texture).coerceIn(0f, 1f)
        val effect = runCatching {
            VibrationEffect.BasicEnvelopeBuilder()
                .setInitialSharpness(sharp)
                .addControlPoint(to.coerceIn(0f, 1f), sharp, rise)
                .addControlPoint(to.coerceIn(0f, 1f), sharp, hold)
                .addControlPoint(0f, sharp, fall)
                .build()
        }.getOrNull() ?: return
        emit(effect)
    }

    private fun point(ms: Long): Long = ms.coerceIn(minPointMs, maxPointMs)
}

/**
 * The granular stream: a train of `PRIMITIVE_LOW_TICK`s dense enough to fuse into a texture. A
 * primitive carries its own envelope, so this cannot sweep — but the grain spacing can, and a rough
 * surface is exactly a stream of closer, harder grains. The head of the train interpolates from the
 * amplitude the previous arm left at, so successive arms fuse rather than pulse.
 */
private class PrimitiveStreamRenderer(vibrator: Vibrator) : VibratorRenderer(vibrator, composes = true) {

    override fun arm(from: Float, to: Float, texture: Float) {
        val grain = (GRAIN_SMOOTH_MS - (GRAIN_SMOOTH_MS - GRAIN_ROUGH_MS) * texture).toInt().coerceAtLeast(8)
        val n = ((SUSTAIN_MS / grain).toInt()).coerceIn(1, MAX_GRAINS)
        val effect = runCatching {
            val c = VibrationEffect.startComposition()
            for (i in 0 until n) {
                val k = if (n == 1) 1f else i / (n - 1f)
                val ramped = from + (to - from) * k.coerceAtMost(RAMP_FRACTION) / RAMP_FRACTION
                val amp = (if (k < RAMP_FRACTION) ramped else to).coerceIn(CUE_MIN_AMPLITUDE, 1f)
                c.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    amp,
                    if (i == 0) 0 else grain,
                )
            }
            c.compose()
        }.getOrNull() ?: return
        emit(effect)
    }

    private companion object {
        const val GRAIN_SMOOTH_MS = 34f
        const val GRAIN_ROUGH_MS = 12f

        /** Compositions have a hardware size cap (`compositionSizeMax`) that the public API does not
         *  expose; 16 sits under every value seen in the wild and `compose()` is guarded anyway. */
        const val MAX_GRAINS = 16

        /** How much of the train is spent arriving at [to]. */
        const val RAMP_FRACTION = 0.25f
    }
}

/**
 * The amplitude-stepped waveform — and, on the target hardware, the ONLY path that runs at all: the
 * K90's Dimensity-9500 actuator reports `supportedPrimitives = []` and `mMaxEnvelopeEffectSize = 0`,
 * so it can neither compose nor sweep, but it does carry `AMPLITUDE_CONTROL` and honours per-step
 * amplitudes exactly.
 *
 * The head interpolates from [from] in 5 ms steps — the platform's own `rampStepDurationMs`, below
 * which nothing is gained — so a re-arm is a continuation rather than a restart; without it, an
 * actuator with no hardware braking reads a re-armed bed as a pulse train instead of a swell. The body
 * is one flat step when the surface is smooth and an alternating coarse/fine step pair when it is not,
 * which is what roughness sounds like through a chassis.
 */
private class WaveformRenderer(
    vibrator: Vibrator,
    composes: Boolean,
    private val amplitudeControl: Boolean,
) : VibratorRenderer(vibrator, composes) {

    override fun arm(from: Float, to: Float, texture: Float) {
        val timings = ArrayList<Long>(24)
        val amps = ArrayList<Int>(24)
        var spent = 0L

        val rampSteps = if (abs(to - from) < 0.02f) 0 else RAMP_STEPS
        for (i in 1..rampSteps) {
            timings += RAMP_STEP_MS
            amps += level(from + (to - from) * (i / rampSteps.toFloat()), texture)
            spent += RAMP_STEP_MS
        }

        val body = SUSTAIN_MS - spent
        if (texture < TEXTURE_FLOOR) {
            timings += body
            amps += level(to, texture)
        } else {
            val period = (BODY_SMOOTH_MS - (BODY_SMOOTH_MS - BODY_ROUGH_MS) * texture).toLong().coerceAtLeast(10L)
            val dip = to * (1f - 0.55f * texture)
            var left = body
            var high = true
            while (left > 0) {
                val d = minOf(period, left)
                timings += d
                amps += level(if (high) to else dip, texture)
                high = !high
                left -= d
            }
        }

        val effect = runCatching {
            VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1)
        }.getOrNull() ?: return
        emit(effect)
    }

    /** Without amplitude control the actuator is a switch, so the level becomes a duty cycle instead —
     *  a bed that at least still exists, rather than one silently rendered at full tilt. */
    private fun level(amplitude: Float, texture: Float): Int {
        val a = amplitude.coerceIn(0f, 1f)
        if (amplitudeControl) return (a * 255).roundToInt().coerceIn(1, 255)
        return if (a * (1f - 0.3f * texture) > PWM_THRESHOLD) 255 else 0
    }

    private companion object {
        const val RAMP_STEPS = 4
        const val RAMP_STEP_MS = 5L
        const val TEXTURE_FLOOR = 0.08f
        const val BODY_SMOOTH_MS = 44f
        const val BODY_ROUGH_MS = 14f
        const val PWM_THRESHOLD = 0.45f
    }
}

/**
 * Pick the richest path the actuator can actually take.
 *
 * The order is not the order of the APIs' vintage but of their fidelity, and the fallback is not
 * hypothetical: envelopes arrived in API 36 and the target device RUNS 36 while reporting no envelope
 * support and no primitives at all, so on the one phone this ships to the third branch is the whole
 * implementation. A device that reports envelopes gets a genuine sweep; one that composes but cannot
 * sweep gets the grain stream; everything else gets stepped amplitude.
 */
private fun pickRenderer(vibrator: Vibrator): HapticRenderer {
    // Every primitive BOTH paths can reach for, probed as one set: an actuator that composes a
    // LOW_TICK but not a SPIN would otherwise build a Shock out of a primitive it cannot render.
    val composes = runCatching {
        vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
            VibrationEffect.Composition.PRIMITIVE_SPIN,
        )
    }.getOrDefault(false)

    if (Build.VERSION.SDK_INT >= 36) {
        val info = runCatching {
            if (vibrator.areEnvelopeEffectsSupported()) vibrator.envelopeEffectInfo else null
        }.getOrNull()
        if (info != null && info.maxSize >= 3 && info.maxDurationMillis >= SUSTAIN_MS) {
            return EnvelopeRenderer(
                vibrator,
                composes,
                minPointMs = info.minControlPointDurationMillis.coerceAtLeast(1L),
                maxPointMs = info.maxControlPointDurationMillis.coerceAtLeast(1L),
            )
        }
    }
    if (composes) return PrimitiveStreamRenderer(vibrator)
    val amplitude = runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(false)
    return WaveformRenderer(vibrator, composes, amplitude)
}

// ── the engine ─────────────────────────────────────────────────────────────────────────────────

/**
 * The continuous-haptics engine: a sustained bed, prioritised transients over it, and one interlock
 * that hands the actuator back.
 *
 * Intensity is NOT a field here. [strengthOf] is read afresh on every call so the app-wide
 * `ui.haptics` level governs the game exactly as it governs a tap — there is deliberately no separate
 * game knob, and [HapticStrength.OFF] costs one enum comparison per frame and touches no hardware and
 * allocates nothing (see [HapticMixCore.bed], which returns before it has scaled anything).
 *
 * Threading: [bed] and [cue] belong to the surface's own thread; [release], [resume] and [close] may
 * be called from anywhere, and do nothing but set a volatile flag the driving thread reads next frame
 * (which is also why release can never race a `vibrate()` into an alarm — see decision 2).
 */
@Stable
class HapticMixer internal constructor(
    private val renderer: HapticRenderer?,
    private val strengthOf: () -> HapticStrength,
    nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    private val core = HapticMixCore(nowMs)

    @Volatile
    private var closed = false

    /**
     * Drive the sustained layer. [level] is the amplitude in `[0, 1]` — the rumble; [texture] is its
     * graininess, the surface under the wheels. Call every frame; the mixer decides when that becomes
     * an actuator write.
     */
    fun bed(level: Float, texture: Float = 0f) {
        val r = renderer ?: return
        if (closed) return
        when (val action = core.bed(level, texture, strengthOf())) {
            is BedAction.Arm -> r.arm(action.from, action.to, action.texture)
            // Nothing to do: ceasing to re-arm IS the stop (decision 1).
            BedAction.Lapse, BedAction.Idle -> Unit
        }
    }

    /** Fire a transient, or drop it. [intensity] scales the cue's own recipe before the user's level. */
    fun cue(cue: HapticCue, intensity: Float = 1f) {
        val r = renderer ?: return
        if (closed) return
        core.cue(cue, intensity, strengthOf())?.let(r::oneShot)
    }

    /**
     * **The alarm interlock.** Hand the actuator back entirely and refuse every subsequent [bed] and
     * [cue] until [resume]. The bed lapses within [SUSTAIN_MS] of the last arm and nothing is
     * cancelled, so this cannot shorten, mask or clip the `:alerts` buzz it is yielding to.
     */
    fun release() = core.release()

    /** Take the actuator back after a [release]. The bed swells from silence rather than snapping. */
    fun resume() {
        if (!closed) core.resume()
    }

    /** Permanent: the surface is gone. Idempotent, and safe from any thread. */
    fun close() {
        closed = true
        core.release()
    }

    companion object {
        /** The silent mixer — no vibrator, no allocation, every call a branch. */
        val None = HapticMixer(renderer = null, strengthOf = { HapticStrength.OFF })

        internal fun create(context: Context, strengthOf: () -> HapticStrength): HapticMixer {
            val vibrator = runCatching {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            }.getOrNull() ?: return HapticMixer(null, strengthOf)
            return HapticMixer(pickRenderer(vibrator), strengthOf)
        }
    }
}

/**
 * A mixer for the current composition, closed when it leaves.
 *
 * It reads its intensity through [LocalT1dmHaptics] rather than taking one, so a change of
 * `ui.haptics` mid-run is picked up on the next frame with no recomposition — the same reason
 * `T1dmHaptics.strength` is a plain field. Under a preview (or any un-hydrated tree) the ambient
 * engine is `T1dmHaptics.None` at [HapticStrength.OFF], so this is inert by construction.
 */
@Composable
fun rememberHapticMixer(): HapticMixer {
    val engine = LocalT1dmHaptics.current
    val app = LocalContext.current.applicationContext
    val mixer = remember(engine, app) { HapticMixer.create(app) { engine.strength } }
    DisposableEffect(mixer) { onDispose { mixer.close() } }
    return mixer
}
