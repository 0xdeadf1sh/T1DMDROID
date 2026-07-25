package com.t1dm.app.widget

import android.content.Context
import com.t1dm.app.T1dmApplication
import com.t1dm.app.notify.BgGlance
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The app-wide glycemic status kind (Navigation.glycemicStatusOf / U1): STABLE is a positive claim,
 *  EXCURSION a predicted crossing, VOID any fail-closed ineligibility. */
internal enum class GlyKind { STABLE, EXCURSION, VOID }

/**
 * The widgets' read of the shared glance plus the richer fields the large tiers show (IOB/COB, CGM
 * signal, circadian clock, GMI, steps, DEATH-mode state). The core BG/trend/forecast still comes from
 * the SAME [BgGlanceComputer] the notification and watch use, so every surface agrees by construction;
 * the extras are read straight off the container. Pull-based — invoked inside `provideGlance`, refreshed
 * whenever anything calls `updateAll`: the foreground service (a reading emit or the 30 s ticker), the
 * boot receiver, or [WidgetRefreshWorker]'s 15-minute floor. A render that cannot complete the pull
 * falls back to [WidgetStateStore]'s persisted copy of the last one that did.
 */
internal data class WidgetSnapshot(
    val glance: BgGlance,
    val unit: UnitSpace,
    val animationsEnabled: Boolean,
    /** Settings → Display → Background opacity (%), applied to the rasterised theme motif behind the tile. */
    val bgAlphaPct: Int,
    val iobU: Double?,
    val cobG: Double?,
    val rssi: Int?,
    /** Circadian model-clock hour (0–24) and its resultant confidence, or null when no time head. */
    val clockHour: Double?,
    val clockConf: Double?,
    /** GMI (estimated HbA1c, %), cached on the container; null when too little data. */
    val gmi: Double?,
    /** Today's cumulative steps (local midnight → now), or null when unavailable. */
    val steps: Int?,
    /** DEATH mode engaged (fail-OPEN override) vs NORMAL. */
    val death: Boolean,
    /** The app-wide glycemic status text (VOID / STABLE / HYPO in 5M / HYPER in 30S …) and its kind. */
    val glyText: String,
    val glyKind: GlyKind,
    /** The alarm geometry this render was computed against — carried so [WidgetStateStore] can persist
     *  it and a cached tile keeps the user's band tint and loss-of-signal window. */
    val thresholds: AlertThresholds,
    val lossMin: Int,
    /** The persisted theme, so `provideGlance` can seed the palette holders itself instead of
     *  inheriting whatever the process happens to hold (see [com.t1dm.core.design.applyWidgetPalette]). */
    val themeId: String,
    val customThemeJson: String?,
)

/** The age (minutes) past which a reading counts as stale, matching the FGS's own glance refresh.
 *  Named so the live pull and [WidgetStateStore]'s cached rebuild cannot drift apart. */
internal const val STALE_MIN = 15

/**
 * Bound [pull] by a hard wall-clock [budgetMs], collapsing BOTH of `provideGlance`'s failure modes to
 * the single `null` its fallback keys on. `runCatching` already turns a THROW into null — but a read
 * that suspends and never resumes (a cold, widget-only process forcing the Room InvalidationTracker's
 * first subscribe, a starved reader-pool acquire) is not an exception; it slips past every guard and
 * parks `provideGlance` short of `provideContent`, and the host keeps inflating its loading layout —
 * the perpetual white-tile spinner. [withTimeoutOrNull] cancels at the next cooperative suspension point
 * (a `Flow` collection and a pooled-connection acquire are both cancellable) and yields null, so a
 * parked read is folded into the very same fallback a throw is. [onTimeout] and [onError] separate the
 * two only for the log; the render treats them identically. Extracted so that contract is host-testable
 * without the Glance stack.
 */
internal suspend fun boundedWidgetPull(
    budgetMs: Long,
    onTimeout: () -> Unit = {},
    onError: (Throwable) -> Unit = {},
    pull: suspend () -> WidgetSnapshot,
): WidgetSnapshot? {
    val outcome = runCatching { withTimeoutOrNull(budgetMs) { pull() } }
    outcome.exceptionOrNull()?.let(onError)
    val snapshot = outcome.getOrNull()
    if (outcome.isSuccess && snapshot == null) onTimeout()
    return snapshot
}

/**
 * The live pull. Deliberately NOT individually guarded at the container/Room/inference seams: it must
 * fail as a UNIT so the caller can fall back to [WidgetStateStore]'s last-known render. Guarding each
 * read would instead yield a plausible-looking snapshot full of boot defaults — which would then be
 * persisted over a perfectly good cache. The optional extras below are per-read guarded because their
 * absence is a missing metric ("—"), not a failed pull.
 *
 * Pinned to [com.t1dm.core.common.T1dmDispatchers.default] rather than run on whatever the Glance
 * SessionWorker hands us: the reads within already hop to `io` themselves, but a guaranteed CPU-pool
 * home keeps the curve eval + glance compute off a confined worker thread and gives the caller's
 * [boundedWidgetPull] timeout a cancellable context to interrupt. The lone settings read is
 * `currentAnimationsEnabled()` — the one-shot `getKv`, NOT `animationsEnabled.first()`, whose live-Flow
 * first-emission is the read most apt to park a cold process (see [boundedWidgetPull]).
 */
internal suspend fun currentWidgetSnapshot(context: Context): WidgetSnapshot {
    val container = (context.applicationContext as T1dmApplication).container
    return withContext(container.dispatchers.default) {
        val nowMs = System.currentTimeMillis()
        val src = container.repository.activeSourceId()
        val latest = src?.let { container.repository.recentReadings(it, 1).firstOrNull() }
        val unit = runCatching { container.statsRepository.currentUnitSpace() }.getOrDefault(UnitSpace.MgDl)
        val animationsEnabled = runCatching { container.settingsStore.currentAnimationsEnabled() }.getOrDefault(true)
        val bgAlphaPct = runCatching { container.settingsStore.currentBackgroundAlphaPct() }
            .getOrDefault(com.t1dm.app.settings.SettingsStore.DEFAULT_BG_ALPHA_PCT)
        val themeId = runCatching { container.settingsStore.currentThemeId() }.getOrDefault(ThemeIds.TRON)
        val customThemeJson = runCatching { container.settingsStore.currentCustomThemeJson() }.getOrNull()
        val state = container.inferenceState.value
        val onBoard = runCatching { container.iobCobNow() }.getOrNull()
        val clock = state.selectedPredictedTime
        val steps = runCatching { container.stepsToday() }.getOrNull()
        // Hydrate on demand rather than racing startup. Every other read in this block is a suspend
        // call that fetches what it needs; this one was a bare volatile that holds the CODED DEFAULTS
        // until the first refresh lands — and the snapshot below is persisted as the tile's alarm
        // geometry, so a widget render early in a cold start would have written those defaults in as
        // though they were the user's thresholds.
        if (!container.alarmConfigHydrated) runCatching { container.refreshAlarmConfig() }
        val cfg = container.alarmConfig
        val (glyText, glyKind) = computeGlyStatus(state, cfg.thresholds, nowMs)

        val glance = BgGlanceComputer.compute(
            latest = latest,
            state = state,
            thresholds = cfg.thresholds,
            lossMin = cfg.lossMin,
            staleMin = STALE_MIN,
            nowMs = nowMs,
        )
        WidgetSnapshot(
            glance = glance,
            unit = unit,
            animationsEnabled = animationsEnabled,
            bgAlphaPct = bgAlphaPct,
            iobU = onBoard?.iobU,
            cobG = onBoard?.cobG,
            rssi = latest?.rssi,
            clockHour = clock?.predictedHour,
            clockConf = clock?.resultantR,
            gmi = container.gmiSnapshot,
            steps = steps,
            death = container.deathModeSnapshot,
            glyText = glyText,
            glyKind = glyKind,
            thresholds = cfg.thresholds,
            lossMin = cfg.lossMin,
            themeId = themeId,
            customThemeJson = customThemeJson,
        )
    }
}

/**
 * The app-wide glycemic status (U1 / Navigation.glycemicStatusOf), recomputed for the widget from the
 * same [InferenceState] + thresholds so the tile agrees with the top bar. Fail-closed: any ineligibility
 * (warmup / no forecast / stale anchor / degenerate) is VOID, never a positive STABLE. The excursion ETA
 * is derived from the crossing's absolute timestamp, so its unit adapts down to seconds near the event.
 */
internal fun computeGlyStatus(state: InferenceState, thr: AlertThresholds?, nowMs: Long): Pair<String, GlyKind> {
    if (state.warmup != null) return "VOID" to GlyKind.VOID
    val p = state.selectedPrediction ?: return "VOID" to GlyKind.VOID
    if (p.stale) return "VOID" to GlyKind.VOID
    if (p.status != ForecastStatus.OK) return "VOID" to GlyKind.VOID
    thr ?: return "VOID" to GlyKind.VOID
    for (i in p.medianBg.indices) {
        val v = p.medianBg[i]
        val etaMs = (p.anchorTsMs + (i + 1L) * p.stepMs) - nowMs
        if (v <= thr.lowMgdl) return "HYPO in ${formatEta(etaMs)}" to GlyKind.EXCURSION
        if (v >= thr.highMgdl) return "HYPER in ${formatEta(etaMs)}" to GlyKind.EXCURSION
    }
    return "STABLE" to GlyKind.STABLE
}

/** Human ETA whose unit adapts to the magnitude: seconds under a minute, then minutes, then hours. */
private fun formatEta(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return when {
        s < 60L -> "${s}S"
        s < 3600L -> "${s / 60L}M"
        else -> "${s / 3600L}H"
    }
}
