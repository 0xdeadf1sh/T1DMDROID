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

/** How long to wait for a requested anchor to report a position before giving up on revealing it. */
private const val ANCHOR_WAIT_MS = 2_000L

/**
 * A scrollable settings page with consistent padding/spacing. U4 — the page name is carried by the
 * breadcrumb bar, so no in-view headline is rendered here; [screen] instead names the page to the
 * search index, and is what lets a scaffold recognise an anchor request as its own.
 *
 * It also owns the search-result landing: it holds the [androidx.compose.foundation.ScrollState], so
 * when [LocalSettingsFocus] carries an anchor belonging to [screen] the scaffold waits for that row to
 * report a position, scrolls it under the top edge, pulses it, and then releases the request. The
 * scroll branches on the global motion flag exactly as the bottom-nav auto-centre does — animated when
 * motion is on, an instant `scrollTo` when it is off.
 */
@Composable
fun SettingsScaffold(
    screen: SettingsScreenKey,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val registry = remember { SettingsAnchorRegistry() }
    val focus = LocalSettingsFocus.current
    val motion = animationsOn()
    // Clear of the top fade band, not merely clear of the edge: parked any shallower, the row the
    // search just jumped to lands under the mask and stays dimmed there — hardest on the pulse that
    // exists solely to say "this one".
    val padPx = with(LocalDensity.current) { (FADE_EDGE_DEPTH + 8.dp).roundToPx() }
    // Anchor ids are globally unique, so a request naturally fires on exactly one screen.
    val target = focus.pending?.takeIf { SettingsIndex.byId(it)?.screen == screen }
    // Keyed on the request ALONE: the scroll moves the anchor, so keying on its position too would
    // re-trigger the effect off its own effect.
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
        // Released whether or not the row was found: a conditionally-composed knob (the timed-cadence
        // period, the widget-pin buttons) may simply not be on the page right now, and a request that
        // never cleared would re-fire on the next visit.
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

/**
 * A clickable navigation row that drills into a sub-screen.
 *
 * This and the four controls below are where the app's haptics vocabulary reaches nearly all of
 * Settings: instrumenting these five covers every one of the ~20 sub-screens without a per-screen
 * edit, and — more importantly — guarantees that two knobs on two different pages feel identical.
 * A `disabled` ("soon") row stays silent: it is not refusing the press, it is not a control yet.
 *
 * The four controls below take a [SettingsKnob] rather than a bare label, and read their label and
 * subtitle out of it: that is what makes an unindexed knob a compile error (SettingsIndex.kt), and
 * what lets each carry a search anchor without growing a `modifier` parameter. This row is the
 * exception — it navigates rather than tunes, and the hub's rows are not index entries.
 */
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

/**
 * A prominent DANGER banner for the unbounded/safety-critical knobs.
 *
 * Deliberately SILENT. A [HapticEvent.Warn] belongs to a warning that *arises* — a refused
 * recommendation, a curve that would normalise to nothing, an erase being armed — and every one of
 * those fires at the interaction that produced it. This banner is page furniture: Alarms & safety
 * carries two of them permanently, so warning here would rumble twice on every visit, immediately
 * behind the NavSwitch that got you there, and teach the hand to ignore the one pattern it must not.
 */
@Composable
fun DangerBanner(text: String) {
    // While DEATH mode is engaged every safety warning across Settings is stilled.
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

/** An informational note in muted body text. */
@Composable
fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A +/- integer stepper with an explicit unit; unbounded above [min] unless [max] set. */
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
    // A stepper press IS a detent crossing, so it speaks the same SegmentTick a chip or a slider stop
    // does; the event's own rate-limit floor is what keeps a held-down ± from machine-gunning. At the
    // ends the button goes `enabled = false` and Compose swallows the press entirely — no lambda runs,
    // so a bound is silent rather than a reject. That is consistent app-wide (see the note on the
    // Accept button in the bolus calculator).
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

/** A +/- decimal stepper with an explicit unit; unbounded above [min] unless [max] set. */
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

/** A labelled switch row; its subtitle is the index entry's, so the two cannot disagree. */
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
        // `on` is the value being ADOPTED, which is what the mirrored ToggleOn/ToggleOff pair must
        // describe — `checked` still holds the old one at this point.
        Switch(checked = checked, onCheckedChange = { on -> haptics.toggled(on); onCheckedChange(on) })
    }
}

/**
 * A row of single-select chips (an enum picker).
 *
 * [tickOnSelect] exists for exactly one caller: the haptics-intensity picker itself, which answers a
 * tap by PLAYING the level just chosen. Its own detent would be a second buzz at the OLD intensity,
 * immediately superseded on the actuator — so it opts out and lets the preview be the whole feedback.
 */
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
