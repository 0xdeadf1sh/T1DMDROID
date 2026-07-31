package com.t1dm.app.di

import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec

/**
 * Which catalogue preset a dose write commits, as a pure function of the catalogue and the two
 * labels that can name one. Lifted out of [AppContainer] so the precedence is host-testable without
 * Room, a native core, or a composition.
 *
 * The order is the whole of the policy:
 *
 * 1. **[requested]** — what the insulin panel passed. It wins outright. The writers used to discard
 *    it and resolve a Settings selection instead, so the confirmation dialog named one insulin and
 *    the row carried another; honouring it here is the fix, and any future caller that resolves
 *    ahead of this function reintroduces the same class of lie.
 * 2. **[lastLogged]** — the sticky memory of the last dose actually COMMITTED for this family, for
 *    the callers that have no pick of their own (the calculator's accepted bolus, a quick action).
 * 3. **The family's first catalogue entry** — a fresh install, or a memory naming a preset that no
 *    longer exists.
 *
 * Matching is by [InsulinPresetSpec.label], the catalogue's own stable identity (the Rust `preset`
 * enum is deliberately not projected across the uniffi seam). An unknown label therefore falls
 * through rather than throwing: the catalogue may be renamed by a later build, and a stored label
 * from an older one must degrade to a sane insulin, not to a crash on the dose path.
 *
 * Returns null only for an empty catalogue — a broken build, which the caller must fail closed on
 * rather than substitute a curve for.
 */
internal fun resolveInsulinPreset(
    catalog: List<InsulinPresetSpec>,
    family: InsulinFamily,
    requested: String?,
    lastLogged: String?,
): InsulinPresetSpec? {
    val ofFamily = catalog.filter { it.family == family }
    return ofFamily.firstOrNull { it.label == requested }
        ?: ofFamily.firstOrNull { it.label == lastLogged }
        ?: ofFamily.firstOrNull()
}
