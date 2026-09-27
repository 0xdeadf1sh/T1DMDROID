package com.t1dm.app.di

import com.t1dm.core.model.InsulinChoice
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.data.meals.InsulinController

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

/** The row's own insulin, found by its note, when a units edit must re-derive its shape. */
internal fun doseScaledOwnChoice(choices: List<InsulinChoice>, kind: InsulinKind?, label: String?): InsulinChoice? =
    choices.firstOrNull { it.kind == kind && it.label == label }?.takeIf {
        when (it) {
            is InsulinChoice.Preset -> it.spec.family == InsulinFamily.RapidGamma
            is InsulinChoice.Type -> InsulinController.isDoseScaled(it.type)
        }
    }
