package com.t1dm.app.notify

import com.t1dm.core.common.KovatchevScale
import com.t1dm.core.model.UnitSpace

/**
 * BG / trend / age formatting shared by the breadcrumb, the ongoing notification and the widgets, in
 * the app's active unit space (mg/dL default, mmol/L, Kovatchev risk). Mirrors the
 * dashboard's `formatBg`: mg/dL shows the integer, mmol/L divides by 18.0182, Kovatchev applies the
 * risk transform `f` (INFERENCE.md §5).
 *
 * Kovatchev goes through [KovatchevScale], the pure-Kotlin mirror, rather than the native `f` the
 * dashboard and the graph axis thread in: these surfaces render headless — the Glance tile's cached
 * and floor renders run exactly when the container pull has already failed, and no seam here can
 * carry a lambda (a [com.t1dm.app.widget.WidgetSnapshot] is persisted field-by-field into Glance
 * Preferences).
 */
object BgFormat {

    /** Exhaustive on [UnitSpace] by construction: the arm that was missing here rendered risk space
     *  as a raw mg/dL integer under the "risk" label of [unitLabel]. */
    fun value(bgMgdl: Int?, unit: UnitSpace): String {
        if (bgMgdl == null) return "--"
        return when (unit) {
            UnitSpace.MgDl -> bgMgdl.toString()
            UnitSpace.MmolL -> String.format("%.1f", bgMgdl / 18.0182)
            // 2 dp matches the graph's risk axis (GlucoseGraph.formatValue), and the sign is carried
            // explicitly: the scale is symmetrised about ~112.5 mg/dL, so on a lone chrome read-out —
            // no axis, no neighbouring ticks — the side is the reading's whole meaning.
            UnitSpace.Kovatchev -> String.format("%+.2f", KovatchevScale.f(bgMgdl.toDouble()))
        }
    }

    fun unitLabel(unit: UnitSpace): String = when (unit) {
        UnitSpace.MgDl -> "mg/dL"
        UnitSpace.MmolL -> "mmol/L"
        UnitSpace.Kovatchev -> "risk"
    }

    fun arrow(trend: GlanceTrend): String = when (trend) {
        GlanceTrend.RISING_FAST -> "⇈"   // ⇈
        GlanceTrend.RISING -> "↗"        // ↗
        GlanceTrend.FLAT -> "→"          // →
        GlanceTrend.FALLING -> "↘"       // ↘
        GlanceTrend.FALLING_FAST -> "⇊"  // ⇊
    }

    /** Compact human age, e.g. "now", "3m ago", "1h 4m ago". */
    fun age(ageMs: Long): String {
        val totalMin = ageMs / 60_000L
        return when {
            totalMin <= 0L -> "now"
            totalMin < 60L -> "${totalMin}m ago"
            else -> "${totalMin / 60L}h ${totalMin % 60L}m ago"
        }
    }

    /** Second-resolution age for the live notification, where a sub-minute stamp needs to visibly
     *  tick (F1). Unlike [age], which floors to whole minutes, this surfaces "just now" and "${s}s ago"
     *  so a fresh reading reads as fresh rather than as a static "now". */
    fun ageShort(ageMs: Long): String {
        val s = ageMs / 1000L
        return when {
            s < 1 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            else -> "${s / 3600}h ${(s % 3600) / 60}m ago"
        }
    }

    /** The one-line predictive warning, e.g. "Low in ~20 min". */
    fun crossingLine(c: PredictiveCrossing): String {
        val what = if (c.kind == PredictiveCrossing.Kind.HYPO) "Low" else "High"
        val eta = if (c.etaMin <= 5) "~5 min" else "~${c.etaMin} min"
        return "$what in $eta"
    }
}
