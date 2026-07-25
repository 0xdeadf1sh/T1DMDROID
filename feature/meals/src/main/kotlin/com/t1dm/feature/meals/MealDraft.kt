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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
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

/**
 * The portioned component list being assembled, plus the ONE open re-gram draft that indexes it —
 * shared verbatim by the new-meal builder ([MealBuilderScreen]) and the stored-meal editor
 * ([MealEditorScreen]).
 *
 * The draft is POSITIONAL: it names a slot, not a component. Every mutation that shifts or replaces
 * what lives at that slot must therefore drop it, else `Set` writes the portion typed for one food
 * into whatever slid underneath. Binding the list and the draft into one holder makes that
 * structural rather than a rule each call site has to remember — the list is only reachable through
 * methods that close the draft when they must.
 */
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

    /** Drop a whole meal in (load a copy, seed the editor); the draft cannot survive it. */
    fun replaceAll(next: List<MealComponent>) {
        closeRegram()
        components.clear()
        components.addAll(next)
    }

    fun clear() = replaceAll(emptyList())

    /** Open the re-gram field on [index] seeded with that portion, or close it if it is already open. */
    fun toggleRegram(index: Int) {
        if (regramIndex == index) return closeRegram()
        regramText = "%.0f".format(components[index].grams)
        regramIndex = index
    }

    fun editRegramText(text: String) {
        regramText = text.filter { it.isDigit() || it == '.' }
    }

    /**
     * Commit the open draft; false when it will not parse or is non-positive, which the caller refuses
     * audibly rather than swallowing. Committing on the button (never per keystroke) also keeps the
     * off-thread re-resolve — keyed on the component list BY VALUE — off the typing path.
     */
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

/**
 * The draft as parallel Bundle-storable lists, one entry per component.
 *
 * Saveable rather than merely remembered because the builder's ✎ affordances PUSH a destination
 * (`meals/builder/meal/{id}`, `…/food/{id}`), and Navigation-Compose disposes the composition it
 * displaces: a plain `remember` drops a ten-component meal on the floor the moment the user opens an
 * existing row to check its GI, with nothing to restore it from on the way back. The custom curve
 * travels as [com.t1dm.core.model.Food.customCurve] already is — a list of samples — flattened to a
 * string, and a null id/GI as the sentinels the rest of the module already uses.
 *
 * The open re-gram field is deliberately not carried: it is a half-typed portion, not work.
 */
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

/** A drawn shape through its own compact kv encoding, so it survives the same push. */
internal val BezierCurveSaver: Saver<BezierCurve, String> =
    Saver(save = { BezierCurve.encode(it) }, restore = { BezierCurve.decode(it) })

/** A draft that survives recomposition AND a route push, re-seeded only when [key] changes (the
 *  edited meal's id). */
@Composable
internal fun rememberMealDraft(
    initial: List<MealComponent> = emptyList(),
    key: Any? = Unit,
): MealDraft = rememberSaveable(key, saver = MealDraftSaver) { MealDraft(initial) }

/** The live combined **appearance (Ra)** curve for [components], resolved off-thread by the caller. */
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

/** One row per component, with the inline re-gram field beneath whichever row has it open. */
@Composable
internal fun ComponentRows(draft: MealDraft) {
    val haptics = rememberT1dmHaptics()
    draft.components.forEachIndexed { i, c ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${c.name} · ${c.grams.toInt()} g", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${"%.0f".format(c.carbs)} g carb", style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Tap); draft.toggleRegram(i) },
                ) { Text("grams") }
                TextButton(
                    onClick = { haptics.perform(HapticEvent.Reject); draft.removeAt(i) },
                ) { Text("remove") }
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

/** Total carbs, appearance peak, and the combined Ra preview for a resolved component list. */
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

/**
 * The FTS5 dictionary search and its bounded, independently-scrollable result list (N8 — the whole
 * seeded catalogue is reachable, not a clipped first-N slice). A bounded height lets it nest inside
 * an outer vertical scroll without an infinite-constraint conflict.
 */
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
                // A component joining the meal is a real addition to the thing being built, so it
                // confirms; a portion that will not parse is refused rather than silently ignored.
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

/**
 * The ✎ / 🗑 pair every editable row carries. Edit is "picked up, not committed" ([HapticEvent.DragStart])
 * — deliberately unlike the [HapticEvent.Confirm] a plain tap-to-load fires — and delete keeps its
 * [HapticEvent.Reject].
 */
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

/** A food's per-100 g facts — what makes it legible as an INGREDIENT rather than a portioned meal. */
internal fun foodFacts(f: Food): String =
    "${"%.0f".format(f.carbsPer100g)} g/100 g" + (f.giOrNull?.let { " · GI ${it.toInt()}" } ?: " · GI —")

/** Build a portioned component from a dictionary food (kept in the UI layer; no `:data` types). */
internal fun Food.toComponentUi(grams: Double): MealComponent = MealComponent(
    foodId = id.takeIf { it != 0L },
    name = name,
    grams = grams,
    carbsPer100g = carbsPer100g,
    giOrNull = giOrNull,
    customCurve = customCurve,
)
