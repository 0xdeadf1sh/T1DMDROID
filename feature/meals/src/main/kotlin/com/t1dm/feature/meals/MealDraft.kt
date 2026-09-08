package com.t1dm.feature.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.BezierCurve
import com.t1dm.core.model.Food
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.ResolvedMealCurve
import com.t1dm.ui.graph.CurvePreview

/** Re-gram draft is POSITIONAL — names a slot, not a component; a slot mutation closes it. */
@Stable
internal class MealDraft(initial: List<MealComponent>) {
    val components: SnapshotStateList<MealComponent> =
        mutableStateListOf<MealComponent>().apply { addAll(initial) }

    var regramIndex by mutableStateOf<Int?>(null)
        private set
    var regramText by mutableStateOf("")
        private set

    val isEmpty: Boolean get() = components.isEmpty()

    fun snapshot(): List<MealComponent> = components.toList()

    fun add(component: MealComponent) = components.add(component)

    fun removeAt(index: Int) {
        closeRegram()
        components.removeAt(index)
    }

    fun replaceAll(next: List<MealComponent>) {
        closeRegram()
        components.clear()
        components.addAll(next)
    }

    fun clear() = replaceAll(emptyList())

    fun toggleRegram(index: Int) {
        if (regramIndex == index) return closeRegram()
        regramText = "%.0f".format(components[index].grams)
        regramIndex = index
    }

    fun editRegramText(text: String) {
        regramText = text.filter { it.isDigit() || it == '.' }
    }

    /** Committed on the button, never per keystroke: re-resolve keys on the list by value. */
    fun commitRegram(): Boolean {
        val i = regramIndex ?: return false
        val grams = regramText.toDoubleOrNull()
        if (grams == null || grams <= 0.0 || i !in components.indices) return false
        components[i] = components[i].copy(grams = grams)
        closeRegram()
        return true
    }

    fun closeRegram() {
        regramIndex = null
        regramText = ""
    }
}

/** Parallel Bundle-storable lists; Saveable since ✎ disposes composition. Re-gram unsaved. */
internal val MealDraftSaver: Saver<MealDraft, Any> = listSaver(
    save = { draft ->
        val c = draft.snapshot()
        listOf(
            ArrayList(c.map { it.name }),
            ArrayList(c.map { it.foodId ?: 0L }),
            ArrayList(c.map { it.grams }),
            ArrayList(c.map { it.carbsPer100g }),
            ArrayList(c.map { it.giOrNull ?: Double.NaN }),
            ArrayList(c.map { it.customCurve?.joinToString(",") ?: "" }),
        )
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val names = saved[0] as List<String>
        @Suppress("UNCHECKED_CAST")
        val ids = saved[1] as List<Long>
        @Suppress("UNCHECKED_CAST")
        val grams = saved[2] as List<Double>
        @Suppress("UNCHECKED_CAST")
        val per100 = saved[3] as List<Double>
        @Suppress("UNCHECKED_CAST")
        val gi = saved[4] as List<Double>
        @Suppress("UNCHECKED_CAST")
        val curves = saved[5] as List<String>
        MealDraft(
            names.indices.map { i ->
                MealComponent(
                    foodId = ids[i].takeIf { it != 0L },
                    name = names[i],
                    grams = grams[i],
                    carbsPer100g = per100[i],
                    giOrNull = gi[i].takeIf { !it.isNaN() },
                    customCurve = curves[i].takeIf { it.isNotEmpty() }
                        ?.split(",")?.mapNotNull { it.toDoubleOrNull() },
                )
            },
        )
    },
)

internal val BezierCurveSaver: Saver<BezierCurve, String> =
    Saver(save = { BezierCurve.encode(it) }, restore = { BezierCurve.decode(it) })

@Composable
internal fun rememberMealDraft(
    initial: List<MealComponent> = emptyList(),
    key: Any? = Unit,
): MealDraft = rememberSaveable(key, saver = MealDraftSaver) { MealDraft(initial) }

@Composable
internal fun resolvedCurve(
    components: List<MealComponent>,
    onResolve: suspend (List<MealComponent>) -> ResolvedMealCurve,
): ResolvedMealCurve {
    val snapshot = components.toList()
    val resolved by produceState(ResolvedMealCurve.EMPTY, snapshot) {
        value = if (snapshot.isEmpty()) ResolvedMealCurve.EMPTY else onResolve(snapshot)
    }
    return resolved
}

@Composable
internal fun ComponentRows(draft: MealDraft) {
    val haptics = rememberT1dmHaptics()
    draft.components.forEachIndexed { i, c ->
        // Name is the elastic member: unweighted it claims intrinsic width, starving the actions.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${c.name} · ${c.grams.toInt()} g",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Ellipsis, not default Clip: clipped reads as a shorter word, not a truncated one.
                Text(
                    "${"%.0f".format(c.carbs)} g carb",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Tap); draft.toggleRegram(i) },
                ) { Text("grams", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Reject); draft.removeAt(i) },
                ) { Text("remove", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (draft.regramIndex == i) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.regramText,
                    onValueChange = draft::editRegramText,
                    label = { Text("Portion (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        haptics.perform(
                            if (draft.commitRegram()) HapticEvent.Confirm else HapticEvent.Reject,
                        )
                    },
                ) { Text("Set") }
            }
        }
    }
}

@Composable
internal fun ResolvedCurveSummary(resolved: ResolvedMealCurve) {
    Text(
        "Total ${"%.0f".format(resolved.totalCarbs)} g carbs · peak ~${resolved.peakMin.toInt()} min",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text("Combined appearance (Ra) — grams per 5 min", style = MaterialTheme.typography.labelMedium)
    CurvePreview(values = resolved.values)
}

/** Bounded height nests this in an outer vertical scroll without infinite-constraint conflict. */
@Composable
internal fun FoodSearch(
    onSearch: suspend (String) -> List<Food>,
    onAdd: (Food, Double) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Food?>(null) }
    var gramsText by remember { mutableStateOf("100") }
    val results by produceState(emptyList<Food>(), query) {
        value = onSearch(query)
    }
    val haptics = rememberT1dmHaptics()

    Text("Add food", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it; selected = null },
        label = { Text("Search foods") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${results.size} match(es)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .verticalScrollbar(listState),
    ) {
        items(results, key = { it.id.takeIf { id -> id != 0L } ?: it.name }) { food ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .hapticClickable(HapticEvent.SegmentTick) { selected = food }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(foodLabel(food), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        foodFacts(food),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (selected?.id == food.id && food.id != 0L) Text("selected")
            }
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
                    if (g != null && g > 0.0) {
                        haptics.perform(HapticEvent.Confirm)
                        onAdd(food, g)
                        selected = null
                    } else {
                        haptics.perform(HapticEvent.Reject)
                    }
                },
            ) { Text("Add") }
        }
    }
}

/** Edit fires DragStart — picked up, not committed — unlike a tap-to-load's Confirm. */
@Composable
internal fun RowActions(onEdit: () -> Unit, onDelete: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    TextButton(
        onClick = { haptics.perform(HapticEvent.DragStart); onEdit() },
        modifier = Modifier.semantics { contentDescription = "Edit" },
    ) { Text("✎") }
    TextButton(
        onClick = { haptics.perform(HapticEvent.Reject); onDelete() },
        modifier = Modifier.semantics { contentDescription = "Delete" },
    ) { Text("🗑") }
}

internal fun foodLabel(f: Food): String = if (f.brand.isNullOrBlank()) f.name else "${f.name} (${f.brand})"

internal fun foodFacts(f: Food): String =
    "${"%.0f".format(f.carbsPer100g)} g/100 g" + (f.giOrNull?.let { " · GI ${it.toInt()}" } ?: " · GI —")

internal fun Food.toComponentUi(grams: Double): MealComponent = MealComponent(
    foodId = id.takeIf { it != 0L },
    name = name,
    grams = grams,
    carbsPer100g = carbsPer100g,
    giOrNull = giOrNull,
    customCurve = customCurve,
)
