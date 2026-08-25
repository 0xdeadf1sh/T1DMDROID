package com.t1dm.app.di

import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec

/** Matched by label, so an unknown one falls through. Null only for an empty catalogue. */
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
