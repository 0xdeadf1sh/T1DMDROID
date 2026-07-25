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
 * App-wide **interaction** haptics — the tactile twin of `Motion.kt`. Where motion collapses through
 * one flag, touch feedback flows through one vocabulary: a call site names the semantic event
 * ([HapticEvent] — what the interaction *meant*), never a primitive, and [T1dmHaptics] decides how
 * that reads on the K90's LRA and how loudly, from the single user intensity [HapticStrength]
 * (persisted as `ui.haptics`).
 *
 * Three decisions are load-bearing enough to record here, because each is the kind that gets
 * "simplified" away by a later reader who sees only its symptom:
 *
 *  1. **This is not `androidx.compose.ui.hapticfeedback.HapticFeedbackType`.** The Compose BOM is
 *     pinned at 2024.12.01 ⇒ compose-ui 1.7.6, whose entire haptic vocabulary is `LongPress` and
 *     `TextHandleMove` (the value class's constructor is `internal`, so it cannot be widened from
 *     outside). Confirm/Reject/ToggleOn/SegmentTick arrived in 1.8, and the BOM cannot move while
 *     `t1dm.android.compose.gradle.kts` force-pins foundation to 1.7.6 against the Coil3 `FlowRow`
 *     incident. Rendering the vocabulary ourselves also buys per-step amplitude — the whole point of
 *     an intensity knob.
 *
 *  2. **This is not `:alerts`' `VibrationActuator`, and must never be folded into it.** That actuator
 *     is the §3.6-A deterministic alarm path: an urgent-low buzz is *safety actuation* and may not be
 *     attenuated — let alone silenced — by a UI comfort preference. Two engines with two enums is the
 *     separation, not duplication; a shared one would put a single "Off" chip between the user and a
 *     hypo alarm. `VibrationPreset`'s ordinals are additionally frozen into the notification-channel
 *     ids (`AlertActuatorConfig.version()`), so its enum could not carry UI cases even if that were
 *     wanted.
 *
 *  3. **DEATH mode does not still these.** `LocalDeathMode` suppresses *glycaemic* warning surfaces
 *     (see `DangerBanner`). [HapticEvent.Warn] and [HapticEvent.Reject] describe an *interaction*
 *     outcome — an input the UI refused, a control that will not accept the press — not a clinical
 *     one, so they stay live in DEATH: the override is about defeating safety rails, not about making
 *     the app unresponsive to touch. Likewise, haptics are orthogonal to [LocalAnimationsEnabled]: a
 *     user who wants a static UI may still want to feel the toggle land, so they are a sibling
 *     setting, never a dependent of the motion flag.
 *
 * Because the composition path talks to the vibrator directly it bypasses the OS "touch feedback"
 * system setting, which makes the in-app intensity the *only* off switch — hence [HapticStrength.OFF]
 * is a hard, allocation-free early return in [T1dmHaptics.perform].
 */

// ── the vocabulary ─────────────────────────────────────────────────────────────────────────────

/**
 * The hardware primitives the app draws on. [nominalMs] is roughly what each occupies on the
 * actuator; it exists ONLY to synthesise the [HapticWaveform] fallback for a device (or a device
 * whose LRA lacks one of the richer primitives) that cannot compose, so the same recipe table
 * describes both render paths and the two can never drift.
 */
enum class HapticPrimitive(internal val nominalMs: Long) {
    CLICK(20),
    TICK(10),
    LOW_TICK(14),
    THUD(40),
    SPIN(60),
    QUICK_RISE(35),
    QUICK_FALL(30),
}

/** One primitive in a recipe. [delayMs] is measured from the END of the preceding step. */
data class HapticStep(
    val primitive: HapticPrimitive,
    val amplitude: Float,
    val delayMs: Int = 0,
)

/** An ordered gesture on the actuator: the shape of one [HapticEvent] at reference intensity. */
class HapticRecipe internal constructor(val steps: List<HapticStep>) {
    internal val primitives: Set<HapticPrimitive> = steps.mapTo(LinkedHashSet()) { it.primitive }
}

private fun recipe(vararg steps: HapticStep) = HapticRecipe(steps.toList())

private fun step(primitive: HapticPrimitive, amplitude: Float, delayMs: Int = 0) =
    HapticStep(primitive, amplitude, delayMs)

/**
 * Every tactile event the UI can express. The set is deliberately semantic and closed: a call site
 * that cannot find its meaning here should argue for a new case rather than reach for a primitive,
 * because the value of the layer is that two screens confirming something feel identical.
 *
 * The recipes below are chosen to be a *phonology* rather than fifteen unrelated buzzes — the pairs
 * mirror each other so the hand learns them:
 *  - [ToggleOn] rises into its click, [ToggleOff] falls away from one.
 *  - [DragStart] lifts (a rise with no impact), [DragEnd] sets down (a fall into an impact).
 *  - [Confirm] is light-then-firm (ascending, "yes"), [Reject] is two flat equal knocks (curt, "no").
 *  - [Commit] is the heaviest thing in the app: a click landing in a THUD, the settled thump reserved
 *    for an irreversible act (the §3.6 bolus accept, an erase).
 *  - [SegmentTick] is nearly weightless — a detent should be felt, not heard.
 *
 * [minIntervalMs] is a floor on re-firing, the backstop for the two events that ride a continuous
 * gesture. It is NOT a substitute for value-quantisation at the call site (see [HapticDetent]): a
 * time throttle alone turns a fast scrub into a uniform buzz rather than a texture that tracks the
 * data.
 */
enum class HapticEvent(internal val recipe: HapticRecipe, internal val minIntervalMs: Long = 0) {
    /** A plain button / row press. */
    Tap(recipe(step(HapticPrimitive.CLICK, 0.50f))),

    /** A destination change — bottom nav, breadcrumb ascend, a drill-down row. A lead tick then the
     *  click, so arriving somewhere reads as heavier than pressing something. */
    NavSwitch(
        recipe(
            step(HapticPrimitive.TICK, 0.35f),
            step(HapticPrimitive.CLICK, 0.55f, delayMs = 30),
        ),
    ),

    /** A switch/checkbox turning ON. */
    ToggleOn(
        recipe(
            step(HapticPrimitive.QUICK_RISE, 0.40f),
            step(HapticPrimitive.CLICK, 0.70f),
        ),
    ),

    /** A switch/checkbox turning OFF — [ToggleOn] played backwards. */
    ToggleOff(
        recipe(
            step(HapticPrimitive.CLICK, 0.60f),
            step(HapticPrimitive.QUICK_FALL, 0.40f),
        ),
    ),

    /** A discrete detent: a chip in a picker, a segmented-button stop, a stepper increment. */
    SegmentTick(recipe(step(HapticPrimitive.LOW_TICK, 0.25f)), minIntervalMs = SEGMENT_MIN_MS),

    /** One sample crossed while dragging along a continuum — graph scrub, slider stop. Drier and a
     *  shade firmer than [SegmentTick] because it competes with the motion of the finger. */
    ScrubTick(recipe(step(HapticPrimitive.TICK, 0.30f)), minIntervalMs = SCRUB_MIN_MS),

    /** A continuous gesture met a hard bound: the graph viewport panned or zoomed into its clamp. Low
     *  and dull — a wall, not a detent — so it can never be mistaken for a [ScrubTick] crossing. It
     *  rides the same gesture as the tick, hence the rate-limit floor; but the call site must ALSO
     *  edge-trigger it (fire on arrival at the wall, not while resting against it), or a pan held at
     *  the bound drones. */
    EdgeStop(recipe(step(HapticPrimitive.LOW_TICK, 0.60f)), minIntervalMs = EDGE_MIN_MS),

    /** A long press has been recognised — one firm, unmistakable pop. */
    LongPress(recipe(step(HapticPrimitive.CLICK, 0.90f))),

    /** Something has been picked up: a graph scrub begins, a curve knot is grabbed. */
    DragStart(recipe(step(HapticPrimitive.QUICK_RISE, 0.50f))),

    /** Something has been set down: the drag released, the knot committed. */
    DragEnd(
        recipe(
            step(HapticPrimitive.QUICK_FALL, 0.45f),
            step(HapticPrimitive.CLICK, 0.55f),
        ),
    ),

    /** A freehand stroke touches down (the DEATH signature pad) — barely-there contact. */
    StrokeStart(recipe(step(HapticPrimitive.LOW_TICK, 0.35f))),

    /** A freehand stroke lifts off. */
    StrokeEnd(recipe(step(HapticPrimitive.TICK, 0.25f))),

    /** An action succeeded and is done — a log written, a dialog accepted. Ascending. */
    Confirm(
        recipe(
            step(HapticPrimitive.TICK, 0.45f),
            step(HapticPrimitive.CLICK, 0.75f, delayMs = 70),
        ),
    ),

    /** An irreversible commit — the terminal bolus accept, an erase. The heaviest pattern here, and
     *  the only one that ends in weight rather than a click. */
    Commit(
        recipe(
            step(HapticPrimitive.CLICK, 0.70f),
            step(HapticPrimitive.THUD, 1.00f, delayMs = 50),
        ),
    ),

    /** The UI refused the input — a cancel, a clear, a press swallowed by a rate limit. Two flat
     *  equal knocks, tight enough to read as a single "no". */
    Reject(
        recipe(
            step(HapticPrimitive.CLICK, 0.80f),
            step(HapticPrimitive.CLICK, 0.80f, delayMs = 55),
        ),
    ),

    /** A warning surfaced (a danger banner, a refused recommendation, a degenerate curve). A churn
     *  settling into weight — the only rumble in the vocabulary, so it cannot be mistaken for a
     *  confirmation. */
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

/**
 * The user intensity, persisted by `.name` through `SettingsStore.hapticsLevel` (`ui.haptics`).
 * [scale] multiplies every step amplitude; [STRONG] deliberately exceeds 1 so it saturates the heavy
 * events while still lifting the near-weightless ones, and the product is clamped at render time.
 */
enum class HapticStrength(val scale: Float, val displayName: String) {
    OFF(0f, "Off"),
    SUBTLE(0.55f, "Subtle"),
    STANDARD(1.0f, "Standard"),
    STRONG(1.4f, "Strong");

    companion object {
        val DEFAULT = STANDARD

        /** Tolerant decode of a persisted/imported name — an unknown value falls back to [DEFAULT]. */
        fun forKey(key: String?): HapticStrength = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** Below this the LRA moves without being felt, so a scaled step is floored here rather than lost. */
private const val MIN_AMPLITUDE = 0.05f

// ── the pure render kernel (unit-tested; no Android types) ─────────────────────────────────────

/** A [VibrationEffect.createWaveform] fallback synthesised from a recipe. */
class HapticWaveform internal constructor(val timingsMs: LongArray, val amplitudes: IntArray)

/** What [T1dmHaptics] will actually play; `null` from [hapticPlan] means "play nothing at all". */
internal sealed interface HapticPlan {
    class Primitives(val steps: List<HapticStep>) : HapticPlan
    class Waveform(val waveform: HapticWaveform) : HapticPlan
}

internal fun HapticRecipe.scaled(strength: HapticStrength): List<HapticStep> =
    steps.map { it.copy(amplitude = (it.amplitude * strength.scale).coerceIn(MIN_AMPLITUDE, 1f)) }

/**
 * Flatten scaled steps into the waveform form: each step becomes an off-segment of its delay followed
 * by an on-segment of the primitive's nominal duration at the scaled amplitude. A crude imitation —
 * a waveform cannot reproduce a QUICK_RISE envelope — but it preserves the *rhythm*, which is what
 * distinguishes a Reject from a Confirm on a device that cannot compose.
 */
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

/**
 * Decide, without touching hardware, what an event should play. [primitivesSupported] is asked once
 * per event about the whole set that event needs — the `areAllPrimitivesSupported` shape `:alerts`
 * already uses — so an actuator missing only THUD keeps composing everything that does not need it.
 */
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

// ── the engine ─────────────────────────────────────────────────────────────────────────────────

/**
 * Renders [HapticEvent]s on the device vibrator, preferring [VibrationEffect.Composition] and
 * degrading to a waveform per event, exactly as `:alerts`' `VibrationActuator` does for the alarm
 * tier — and, like it, never throwing: a vibrator that is absent, busy, or policy-blocked must not
 * take a tap with it.
 *
 * One instance lives for the process, provided through [LocalT1dmHaptics] by `T1dmTheme`. [strength]
 * is a plain mutable field rather than composition state on purpose: a haptic is not rendered, so a
 * change of intensity must NOT recompose anything, and the engine's identity can stay stable under a
 * `staticCompositionLocalOf` that would otherwise invalidate the whole tree on every settings write.
 */
@Stable
class T1dmHaptics internal constructor(
    private val vibrator: Vibrator?,
    strength: HapticStrength = HapticStrength.DEFAULT,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
    @Volatile
    var strength: HapticStrength = strength

    // Both caches are indexed by enum ordinal and touched only from the main thread (haptics are
    // fired from input callbacks). A torn read would at worst drop or duplicate one tick, so no
    // synchronisation is bought here.
    private val lastFiredMs = LongArray(HapticEvent.entries.size)
    private val composable = arrayOfNulls<Boolean>(HapticEvent.entries.size)

    /** Play [event] at the current [strength]. At [HapticStrength.OFF] this is a bare branch. */
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

    /**
     * [HapticEvent.ToggleOn] or [HapticEvent.ToggleOff] by the state being ADOPTED — the commonest
     * call in the app (every `Switch`, `Checkbox` and boolean chip), and the one most easily written
     * backwards, since a Material `onCheckedChange` reports the new value while the composable still
     * holds the old one.
     */
    fun toggled(on: Boolean) = perform(if (on) HapticEvent.ToggleOn else HapticEvent.ToggleOff)

    /**
     * Play [event] at an EXPLICIT strength, ignoring the stored one — the Settings picker's "feel it
     * as you pick it" seam (the `onPreviewVibration` precedent in Settings → Alerts), where the chip
     * must be felt before the kv write has round-tripped back through the flow.
     */
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
        /** The silent engine: the [LocalT1dmHaptics] default, so an un-hydrated preview is mute. */
        val None = T1dmHaptics(vibrator = null, strength = HapticStrength.OFF)

        fun create(context: Context, strength: HapticStrength = HapticStrength.DEFAULT): T1dmHaptics {
            val vibrator = runCatching {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            }.getOrNull()
            return T1dmHaptics(vibrator, strength)
        }
    }
}

/** Internal rather than private only so `ContinuousHaptics.kt`'s renderers can compose the same
 *  vocabulary; the mapping must stay in ONE place or the two modalities drift. */
internal fun androidId(primitive: HapticPrimitive): Int = when (primitive) {
    HapticPrimitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
    HapticPrimitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
    HapticPrimitive.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    HapticPrimitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
    HapticPrimitive.SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
    HapticPrimitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    HapticPrimitive.QUICK_FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
}

// ── composition plumbing + call-site ergonomics ────────────────────────────────────────────────

/**
 * The ambient haptics engine, provided once by `T1dmTheme`. Static (like [LocalAnimationsEnabled] and
 * [LocalDeathMode]) because the instance never changes for the life of the process — only the field
 * inside it does, which by design costs no recomposition at all.
 */
val LocalT1dmHaptics = staticCompositionLocalOf { T1dmHaptics.None }

/**
 * The engine for the current composition. Named for the call site rather than for its implementation
 * — it reads an ambient value and remembers nothing — so that instrumenting a screen reads like
 * `rememberCoroutineScope()` beside it. Haptics must always arrive this way: feature-module screen
 * signatures stay free of `:core:design` types (see `DisplaySettingsScreen`'s contract).
 */
@Composable
@ReadOnlyComposable
fun rememberT1dmHaptics(): T1dmHaptics = LocalT1dmHaptics.current

/** Build the process engine and keep its intensity current. Root-only; see `T1dmTheme`. */
@Composable
internal fun rememberHapticsEngine(strength: HapticStrength): T1dmHaptics {
    val app = LocalContext.current.applicationContext
    val engine = remember(app) { T1dmHaptics.create(app) }
    SideEffect { engine.strength = strength }
    return engine
}

/**
 * [Modifier.clickable] that also speaks. Compose foundation 1.7.6's `clickable` emits no haptic of
 * its own (nor do Material3 1.3.1's Switch/Slider/FilterChip), so every site is explicit and nothing
 * can double-fire.
 *
 * The buzz precedes [onClick] unconditionally — including where the click is a no-op, e.g. re-tapping
 * the already-selected bottom-nav tab, whose `launchSingleTop` navigation does nothing. Gating on
 * "did something change" is what makes a selected tab feel dead under the thumb.
 */
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
 * A detent for continuous controls: sliders, steppers, the graph scrub. Feed it the QUANTISED value
 * — the rounded stop, the sampled frame index, the 5-minute bucket — and it fires once per crossing
 * and never on a repeat.
 *
 * The quantisation must happen at the call site, not here, because only the call site knows the grain
 * (`CgmSettingsScreen` steps in days, `MealsScreen` in 5 g, the graph in sample indices). Handing it a raw
 * `Float` reproduces exactly the failure the engine's rate limit only papers over: a pointer-move
 * stream that saturates the LRA into a flat buzz instead of a texture that tracks the data.
 *
 * The first observation seeds silently, so simply rendering a control never buzzes; only a change
 * does. Note the two-way-bound sliders (`InsulinScreen`, `MealsScreen`) whose value also moves when
 * the user TYPES — those must drive the detent from the drag interaction, not from the value.
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

    /** Forget the current bucket, so the next [at] seeds instead of firing (e.g. on drag start). */
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
