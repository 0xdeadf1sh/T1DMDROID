package com.t1dm.core.design

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt

/**
 * UI interaction haptics only. Never fold into `:alerts`' `VibrationActuator`: that is the §3.6-A
 * alarm path, and safety actuation may not be attenuated by a UI comfort preference. Not Compose's
 * `HapticFeedbackType` — 1.7.6 carries only LongPress and TextHandleMove, and the BOM is pinned.
 */

/** [nominalMs] is roughly what each occupies on the actuator; it exists only to synthesise the
 *  [HapticWaveform] fallback. */
enum class HapticPrimitive(internal val nominalMs: Long) {
    CLICK(20),
    TICK(10),
    LOW_TICK(14),
    THUD(40),
    SPIN(60),
    QUICK_RISE(35),
    QUICK_FALL(30),
}

/** [delayMs] is measured from the END of the preceding step. */
data class HapticStep(
    val primitive: HapticPrimitive,
    val amplitude: Float,
    val delayMs: Int = 0,
)

/** One [HapticEvent]'s shape at reference intensity. */
class HapticRecipe internal constructor(val steps: List<HapticStep>) {
    internal val primitives: Set<HapticPrimitive> = steps.mapTo(LinkedHashSet()) { it.primitive }
}

private fun recipe(vararg steps: HapticStep) = HapticRecipe(steps.toList())

private fun step(primitive: HapticPrimitive, amplitude: Float, delayMs: Int = 0) =
    HapticStep(primitive, amplitude, delayMs)

/** [minIntervalMs] floors re-firing. It is not a substitute for quantising the value at the call
 *  site — see [HapticDetent]. */
enum class HapticEvent(internal val recipe: HapticRecipe, internal val minIntervalMs: Long = 0) {
    Tap(recipe(step(HapticPrimitive.CLICK, 0.50f))),

    NavSwitch(
        recipe(
            step(HapticPrimitive.TICK, 0.35f),
            step(HapticPrimitive.CLICK, 0.55f, delayMs = 30),
        ),
    ),

    ToggleOn(
        recipe(
            step(HapticPrimitive.QUICK_RISE, 0.40f),
            step(HapticPrimitive.CLICK, 0.70f),
        ),
    ),

    ToggleOff(
        recipe(
            step(HapticPrimitive.CLICK, 0.60f),
            step(HapticPrimitive.QUICK_FALL, 0.40f),
        ),
    ),

    SegmentTick(recipe(step(HapticPrimitive.LOW_TICK, 0.25f)), minIntervalMs = SEGMENT_MIN_MS),

    ScrubTick(recipe(step(HapticPrimitive.TICK, 0.30f)), minIntervalMs = SCRUB_MIN_MS),

    /** The call site must edge-trigger this, or a pan held at the bound drones. */
    EdgeStop(recipe(step(HapticPrimitive.LOW_TICK, 0.60f)), minIntervalMs = EDGE_MIN_MS),

    LongPress(recipe(step(HapticPrimitive.CLICK, 0.90f))),

    DragStart(recipe(step(HapticPrimitive.QUICK_RISE, 0.50f))),

    DragEnd(
        recipe(
            step(HapticPrimitive.QUICK_FALL, 0.45f),
            step(HapticPrimitive.CLICK, 0.55f),
        ),
    ),

    StrokeStart(recipe(step(HapticPrimitive.LOW_TICK, 0.35f))),

    StrokeEnd(recipe(step(HapticPrimitive.TICK, 0.25f))),

    Confirm(
        recipe(
            step(HapticPrimitive.TICK, 0.45f),
            step(HapticPrimitive.CLICK, 0.75f, delayMs = 70),
        ),
    ),

    Commit(
        recipe(
            step(HapticPrimitive.CLICK, 0.70f),
            step(HapticPrimitive.THUD, 1.00f, delayMs = 50),
        ),
    ),

    Reject(
        recipe(
            step(HapticPrimitive.CLICK, 0.80f),
            step(HapticPrimitive.CLICK, 0.80f, delayMs = 55),
        ),
    ),

    Warn(
        recipe(
            step(HapticPrimitive.SPIN, 0.60f),
            step(HapticPrimitive.THUD, 0.70f),
        ),
    ),
}

private const val SEGMENT_MIN_MS = 40L
private const val SCRUB_MIN_MS = 28L
private const val EDGE_MIN_MS = 120L

/** [scale] multiplies every step amplitude; [STRONG] exceeds 1 deliberately, and the product is
 *  clamped at render time. */
enum class HapticStrength(val scale: Float, val displayName: String) {
    OFF(0f, "Off"),
    SUBTLE(0.55f, "Subtle"),
    STANDARD(1.0f, "Standard"),
    STRONG(1.4f, "Strong");

    companion object {
        val DEFAULT = STANDARD

        fun forKey(key: String?): HapticStrength = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** Below this the LRA moves without being felt. */
private const val MIN_AMPLITUDE = 0.05f

class HapticWaveform internal constructor(val timingsMs: LongArray, val amplitudes: IntArray)

/** `null` from [hapticPlan] means play nothing. */
internal sealed interface HapticPlan {
    class Primitives(val steps: List<HapticStep>) : HapticPlan
    class Waveform(val waveform: HapticWaveform) : HapticPlan
}

internal fun HapticRecipe.scaled(strength: HapticStrength): List<HapticStep> =
    steps.map { it.copy(amplitude = (it.amplitude * strength.scale).coerceIn(MIN_AMPLITUDE, 1f)) }

/** Each step becomes an off-segment of its delay then an on-segment of the primitive's nominal
 *  duration. The envelope is lost; the rhythm survives. */
internal fun List<HapticStep>.toWaveform(): HapticWaveform {
    val timings = LongArray(size * 2)
    val amplitudes = IntArray(size * 2)
    forEachIndexed { i, s ->
        timings[i * 2] = s.delayMs.toLong()
        amplitudes[i * 2] = 0
        timings[i * 2 + 1] = s.primitive.nominalMs
        amplitudes[i * 2 + 1] = (s.amplitude * 255).roundToInt().coerceIn(1, 255)
    }
    return HapticWaveform(timings, amplitudes)
}

internal fun hapticPlan(
    event: HapticEvent,
    strength: HapticStrength,
    primitivesSupported: (Set<HapticPrimitive>) -> Boolean,
): HapticPlan? {
    if (strength == HapticStrength.OFF) return null
    val steps = event.recipe.scaled(strength)
    return if (primitivesSupported(event.recipe.primitives)) HapticPlan.Primitives(steps)
    else HapticPlan.Waveform(steps.toWaveform())
}

/**
 * Never throws: an absent, busy or policy-blocked vibrator must not take a tap with it. One instance
 * per process; [strength] is a plain field, not composition state, so changing it recomposes nothing.
 */
@Stable
class T1dmHaptics internal constructor(
    private val vibrator: Vibrator?,
    strength: HapticStrength = HapticStrength.DEFAULT,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    @Volatile
    var strength: HapticStrength = strength

    // Main thread only. A torn read drops or duplicates one tick at worst, so neither is synchronised.
    private val lastFiredMs = LongArray(HapticEvent.entries.size)
    private val composable = arrayOfNulls<Boolean>(HapticEvent.entries.size)

    fun perform(event: HapticEvent) {
        val level = strength
        if (level == HapticStrength.OFF || vibrator == null) return
        if (event.minIntervalMs > 0) {
            val now = nowMs()
            if (now - lastFiredMs[event.ordinal] < event.minIntervalMs) return
            lastFiredMs[event.ordinal] = now
        }
        play(event, level)
    }

    /** [on] is the state being ADOPTED, as Material's `onCheckedChange` reports it — easily written
     *  backwards. */
    fun toggled(on: Boolean) = perform(if (on) HapticEvent.ToggleOn else HapticEvent.ToggleOff)

    /** Ignores the stored strength: the settings chip must be felt before the kv write round-trips. */
    fun preview(strength: HapticStrength, event: HapticEvent = HapticEvent.Confirm) {
        if (strength == HapticStrength.OFF || vibrator == null) return
        play(event, strength)
    }

    private fun play(event: HapticEvent, level: HapticStrength) {
        val plan = hapticPlan(event, level) { supportsComposition(event) } ?: return
        runCatching {
            val effect = when (plan) {
                is HapticPlan.Primitives -> {
                    val c = VibrationEffect.startComposition()
                    plan.steps.forEach { c.addPrimitive(androidId(it.primitive), it.amplitude, it.delayMs) }
                    c.compose()
                }
                is HapticPlan.Waveform ->
                    VibrationEffect.createWaveform(plan.waveform.timingsMs, plan.waveform.amplitudes, -1)
            }
            vibrator?.vibrate(effect)
        }
    }

    private fun supportsComposition(event: HapticEvent): Boolean =
        composable[event.ordinal] ?: run {
            val ids = event.recipe.primitives.map(::androidId).toIntArray()
            val ok = runCatching { vibrator?.areAllPrimitivesSupported(*ids) == true }.getOrDefault(false)
            composable[event.ordinal] = ok
            ok
        }

    companion object {
        val None = T1dmHaptics(vibrator = null, strength = HapticStrength.OFF)

        fun create(context: Context, strength: HapticStrength = HapticStrength.DEFAULT): T1dmHaptics {
            val vibrator = runCatching {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            }.getOrNull()
            return T1dmHaptics(vibrator, strength)
        }
    }
}

/** Internal only so `ContinuousHaptics.kt` shares this mapping rather than copying it. */
internal fun androidId(primitive: HapticPrimitive): Int = when (primitive) {
    HapticPrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
    HapticPrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
    HapticPrimitive.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    HapticPrimitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
    HapticPrimitive.SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
    HapticPrimitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    HapticPrimitive.QUICK_FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
}

/** Static: the instance never changes for the life of the process, only the field inside it. */
val LocalT1dmHaptics = staticCompositionLocalOf { T1dmHaptics.None }

/** Haptics reach a screen this way and not as a parameter: feature-module signatures stay free of
 *  `:core:design` types. */
@Composable
@ReadOnlyComposable
fun rememberT1dmHaptics(): T1dmHaptics = LocalT1dmHaptics.current

/** Root-only; see `T1dmTheme`. */
@Composable
internal fun rememberHapticsEngine(strength: HapticStrength): T1dmHaptics {
    val app = LocalContext.current.applicationContext
    val engine = remember(app) { T1dmHaptics.create(app) }
    SideEffect { engine.strength = strength }
    return engine
}

/** Foundation 1.7.6's `clickable` emits no haptic of its own, so nothing double-fires. The buzz
 *  precedes [onClick] unconditionally, including where the click is a no-op. */
@Composable
fun Modifier.hapticClickable(
    event: HapticEvent = HapticEvent.Tap,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier {
    val haptics = LocalT1dmHaptics.current
    return clickable(enabled = enabled, onClickLabel = onClickLabel, role = role) {
        haptics.perform(event)
        onClick()
    }
}

/**
 * Feed it the QUANTISED value — only the call site knows the grain. The first observation seeds
 * silently, so rendering a control never buzzes. A two-way-bound slider whose value also moves when
 * typed must drive this from the drag interaction, not from the value.
 */
@Stable
class HapticDetent internal constructor(
    private val haptics: T1dmHaptics,
    private val event: HapticEvent,
) {
    private var last: Any? = UNSEEDED

    fun at(bucket: Any?) {
        if (bucket == last) return
        val seeding = last === UNSEEDED
        last = bucket
        if (!seeding) haptics.perform(event)
    }

    fun reset() {
        last = UNSEEDED
    }

    private companion object {
        val UNSEEDED = Any()
    }
}

@Composable
fun rememberHapticDetent(event: HapticEvent = HapticEvent.SegmentTick): HapticDetent {
    val haptics = LocalT1dmHaptics.current
    return remember(haptics, event) { HapticDetent(haptics, event) }
}
