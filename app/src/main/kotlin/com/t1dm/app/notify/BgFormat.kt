package com.t1dm.app.notify

import com.t1dm.core.model.UnitSpace

/**
 * BG / trend / age formatting shared by the notification and the widgets, in the app's active unit
 * space (ux-decisions.md — mg/dL default, mmol/L, Kovatchev raw). Mirrors the dashboard's
 * `formatBg`: mg/dL and Kovatchev show the integer mg/dL; mmol/L divides by 18.0182.
 */
object BgFormat {

    fun value(bgMgdl: Int?, unit: UnitSpace): String = when {
        bgMgdl == null -> "--"
        unit == UnitSpace.MmolL -> String.format("%.1f", bgMgdl / 18.0182)
        else -> bgMgdl.toString()
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

    /** The one-line predictive warning, e.g. "Approaching hypoglycemia in ~20 min". */
    fun crossingLine(c: PredictiveCrossing): String {
        val what = if (c.kind == PredictiveCrossing.Kind.HYPO) "hypoglycemia" else "hyperglycemia"
        val eta = if (c.etaMin <= 5) "~5 min" else "~${c.etaMin} min"
        return "Approaching $what in $eta"
    }
}
