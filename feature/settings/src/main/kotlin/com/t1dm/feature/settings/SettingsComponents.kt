package com.t1dm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.FADE_EDGE_DEPTH
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.PULSE_HIGHLIGHT_MS
import com.t1dm.core.design.animationsOn
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.rememberT1dmHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val ANCHOR_WAIT_MS = 2_000L

/** [screen] names the page to the index, letting this scaffold claim its own anchor requests. */
@Composable
fun SettingsScaffold(
    screen: SettingsScreenKey,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val registry = remember { SettingsAnchorRegistry() }
    val focus = LocalSettingsFocus.current
    val motion = animationsOn()
    // Clear of the top fade band, not merely the edge, or the landed row sits under the mask.
    val padPx = with(LocalDensity.current) { (FADE_EDGE_DEPTH + 8.dp).roundToPx() }
    // Anchor ids are globally unique, so a request fires on exactly one screen.
    val target = focus.pending?.takeIf { SettingsIndex.byId(it)?.screen == screen }
    // Keyed on the request alone: keying on the position would re-trigger off its own scroll.
    LaunchedEffect(target) {
        val id = target
        if (id == null) {
            registry.wanted = null
            return@LaunchedEffect
        }
        registry.wantedRootY = null
        registry.wanted = id
        val y = withTimeoutOrNull(ANCHOR_WAIT_MS) {
            snapshotFlow { registry.wantedRootY }.filterNotNull().first()
        }
        if (y != null) {
            val to = (y - registry.originY + scroll.value - padPx).coerceIn(0, scroll.maxValue)
            if (motion) scroll.animateScrollTo(to) else scroll.scrollTo(to)
            registry.focused = id
            delay(PULSE_HIGHLIGHT_MS)
            registry.focused = null
        }
        registry.wanted = null
        // Released even unfound; an uncleared request re-fires on the next visit.
        focus.request(null)
    }
    CompositionLocalProvider(LocalSettingsAnchors provides registry) {
        Column(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { registry.originY = it.positionInRoot().y.toInt() }
                .fadingEdges(scroll)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
fun SettingsSectionHeader(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Bare label, not [SettingsKnob]: hub rows navigate, not index entries. Disabled ⇒ silent. */
@Composable
fun SettingsNavRow(label: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit = {}) {
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (enabled) it.clickable { haptics.perform(HapticEvent.NavSwitch); onClick() } else it
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (enabled) "›" else "soon", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Deliberately silent: page furniture, so a [HapticEvent.Warn] here would fire on every visit. */
@Composable
fun DangerBanner(text: String) {
    if (com.t1dm.core.design.LocalDeathMode.current) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Unbounded above unless [max] is set. */
@Composable
fun IntStepper(
    knob: SettingsKnob,
    value: Int,
    unit: String = "",
    step: Int = 5,
    min: Int = 0,
    max: Int? = null,
    onChange: (Int) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier.settingsAnchor(knob.id).fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(knob.label, style = MaterialTheme.typography.bodyMedium)
            Text("$value $unit".trim(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        OutlinedButton(
            onClick = {
                haptics.perform(HapticEvent.SegmentTick)
                onChange((value - step).coerceAtLeast(min))
            },
            enabled = value > min,
        ) { Text("−$step") }
        Button(
            onClick = {
                haptics.perform(HapticEvent.SegmentTick)
                onChange(max?.let { (value + step).coerceAtMost(it) } ?: (value + step))
            },
            enabled = max == null || value < max,
        ) { Text("+$step") }
    }
}

/** Unbounded above unless [max] is set. */
@Composable
fun DoubleStepper(
    knob: SettingsKnob,
    value: Double,
    unit: String = "",
    step: Double = 0.5,
    min: Double = 0.0,
    max: Double? = null,
    onChange: (Double) -> Unit,
) {
    fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier.settingsAnchor(knob.id).fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(knob.label, style = MaterialTheme.typography.bodyMedium)
            Text("${fmt(value)} $unit".trim(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        OutlinedButton(
            onClick = {
                haptics.perform(HapticEvent.SegmentTick)
                onChange((value - step).coerceAtLeast(min))
            },
            enabled = value > min,
        ) { Text("−${fmt(step)}") }
        Button(
            onClick = {
                haptics.perform(HapticEvent.SegmentTick)
                onChange(max?.let { (value + step).coerceAtMost(it) } ?: (value + step))
            },
            enabled = max == null || value < max,
        ) { Text("+${fmt(step)}") }
    }
}

@Composable
fun ToggleRow(knob: SettingsKnob, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier.settingsAnchor(knob.id).fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(knob.label, style = MaterialTheme.typography.bodyLarge)
            if (knob.subtitle.isNotEmpty()) {
                Text(knob.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // `on` is the value being adopted; `checked` still holds the old one.
        Switch(checked = checked, onCheckedChange = { on -> haptics.toggled(on); onCheckedChange(on) })
    }
}

/** [tickOnSelect] is false only for the haptics-intensity picker, which plays the chosen level. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> ChipPicker(
    knob: SettingsKnob,
    options: List<Pair<T, String>>,
    selected: T,
    tickOnSelect: Boolean = true,
    onSelect: (T) -> Unit,
) {
    val haptics = rememberT1dmHaptics()
    Column(Modifier.settingsAnchor(knob.id).fillMaxWidth().padding(vertical = 4.dp)) {
        Text(knob.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = {
                        if (tickOnSelect) haptics.perform(HapticEvent.SegmentTick)
                        onSelect(value)
                    },
                    label = { Text(text) },
                )
            }
        }
    }
}
