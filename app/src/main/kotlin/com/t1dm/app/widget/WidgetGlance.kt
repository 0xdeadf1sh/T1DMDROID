package com.t1dm.app.widget

import android.content.Context
import com.t1dm.app.T1dmApplication
import com.t1dm.app.notify.BgGlance
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.flow.first

/** The app-wide glycemic status kind (Navigation.glycemicStatusOf / U1): STABLE is a positive claim,
 *  EXCURSION a predicted crossing, VOID any fail-closed ineligibility. */
internal enum class GlyKind { STABLE, EXCURSION, VOID }

/**
 * The widgets' read of the shared glance plus the richer fields the large tiers show (IOB/COB, CGM
 * signal, circadian clock, GMI, steps, DEATH-mode state). The core BG/trend/forecast still comes from
 * the SAME [BgGlanceComputer] the notification and watch use, so every surface agrees by construction;
 * the extras are read straight off the container. Pull-based — invoked inside `provideGlance`, refreshed
 * whenever the foreground service calls `updateAll` (a reading emit or the 30 s ticker).
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
)

internal suspend fun currentWidgetSnapshot(context: Context): WidgetSnapshot {
    val container = (context.applicationContext as T1dmApplication).container
    val nowMs = System.currentTimeMillis()
    val src = container.repository.activeSourceId()
    val latest = src?.let { container.repository.recentReadings(it, 1).firstOrNull() }
    val unit = runCatching { container.statsRepository.currentUnitSpace() }.getOrDefault(UnitSpace.MgDl)
    val animationsEnabled = runCatching { container.settingsStore.animationsEnabled.first() }.getOrDefault(true)
    val bgAlphaPct = runCatching { container.settingsStore.currentBackgroundAlphaPct() }
        .getOrDefault(com.t1dm.app.settings.SettingsStore.DEFAULT_BG_ALPHA_PCT)
    val state = container.inferenceState.value
    val onBoard = runCatching { container.iobCobNow() }.getOrNull()
    val clock = state.selectedPredictedTime
    val steps = runCatching { container.stepsToday() }.getOrNull()
    val (glyText, glyKind) = computeGlyStatus(state, container.alarmConfig.thresholds, nowMs)

    val glance = BgGlanceComputer.compute(
        latest = latest,
        state = state,
        thresholds = container.alarmConfig.thresholds,
        lossMin = container.alarmConfig.lossMin,
        staleMin = 15,
        nowMs = nowMs,
    )
    return WidgetSnapshot(
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
    )
}

/**
 * The app-wide glycemic status (U1 / Navigation.glycemicStatusOf), recomputed for the widget from the
 * same [InferenceState] + thresholds so the tile agrees with the top bar. Fail-closed: any ineligibility
 * (warmup / no forecast / stale anchor / degenerate) is VOID, never a positive STABLE. The excursion ETA
 * is derived from the crossing's absolute timestamp, so its unit adapts down to seconds near the event.
 */
private fun computeGlyStatus(state: InferenceState, thr: AlertThresholds?, nowMs: Long): Pair<String, GlyKind> {
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
