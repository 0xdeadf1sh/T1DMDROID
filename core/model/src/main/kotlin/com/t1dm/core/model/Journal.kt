package com.t1dm.core.model

/**
 * Phase-4 manual-entry + journal domain types (Phase 4, deliverables 1–2).
 * These cross the `:feature:{journal,meals,insulin}` ⇄ `:app` seam as pure value types so the
 * feature screens stay dependency-light (only `:core:*`) — the Room/`:data`/`:sync` wiring lives
 * in `:app`, exactly as the Phase-1 `DashboardScreen` is fed state + callbacks from the container.
 */

/**
 * One free-text journal note (the locked "mood + free-text journal now" scope). Persisted in the
 * local `note` table and mirrored via `POST /v1/notes`; [tsMs] is wall-clock (NOT grid-snapped —
 * a note is an instant, not a 5-min bucket).
 */
data class JournalNote(
    val tsMs: Long,
    val tzOffsetMin: Int,
    val text: String,
)

/**
 * The IOB/COB read-out with its §3.6-F provenance metadata. IOB/COB are computed from **logged
 * doses only** (never from an announced/candidate what-if), so a long silence since the last
 * logged insulin — surfaced here as [minsSinceLastLoggedInsulin] — is the signal the dose card
 * escalates on ("IOB from logged doses only; last logged N min ago"). [hasBasalSchedule] tells the
 * reader whether the near-flat basal background is included in [iobU].
 *
 * [iobZeroMs] is the wall-clock instant the combined insulin action (logged doses + active basal
 * tails only) finally decays to zero — the anchor the circadian panel's insulin-exhaustion
 * countdown projects DKA/coma/death forward from. Null when no insulin is on board (nothing to
 * exhaust); display-only, never read by §3.6.
 */
data class IobCobReadout(
    val atMs: Long,
    val iobU: Double,
    val cobG: Double,
    val minsSinceLastLoggedInsulin: Long?,
    val hasBasalSchedule: Boolean,
    val iobZeroMs: Long? = null,
)

/**
 * A long-acting basal quick-preset for the insulin entry surface. The label is presentation-only;
 * `:app` maps the preset onto the canonical Bateman DIA + rate constants (`CurveEngine.Presets`),
 * keeping the numeric authority in one place (SPEC §3.3).
 */
enum class BasalPreset(val label: String) {
    LANTUS("Lantus · glargine · ~24 h"),
    TRESIBA("Tresiba · degludec · ~42 h"),
}

/**
 * A rapid-acting bolus quick-preset. Only the Novorapid/aspart gamma neighbourhood is canonical
 * (`simulator.bolus_pk_for_dose`); a custom curve is the food/insulin-builder's later job.
 */
enum class BolusPreset(val label: String) {
    NOVORAPID("Novorapid · aspart · gamma ~50 min peak"),
}

/**
 * A carb glycemic-index quick-chip for the meal entry surface. [gi] parameterizes the appearance
 * (Ra) gamma (juice ⇒ high early peak, bread ⇒ spread); `:app` maps it via
 * `CurveEngine.Presets.carbGammaForGi`. The set is a small curated span, not a licensed table
 * (the FTS5 food DB is a later deliverable, gated on S9).
 */
/**
 * A recently-logged meal surfaced as a quick-select chip on the meal entry surface (Phase 7C,
 * item 9). It carries just the two fields the simple carb form binds — [grams] and [gi] — so a tap
 * pre-fills both the grams field/slider and the GI slider. Only GI-bearing logged meals qualify
 * (meals logged through the multi-food builder carry a custom curve and a null GI, which the simple
 * form cannot round-trip); `:app` selects the last few DISTINCT `(grams, gi)` pairs.
 */
data class RecentMeal(val grams: Double, val gi: Double) {
    /** A compact chip label, e.g. "45 g · GI 65". */
    val label: String get() = "${grams.toInt()} g · GI ${gi.toInt()}"
}

enum class GiChip(val label: String, val gi: Double) {
    JUICE("Juice / glucose", 100.0),
    WHITE_BREAD("White bread", 75.0),
    RICE("Rice / potato", 65.0),
    MIXED("Mixed meal", 50.0),
    PASTA("Pasta / legumes", 35.0),
}
