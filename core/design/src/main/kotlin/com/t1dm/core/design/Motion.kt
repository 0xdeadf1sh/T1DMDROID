package com.t1dm.core.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The single lever that makes the "disable all animations" setting real (issue 17). Motion in the app
 * flows through three doors, and each reads [LocalAnimationsEnabled] via a helper here so the flag
 * genuinely collapses everything to a snap:
 *
 *  1. **Screen transitions** — the `NavHost` enter/exit/pop specs (the crossfade the user sees on
 *     every tab change) come from [navEnter]/[navExit], which return [EnterTransition.None] /
 *     [ExitTransition.None] when motion is off.
 *  2. **Value/spec animations** — any `animate*AsState`/`AnimatedVisibility` spec should be built with
 *     [motionSpec], which returns [snap] when motion is off.
 *  3. **Imperative scrolls** — call sites branch on [LocalAnimationsEnabled] to `scrollTo` instead of
 *     `animateScrollTo` (the bottom-nav auto-centre).
 *
 * Decorative/looping motion is simply not started when the flag is off.
 */

const val DEFAULT_MOTION_MS = 220

@Composable
@ReadOnlyComposable
fun animationsOn(): Boolean = LocalAnimationsEnabled.current

/** A finite spec that becomes an instant [snap] when motion is disabled. */
fun <T> motionSpec(enabled: Boolean, durationMs: Int = DEFAULT_MOTION_MS): FiniteAnimationSpec<T> =
    if (enabled) tween(durationMs) else snap()

fun navEnter(enabled: Boolean): EnterTransition =
    if (enabled) fadeIn(tween(DEFAULT_MOTION_MS)) else EnterTransition.None

fun navExit(enabled: Boolean): ExitTransition =
    if (enabled) fadeOut(tween(DEFAULT_MOTION_MS)) else ExitTransition.None
