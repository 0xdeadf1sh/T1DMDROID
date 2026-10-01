package com.t1dm.app.widget

import android.content.Context
import com.t1dm.app.T1dmApplication
import com.t1dm.app.notify.BgGlance
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.app.notify.GlanceReadings
import com.t1dm.app.notify.GlyStatus
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.model.AlarmFanEdges
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [GlyStatus] as the tile colours it; VOID is any fail-closed ineligibility. */
internal enum class GlyKind { STABLE, UNSURE, EXCURSION, VOID }

/** BG, trend and forecast share BgGlanceComputer with the notification and the watch. */
internal data class WidgetSnapshot(
    val glance: BgGlance,
    val unit: UnitSpace,
    val animationsEnabled: Boolean,
    val bgAlphaPct: Int,
    val iobU: Double?,
    val cobG: Double?,
    val rssi: Int?,
    /** Hour, 0–24; null with no time head. */
    val clockHour: Double?,
    val clockConf: Double?,
    /** Percent; null on too little data. */
    val gmi: Double?,
    /** Local midnight → now. */
    val steps: Int?,
    /** The fail-open override. */
    val death: Boolean,
    val glyText: String,
    val glyKind: GlyKind,
    /** Carried so [WidgetStateStore] can persist it with the tile. */
    val thresholds: AlertThresholds,
    val lossMin: Int,
    /** Carried so `provideGlance` can seed the palette holders itself. */
    val themeId: String,
    val customThemeJson: String?,
)

/** Minutes. Shared so the live pull and [WidgetStateStore]'s rebuild cannot drift apart. */
internal const val STALE_MIN = 15

/** Collapses timeout and error to null; a hung suspend has no guard and would stall glance. */
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

/** Fails as a unit (falls back to WidgetStateStore); per-read fields default individually. */
internal suspend fun currentWidgetSnapshot(context: Context): WidgetSnapshot {
    val container = (context.applicationContext as T1dmApplication).container
    return withContext(container.dispatchers.default) {
        val nowMs = System.currentTimeMillis()
        val src = container.repository.authoritativeSourceId()
        // 36 rows: the newest MEASUREMENT may sit behind promoted reconstructions.
        val rows = src?.let { container.repository.recentReadings(it, 36) } ?: emptyList()
        val readings = GlanceReadings.create(rows)
        val direction = src?.let { runCatching { container.directionNow(it, rows) }.getOrNull() }
        val latest = readings.latest
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
        // Volatile holds defaults until refresh; cold-start render would persist them as real.
        if (!container.alarmConfigHydrated) runCatching { container.refreshAlarmConfig() }
        val cfg = container.alarmConfig
        val ageMs = latest?.let { (nowMs - it.rxWallMs).coerceAtLeast(0L) }
        val (glyText, glyKind) = computeGlyStatus(state, cfg.thresholds, container.alarmFanEdges, nowMs, ageMs)

        val glance = BgGlanceComputer.compute(
            readings = readings,
            state = state,
            thresholds = cfg.thresholds,
            edges = container.alarmFanEdges,
            lossMin = cfg.lossMin,
            staleMin = STALE_MIN,
            nowMs = nowMs,
            trend = direction?.trend,
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

/** The top bar's status, so tile and top bar agree by construction. */
internal fun computeGlyStatus(
    state: InferenceState,
    thr: AlertThresholds?,
    edges: AlarmFanEdges?,
    nowMs: Long,
    readingAgeMs: Long?,
): Pair<String, GlyKind> {
    val s = BgGlanceComputer.status(state, thr, edges, nowMs, readingAgeMs, STALE_MIN)
    return when (s) {
        is GlyStatus.Void -> "VOID" to GlyKind.VOID
        GlyStatus.Stable -> "STABLE" to GlyKind.STABLE
        GlyStatus.Unsure -> "UNSURE" to GlyKind.UNSURE
        is GlyStatus.Excursion -> "${s.kind.name} in ${formatEta(s.atMs - nowMs)}" to GlyKind.EXCURSION
    }
}

private fun formatEta(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return when {
        s < 60L -> "${s}S"
        s < 3600L -> "${s / 60L}M"
        else -> "${s / 3600L}H"
    }
}
