package com.t1dm.core.model

/** IOB/COB from LOGGED DOSES only, never a what-if (§3.6-F); iobZeroMs is display-only. */
data class IobCobReadout(
    val atMs: Long,
    val iobU: Double,
    val cobG: Double,
    val minsSinceLastLoggedInsulin: Long?,
    val hasBasalSchedule: Boolean,
    val iobZeroMs: Long? = null,
)

/** Only GI-bearing meals qualify; multi-food builder meals carry a null GI, no round-trip. */
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
