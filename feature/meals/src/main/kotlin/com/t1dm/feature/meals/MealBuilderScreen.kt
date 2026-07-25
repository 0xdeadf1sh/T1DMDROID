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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.ConfirmLogDialog
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.PendingLog
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.Food
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.ResolvedMealCurve
import com.t1dm.core.model.SavedMeal
import com.t1dm.ui.graph.CurveEditor

/**
 * The multi-food meal builder (Phase 4 deliverable 3/4) — the rich seam the simple
 * carb-entry [MealsScreen] points at. It searches the bundled glycemic dictionary (FTS5), assembles
 * a portioned component list, shows the LIVE combined **appearance (Ra)** curve
 * ([onResolve]) with its total carbs + peak time, and can save the meal, log it (reshaping the
 * forecast), add a custom food (with an optional drawn appearance curve), and recall a saved meal.
 * Stateless + callback-driven; all resolution/persistence is off-thread in the caller.
 *
 * It composes NEW meals only. Altering a stored meal or a custom food happens in its own view
 * ([MealEditorScreen] / [FoodEditorScreen]), reached through [onEditMeal] / [onEditFood]: an edit
 * session grafted onto this screen had to stash and restore the scratch meal it displaced, and left
 * Save aimed at whichever identity was open rather than at the thing on screen. The two lists here
 * are otherwise unlike — a [Food] is an ingredient priced per 100 g, a [SavedMeal] is a combination
 * of portions — so each row states its own facts rather than relying on prose to separate them.
 */
@Composable
fun MealBuilderScreen(
    savedMeals: List<SavedMeal>,
    customFoods: List<Food>,
    onSearch: suspend (String) -> List<Food>,
    onResolve: suspend (List<MealComponent>) -> ResolvedMealCurve,
    onLogMeal: (List<MealComponent>) -> Unit,
    onSaveMeal: (String, List<MealComponent>) -> Unit,
    onSaveFood: (Food) -> Unit,
    onEditMeal: (Long) -> Unit,
    onEditFood: (Long) -> Unit,
    onDeleteMeal: (Long) -> Unit,
    onDeleteFood: (Long) -> Unit,
) {
    val draft = rememberMealDraft()
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BuilderSummary(
            draft = draft,
            onResolve = onResolve,
            onLogMeal = onLogMeal,
            onSaveMeal = onSaveMeal,
        )
        HorizontalDivider()
        FoodSearch(onSearch) { food, grams -> draft.add(food.toComponentUi(grams)) }
        HorizontalDivider()
        SavedMeals(
            savedMeals = savedMeals,
            // A tap drops a COPY into the scratch builder; no identity comes with it.
            onLoad = { meal -> draft.replaceAll(meal.components) },
            onEdit = onEditMeal,
            onDelete = onDeleteMeal,
        )
        HorizontalDivider()
        FoodBuilder(customFoods, onSaveFood, onEditFood, onDeleteFood)
    }
}

@Composable
private fun BuilderSummary(
    draft: MealDraft,
    onResolve: suspend (List<MealComponent>) -> ResolvedMealCurve,
    onLogMeal: (List<MealComponent>) -> Unit,
    onSaveMeal: (String, List<MealComponent>) -> Unit,
) {
    val resolved = resolvedCurve(draft.components, onResolve)
    // The meal the Log press proposes, resolved carbs and component breakdown captured at the press
    // so the confirmation restates the row rather than whatever the list holds a moment later.
    var pending by remember { mutableStateOf<PendingLog.Meal?>(null) }
    // Carbs logged by the last "Log meal" press, so the builder can confirm the action once it
    // clears itself. Reset as soon as a new meal is started (a component is added).
    var justLoggedCarbs by rememberSaveable { mutableStateOf<Double?>(null) }
    var mealName by rememberSaveable { mutableStateOf("") }
    val isEmpty = draft.isEmpty
    val haptics = rememberT1dmHaptics()
    LaunchedEffect(isEmpty) { if (!isEmpty) justLoggedCarbs = null }

    if (isEmpty) {
        justLoggedCarbs?.let {
            Text(
                "✓ Logged ${"%.0f".format(it)} g carbs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "Search a food below",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        return
    }

    ComponentRows(draft)
    ResolvedCurveSummary(resolved)

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Propose, then (on confirm) log and clear the builder: the collapse to the confirmation line
        // is the feedback that the press took effect, and it also stops the no-feedback button from
        // being mashed into duplicate logged_meal rows.
        // Propose only — the dialog carries the Warn/Confirm/Reject and the receipt carries the Commit.
        Button(
            onClick = {
                haptics.perform(HapticEvent.Tap)
                pending = PendingLog.Meal(
                    grams = resolved.totalCarbs,
                    gi = null, // a builder meal carries a combined Ra curve, not one glycemic index
                    detail = draft.components.joinToString(", ") { "${it.name} ${it.grams.toInt()} g" },
                )
            },
            enabled = resolved.totalCarbs > 0.0,
        ) {
            Text("Log meal")
        }
        OutlinedButton(
            onClick = { haptics.perform(HapticEvent.Reject); draft.clear() },
        ) { Text("Clear") }
    }
    pending?.let { p ->
        ConfirmLogDialog(
            pending = p,
            onConfirm = {
                onLogMeal(draft.snapshot())
                draft.clear()
                justLoggedCarbs = p.grams
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = mealName,
            onValueChange = { mealName = it },
            label = { Text("Save as…") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                haptics.perform(HapticEvent.Confirm)
                onSaveMeal(mealName.ifBlank { "Meal" }, draft.snapshot())
                mealName = ""
            },
        ) { Text("Save") }
    }
}

@Composable
private fun SavedMeals(
    savedMeals: List<SavedMeal>,
    onLoad: (SavedMeal) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Text("Saved meals", style = MaterialTheme.typography.titleMedium)
    if (savedMeals.isEmpty()) {
        Text(
            "None yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        return
    }
    Text(
        "Tap loads a copy",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    savedMeals.forEach { meal ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).hapticClickable(HapticEvent.Confirm) { onLoad(meal) }) {
                Text(meal.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${meal.components.size} items · ${"%.0f".format(meal.totalCarbs)} g",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            RowActions(onEdit = { onEdit(meal.id) }, onDelete = { onDelete(meal.id) })
        }
    }
}

@Composable
private fun FoodBuilder(
    customFoods: List<Food>,
    onSaveFood: (Food) -> Unit,
    onEditFood: (Long) -> Unit,
    onDeleteFood: (Long) -> Unit,
) {
    // Saveable throughout: an editor route opened from a row below displaces this composition, and a
    // half-drafted food (a hand-drawn curve above all) must not be the price of checking one.
    var name by rememberSaveable { mutableStateOf("") }
    var carbsText by rememberSaveable { mutableStateOf("") }
    var giText by rememberSaveable { mutableStateOf("") }
    var useCustomCurve by rememberSaveable { mutableStateOf(false) }
    var curve by rememberSaveable(stateSaver = BezierCurveSaver) {
        mutableStateOf(BezierCurve.default(180.0))
    }
    val haptics = rememberT1dmHaptics()

    Text("Add a custom food", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = name, onValueChange = { name = it }, label = { Text("Name") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = carbsText,
            onValueChange = { carbsText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Carbs / 100 g") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = giText,
            onValueChange = { giText = it.filter { c -> c.isDigit() } },
            label = { Text("GI (opt.)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = useCustomCurve, onCheckedChange = { on -> haptics.toggled(on); useCustomCurve = on })
        Text(
            "Draw a custom appearance curve (overrides GI)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    val curveDegenerate = useCustomCurve && curve.isDegenerate()
    // Felt at the drag that flattens the curve, while the finger is still on the editor — the same
    // moment the Save button goes dead, which is otherwise the only sign anything happened.
    LaunchedEffect(curveDegenerate) { if (curveDegenerate) haptics.perform(HapticEvent.Warn) }
    if (useCustomCurve) {
        CurveEditor(curve = curve, onChange = { curve = it })
        if (curveDegenerate) {
            Text(
                "Flat curve encodes no carbs — draw a hump or turn it off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Button(
        onClick = {
            haptics.perform(HapticEvent.Confirm)
            val carbs = carbsText.toDoubleOrNull()
            if (name.isNotBlank() && carbs != null) {
                onSaveFood(
                    Food(
                        id = 0L, name = name.trim(), brand = null, carbsPer100g = carbs,
                        giOrNull = giText.toDoubleOrNull(), category = "Custom", source = "user",
                        custom = true,
                        customCurve = if (useCustomCurve) curve.sampleNormalized(1.0) else null,
                    ),
                )
                name = ""; carbsText = ""; giText = ""; useCustomCurve = false
            }
        },
        enabled = name.isNotBlank() && carbsText.toDoubleOrNull() != null && !curveDegenerate,
    ) { Text("Save food") }

    if (customFoods.isNotEmpty()) {
        Text("Your foods", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        customFoods.forEach { f ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(foodLabel(f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        foodFacts(f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                RowActions(onEdit = { onEditFood(f.id) }, onDelete = { onDeleteFood(f.id) })
            }
        }
    }
}
