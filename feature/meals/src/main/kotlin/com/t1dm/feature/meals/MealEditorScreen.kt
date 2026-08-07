package com.t1dm.feature.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.Food
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.ResolvedMealCurve
import com.t1dm.core.model.SavedMeal

/**
 * The stored-meal editor (`meals/builder/meal/{id}`) — rename, add/remove components, re-gram
 * portions, watch the combined **appearance (Ra)** curve follow, then overwrite the row, fork a copy,
 * or walk away.
 *
 * Its own view rather than a mode [MealBuilderScreen] falls into: as a mode it had to stash and
 * restore the scratch meal it displaced, suppress its own empty state, and keep Save pointed at an
 * identity that was invisible once the component list emptied. A route carries that identity instead.
 *
 * [meal] seeds the draft ONCE, keyed by its id. The saved-meal Flow re-emits a fresh [SavedMeal] on
 * every write — following it would discard the edit in progress the moment anything else touched the
 * store. The caller resolves a vanished id (deleted from another view) by leaving, not by mutating
 * what is on screen.
 */
@Composable
fun MealEditorScreen(
    meal: SavedMeal,
    onSearch: suspend (String) -> List<Food>,
    onResolve: suspend (List<MealComponent>) -> ResolvedMealCurve,
    onSave: (String, List<MealComponent>) -> Unit,
    onSaveAsNew: (String, List<MealComponent>) -> Unit,
    onCancel: () -> Unit,
) {
    val draft = rememberMealDraft(meal.components, key = meal.id)
    var name by remember(meal.id) { mutableStateOf(meal.name) }
    val resolved = resolvedCurve(draft.components, onResolve)
    val haptics = rememberT1dmHaptics()
    val scroll = rememberScrollState()
    val savable = !draft.isEmpty && name.isNotBlank()

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ComponentRows(draft)
        if (draft.isEmpty) {
            Text(
                "Add at least one food",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            ResolvedCurveSummary(resolved)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                // Overwriting a stored template is irreversible — there is no undo log for saved meals
                // — so it carries the heavy Commit, not the Confirm a fresh row gets.
                onClick = { haptics.perform(HapticEvent.Commit); onSave(name.trim(), draft.snapshot()) },
                enabled = savable,
            ) { Text("Save") }
            OutlinedButton(
                onClick = { haptics.perform(HapticEvent.Confirm); onSaveAsNew(name.trim(), draft.snapshot()) },
                enabled = savable,
            ) { Text("Save as new") }
            TextButton(
                onClick = { haptics.perform(HapticEvent.Reject); onCancel() },
            ) { Text("Cancel") }
        }
        HorizontalDivider()
        FoodSearch(onSearch) { food, grams -> draft.add(food.toComponentUi(grams)) }
    }
}
