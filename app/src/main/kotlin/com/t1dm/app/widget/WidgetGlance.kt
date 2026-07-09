package com.t1dm.app.widget

import android.content.Context
import com.t1dm.app.T1dmApplication
import com.t1dm.app.notify.BgGlance
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.core.model.UnitSpace

/**
 * The widgets' read of the shared glance: they run the SAME [BgGlanceComputer] over the same active
 * reading + selected forecast the notification and watch use, so every surface agrees by
 * construction (PLAN Phase 7B). Pull-based — invoked inside `provideGlance`, refreshed whenever the
 * foreground service calls `updateAll` on a new reading or 5-min cycle (no Activity required).
 */
internal data class WidgetSnapshot(val glance: BgGlance, val unit: UnitSpace)

internal suspend fun currentWidgetSnapshot(context: Context): WidgetSnapshot {
    val container = (context.applicationContext as T1dmApplication).container
    val src = container.repository.activeSourceId()
    val latest = src?.let { container.repository.recentReadings(it, 1).firstOrNull() }
    val unit = runCatching { container.statsRepository.currentUnitSpace() }.getOrDefault(UnitSpace.MgDl)
    val glance = BgGlanceComputer.compute(
        latest = latest,
        state = container.inferenceState.value,
        thresholds = container.alarmConfig.thresholds,
        lossMin = container.alarmConfig.lossMin,
        staleMin = 15,
        nowMs = System.currentTimeMillis(),
    )
    return WidgetSnapshot(glance, unit)
}
