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

/** A sustained haptic bed with prioritised transients over it. Two rules keep it clear of the §3.6-A
 *  alarm: every arm is FINITE and re-armed, so a wedged thread falls silent by itself, and nothing
 *  here ever calls [Vibrator.cancel], which is per-uid and would cancel `:alerts` with it. */

/** Ordered by [priority] — the only thing the mixer arbitrates on. */
enum class HapticCue(internal val priority: Int, internal val steps: List<HapticStep>) {
    Blip(0, listOf(HapticStep(HapticPrimitive.LOW_TICK, 0.35f))),

    /** Amplitude is scaled by the impulse at the call site. */
    Impact(1, listOf(HapticStep(HapticPrimitive.THUD, 0.85f))),

    Shock(
        2,
        listOf(
            HapticStep(HapticPrimitive.CLICK, 1.00f),
            HapticStep(HapticPrimitive.THUD, 1.00f, delayMs = 30),
            HapticStep(HapticPrimitive.SPIN, 0.80f, delayMs = 20),
        ),
    ),
    ;

    /** How long the cue occupies the actuator. */
    internal val durationMs: Long = steps.sumOf { it.delayMs + it.primitive.nominalMs }
}

/** ~2 frames at 60 fps: 17 binder calls a second rather than 60. */
internal const val CONTROL_PERIOD_MS = 60L

/** One re-arm's worth: longer than [CONTROL_PERIOD_MS] so arms overlap, short enough that a missed
 *  re-arm is inaudible rather than a drone. */
internal const val SUSTAIN_MS = 220L

/** The hush around a cue. */
internal const val DUCK_WINDOW_MS = 120L

/** What the bed is held to for the rest of the hush. Not zero: a bed that vanishes entirely and
 *  reappears reads as a fault. */
internal const val DUCK_LEVEL = 0.06f

/** Below this a scaled bed is not worth an actuator cycle, so it is rendered as silence. */
internal const val BED_FLOOR = 0.04f

/** A scaled cue step is floored here rather than lost. */
private const val CUE_MIN_AMPLITUDE = 0.05f

/** Returned rather than performed, so the arbitration can be driven by a test with no vibrator. */
internal sealed interface BedAction {
    /** [texture] is graininess in `[0, 1]`. */
    class Arm(val from: Float, val to: Float, val texture: Float) : BedAction

    /** Stop re-arming; the actuator falls silent on its own within [SUSTAIN_MS]. */
    data object Lapse : BedAction

    data object Idle : BedAction
}

/** Single-threaded by contract: every method except [release]/[resume] must be called from the ONE
 *  thread driving the surface, which is also the only thread that may touch the renderer. */
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

    /** The plain fields are cleared BEFORE the volatile flag: that write publishes the reset to the
     *  driving thread's read of `released`. Reversed, it could resume against a stale duck window. */
    fun resume() {
        // Re-enter from silence, so the bed swells back rather than snapping.
        rendered = 0f
        armedDucked = false
        nextArmMs = Long.MIN_VALUE
        cuePriority = Int.MIN_VALUE
        duckUntilMs = 0L
        cueEndsMs = 0L
        released = false
    }

    /** Called every frame; issues at most one re-arm per [CONTROL_PERIOD_MS], and exactly one
     *  [BedAction.Lapse] on the way down to silence. */
    fun bed(level: Float, texture: Float, strength: HapticStrength): BedAction {
        if (released || strength == HapticStrength.OFF) return lapse()
        this.texture = texture.coerceIn(0f, 1f)
        val now = nowMs()
        // One LRA has no mixing: a re-arm here would supersede the transient the hush exposes.
        if (now < cueEndsMs) return BedAction.Idle

        val want = (level.coerceIn(0f, 1f) * strength.scale).coerceAtMost(1f)
        val ducking = now < duckUntilMs
        val target = when {
            want < BED_FLOOR -> 0f
            ducking -> max(want * DUCK_LEVEL, CUE_MIN_AMPLITUDE)
            else -> want
        }
        // Silence is not paced: it must begin the instant the wheels leave.
        if (target <= 0f) return lapse()
        // The hush bypasses the pacing too, or the recovery lands a frame or two after the impact.
        if (now < nextArmMs && ducking == armedDucked) return BedAction.Idle
        val from = rendered
        rendered = target
        armedDucked = ducking
        nextArmMs = now + CONTROL_PERIOD_MS
        return BedAction.Arm(from, target, this.texture)
    }

    /** The scaled steps to play, or `null` when the cue was DROPPED — the only thing that ever
     *  happens to a cue that cannot play now. */
    fun cue(cue: HapticCue, intensity: Float, strength: HapticStrength): List<HapticStep>? {
        if (released || strength == HapticStrength.OFF) return null
        val now = nowMs()
        // Dropped, never deferred: a queued cue would describe a moment already gone.
        if (now < duckUntilMs && cue.priority <= cuePriority) return null
        cuePriority = cue.priority
        cueEndsMs = now + cue.durationMs
        duckUntilMs = now + DUCK_WINDOW_MS
        // The cue owns the actuator: the next arm re-enters from silence, once the cue has finished.
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

    /** Test seam. */
    internal val armedAt: Float get() = rendered
}

/** [HapticMixer] holds exactly one, chosen once at construction by [pickRenderer]. */
internal interface HapticRenderer {
    /** Re-arms for [SUSTAIN_MS], entering at [from] and settling at [to]. */
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

/** Emits as media (`USAGE_GAME`): an always-on bed must not arbitrate as UNKNOWN against the app's
 *  own UI ticks. The OS media-vibration intensity scales this layer as a result. */
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

/** The only path that sweeps rather than steps. `BasicEnvelopeBuilder`, not `WaveformEnvelopeBuilder`:
 *  the frequency variant demands a carrier inside the vibrator's `frequencyProfile`, which many LRAs
 *  report as `NaN`. The envelope must end at intensity 0, so every arm is self-expiring. */
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
        // Sharpness is the envelope's one timbral control, so texture maps onto it directly.
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

/** A train of `PRIMITIVE_LOW_TICK`s dense enough to fuse into a texture. It cannot sweep — the grain
 *  spacing carries texture instead. The head interpolates from the previous arm's amplitude, so
 *  successive arms fuse rather than pulse. */
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

        /** `compositionSizeMax` is not exposed publicly; 16 sits under every value seen in the wild. */
        const val MAX_GRAINS = 16

        /** How much of the train is spent arriving at [to]. */
        const val RAMP_FRACTION = 0.25f
    }
}

/** Stepped amplitude, and the only path the target actuator can take: no primitives, no envelopes,
 *  but `AMPLITUDE_CONTROL`. The head interpolates from [from] in 5 ms steps — the platform's own
 *  `rampStepDurationMs` — or an actuator with no braking reads a re-arm as a pulse train. */
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

    /** Without amplitude control the actuator is a switch, so the level becomes a duty cycle. */
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

/** The richest path the actuator can actually take — ordered by fidelity, not by API vintage. The
 *  target device runs 36 and still reports no envelopes and no primitives, so it lands on the third
 *  branch. */
private fun pickRenderer(vibrator: Vibrator): HapticRenderer {
    // Probed as one set: composing LOW_TICK but not SPIN would build a Shock it cannot render.
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

/** Intensity is NOT a field: [strengthOf] is read afresh on every call, and [HapticStrength.OFF]
 *  allocates nothing. Threading: [bed] and [cue] belong to the surface's own thread; [release],
 *  [resume] and [close] may be called from anywhere and only set a volatile flag. */
@Stable
class HapticMixer internal constructor(
    private val renderer: HapticRenderer?,
    private val strengthOf: () -> HapticStrength,
    nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    private val core = HapticMixCore(nowMs)

    @Volatile
    private var closed = false

    /** [level] is the amplitude in `[0, 1]`, [texture] its graininess. Call every frame; the mixer
     *  decides when that becomes an actuator write. */
    fun bed(level: Float, texture: Float = 0f) {
        val r = renderer ?: return
        if (closed) return
        when (val action = core.bed(level, texture, strengthOf())) {
            is BedAction.Arm -> r.arm(action.from, action.to, action.texture)
            // Ceasing to re-arm IS the stop.
            BedAction.Lapse, BedAction.Idle -> Unit
        }
    }

    /** [intensity] scales the cue's own recipe before the user's level. */
    fun cue(cue: HapticCue, intensity: Float = 1f) {
        val r = renderer ?: return
        if (closed) return
        core.cue(cue, intensity, strengthOf())?.let(r::oneShot)
    }

    /** The alarm interlock: hand the actuator back and refuse [bed] and [cue] until [resume]. Nothing
     *  is cancelled, so this cannot shorten, mask or clip the `:alerts` buzz it yields to. */
    fun release() = core.release()

    /** The bed swells from silence rather than snapping. */
    fun resume() {
        if (!closed) core.resume()
    }

    /** Permanent. Idempotent, and safe from any thread. */
    fun close() {
        closed = true
        core.release()
    }

    companion object {
        /** No vibrator, no allocation. */
        val None = HapticMixer(renderer = null, strengthOf = { HapticStrength.OFF })

        internal fun create(context: Context, strengthOf: () -> HapticStrength): HapticMixer {
            val vibrator = runCatching {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            }.getOrNull() ?: return HapticMixer(null, strengthOf)
            return HapticMixer(pickRenderer(vibrator), strengthOf)
        }
    }
}

/** Closed when the composition leaves. Intensity is read through [LocalT1dmHaptics], so a change of
 *  `ui.haptics` mid-run lands on the next frame with no recomposition. */
@Composable
fun rememberHapticMixer(): HapticMixer {
    val engine = LocalT1dmHaptics.current
    val app = LocalContext.current.applicationContext
    val mixer = remember(engine, app) { HapticMixer.create(app) { engine.strength } }
    DisposableEffect(mixer) { onDispose { mixer.close() } }
    return mixer
}
