package com.t1dm.core.model

/**
 * IOB/COB are computed from LOGGED DOSES only, never from an announced what-if (§3.6-F), so
 * [minsSinceLastLoggedInsulin] is what the dose card escalates on. [hasBasalSchedule] says whether
 * the basal background is included in [iobU]. [iobZeroMs] is when combined insulin action decays to
 * zero, null when none is on board — display-only, never read by §3.6.
 */
data class IobCobReadout(
    val atMs: Long,
    val iobU: Double,
    val cobG: Double,
    val minsSinceLastLoggedInsulin: Long?,
    val hasBasalSchedule: Boolean,
    val iobZeroMs: Long? = null,
)

/** Only GI-bearing logged meals qualify: a meal from the multi-food builder carries a custom curve
 *  and a null GI, which the simple form cannot round-trip. */
data class RecentMeal(val grams: Double, val gi: Double) {
    val label: String get() = "${grams.toInt()} g · GI ${gi.toInt()}"
}

enum class GiChip(val label: String, val gi: Double) {
    JUICE("Juice / glucose", 100.0),
    WHITE_BREAD("White bread", 75.0),
    RICE("Rice / potato", 65.0),
    MIXED("Mixed meal", 50.0),
    PASTA("Pasta / legumes", 35.0),
}
