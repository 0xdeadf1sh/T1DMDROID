package com.t1dm.app.notify

import com.t1dm.core.common.KovatchevScale
import com.t1dm.core.model.UnitSpace

/** Via [KovatchevScale] (INFERENCE.md §5): Widget snapshot persists field-by-field, no lambda. */
object BgFormat {

    fun value(bgMgdl: Int?, unit: UnitSpace): String {
        if (bgMgdl == null) return "--"
        return when (unit) {
            UnitSpace.MgDl -> bgMgdl.toString()
            UnitSpace.MmolL -> String.format("%.1f", bgMgdl / 18.0182)
            // Symmetrised about ~112.5 mg/dL: sign carries meaning; 2 dp matches the axis.
            UnitSpace.Kovatchev -> String.format("%+.2f", KovatchevScale.f(bgMgdl.toDouble()))
        }
    }

    /** U+2007 FIGURE SPACE is digit-width: it holds Kovatchev's sign column open in the others. */
    private val SIGN_PAD = Char(0x2007).toString()

    fun valueSignAligned(bgMgdl: Int?, unit: UnitSpace): String {
        val v = value(bgMgdl, unit)
        return if (v.startsWith('+') || v.startsWith('-')) v else SIGN_PAD + v
    }

    fun unitLabel(unit: UnitSpace): String = when (unit) {
        UnitSpace.MgDl -> "mg/dL"
        UnitSpace.MmolL -> "mmol/L"
        UnitSpace.Kovatchev -> "risk"
    }

    /** Empty where no rate was measured; callers must not lay out a space around it. */
    fun arrow(trend: GlanceTrend?): String = when (trend) {
        GlanceTrend.RISING_FAST -> "⇈"
        GlanceTrend.RISING -> "↗"
        GlanceTrend.FLAT -> "→"
        GlanceTrend.FALLING -> "↘"
        GlanceTrend.FALLING_FAST -> "⇊"
        null -> ""
    }

    fun age(ageMs: Long): String {
        val totalMin = ageMs / 60_000L
        return when {
            totalMin <= 0L -> "now"
            totalMin < 60L -> "${totalMin}m ago"
            else -> "${totalMin / 60L}h ${totalMin % 60L}m ago"
        }
    }

    fun ageShort(ageMs: Long): String {
        val s = ageMs / 1000L
        return when {
            s < 1 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            else -> "${s / 3600}h ${(s % 3600) / 60}m ago"
        }
    }

    fun crossingLine(c: PredictiveCrossing): String {
        val what = if (c.kind == PredictiveCrossing.Kind.HYPO) "Low" else "High"
        val eta = if (c.etaMin <= 5) "~5 min" else "~${c.etaMin} min"
        return "$what in $eta"
    }
}
