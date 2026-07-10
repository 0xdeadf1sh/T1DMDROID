package com.t1dm.feature.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.Food
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.ResolvedMealCurve
import com.t1dm.core.model.SavedMeal
import com.t1dm.ui.graph.CurveEditor
import com.t1dm.ui.graph.CurvePreview
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * The multi-food meal builder (Phase 4 deliverable 3/4) — the rich seam the simple
 * carb-entry [MealsScreen] points at. It searches the bundled glycemic dictionary (FTS5), assembles
 * a portioned component list, shows the LIVE combined **appearance (Ra)** curve
 * ([MealCurveResolver], via [onResolve]) with its total carbs + peak time, and can save the meal,
 * log it (reshaping the forecast), add a custom food (with an optional drawn appearance curve), and
 * recall a saved meal. Stateless + callback-driven; all resolution/persistence is off-thread in the
 * caller.
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
    onDeleteFood: (Long) -> Unit,
    onDeleteMeal: (Long) -> Unit,
) {
    val components: SnapshotStateList<MealComponent> = remember { mutableStateListOf() }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Meal builder", style = MaterialTheme.typography.titleLarge)

        BuilderSummary(components, onResolve, onLogMeal, onSaveMeal)
        HorizontalDivider()
        FoodSearch(onSearch) { food, grams ->
            components.add(food.toComponentUi(grams))
        }
        HorizontalDivider()
        SavedMeals(savedMeals, onDeleteMeal) { meal ->
            components.clear(); components.addAll(meal.components)
        }
        HorizontalDivider()
        FoodBuilder(customFoods, onSaveFood, onDeleteFood)
    }
}

@Composable
private fun BuilderSummary(
    components: SnapshotStateList<MealComponent>,
    onResolve: suspend (List<MealComponent>) -> ResolvedMealCurve,
    onLogMeal: (List<MealComponent>) -> Unit,
    onSaveMeal: (String, List<MealComponent>) -> Unit,
) {
    val snapshot = components.toList()
    val resolved by produceState(ResolvedMealCurve.EMPTY, snapshot) {
        value = if (snapshot.isEmpty()) ResolvedMealCurve.EMPTY else onResolve(snapshot)
    }
    var mealName by remember { mutableStateOf("") }

    if (components.isEmpty()) {
        Text(
            "Search a food below to start building a meal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        return
    }

    components.forEachIndexed { i, c ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${c.name} · ${c.grams.toInt()} g", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${"%.0f".format(c.carbs)} g carb", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { components.removeAt(i) }) { Text("remove") }
            }
        }
    }

    Text(
        "Total ${"%.0f".format(resolved.totalCarbs)} g carbs · peak ~${resolved.peakMin.toInt()} min",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text("Combined appearance (Ra) — grams per 5 min", style = MaterialTheme.typography.labelMedium)
    CurvePreview(values = resolved.values)

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onLogMeal(components.toList()) }, enabled = resolved.totalCarbs > 0.0) {
            Text("Log meal")
        }
        OutlinedButton(onClick = { components.clear() }) { Text("Clear") }
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
            onClick = { onSaveMeal(mealName.ifBlank { "Meal" }, components.toList()); mealName = "" },
            enabled = components.isNotEmpty(),
        ) { Text("Save") }
    }
}

@Composable
private fun FoodSearch(
    onSearch: suspend (String) -> List<Food>,
    onAdd: (Food, Double) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Food?>(null) }
    var gramsText by remember { mutableStateOf("100") }
    val results by produceState(emptyList<Food>(), query) {
        value = onSearch(query)
    }

    Text("Add food", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it; selected = null },
        label = { Text("Search the food dictionary") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    results.take(12).forEach { food ->
        Row(
            Modifier.fillMaxWidth().clickable { selected = food }.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(foodLabel(food), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${"%.0f".format(food.carbsPer100g)} g/100 g" +
                        (food.giOrNull?.let { " · GI ${it.toInt()}" } ?: " · GI —"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (selected?.id == food.id && food.id != 0L) Text("selected")
        }
    }
    selected?.let { food ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = gramsText,
                onValueChange = { gramsText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Portion (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val g = gramsText.toDoubleOrNull()
                    if (g != null && g > 0.0) { onAdd(food, g); selected = null }
                },
            ) { Text("Add") }
        }
    }
}

@Composable
private fun SavedMeals(
    savedMeals: List<SavedMeal>,
    onDelete: (Long) -> Unit,
    onLoad: (SavedMeal) -> Unit,
) {
    Text("Saved meals", style = MaterialTheme.typography.titleMedium)
    if (savedMeals.isEmpty()) {
        Text(
            "None yet — build a meal and Save it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        return
    }
    savedMeals.forEach { meal ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).clickable { onLoad(meal) }) {
                Text(meal.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${meal.components.size} items · ${"%.0f".format(meal.totalCarbs)} g",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = { onDelete(meal.id) }) { Text("delete") }
        }
    }
}

@Composable
private fun FoodBuilder(
    customFoods: List<Food>,
    onSaveFood: (Food) -> Unit,
    onDeleteFood: (Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var carbsText by remember { mutableStateOf("") }
    var giText by remember { mutableStateOf("") }
    var useCustomCurve by remember { mutableStateOf(false) }
    var curve by remember { mutableStateOf(BezierCurve.default(180.0)) }

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
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Switch(checked = useCustomCurve, onCheckedChange = { useCustomCurve = it })
        Text(
            "Draw a custom appearance curve (overrides GI)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    val curveDegenerate = useCustomCurve && curve.isDegenerate()
    if (useCustomCurve) {
        CurveEditor(curve = curve, onChange = { curve = it })
        if (curveDegenerate) {
            Text(
                "The custom appearance curve is flat — it would encode no carbs. Draw a hump, or turn the " +
                    "custom curve off to fall back to the GI preset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Button(
        onClick = {
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(foodLabel(f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onDeleteFood(f.id) }) { Text("delete") }
            }
        }
    }
}

private fun foodLabel(f: Food): String = if (f.brand.isNullOrBlank()) f.name else "${f.name} (${f.brand})"

/** Build a portioned component from a dictionary food (kept in the UI layer; no `:data` types). */
private fun Food.toComponentUi(grams: Double): MealComponent = MealComponent(
    foodId = id.takeIf { it != 0L },
    name = name,
    grams = grams,
    carbsPer100g = carbsPer100g,
    giOrNull = giOrNull,
    customCurve = customCurve,
)
