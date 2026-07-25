package com.t1dm.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → BG graph range (Phase 7A item 1). The glucose Y-axis always spans at
 * least [minMgdl]..[maxMgdl]; it grows ABOVE the ceiling to never clip a high reading. Both are
 * mg/dL and stepped in 5-mg/dL increments; [onChange] persists the new pair (`min < max` enforced
 * upstream). Pure/stateless — the caller owns the persisted value.
 *
 * It also hosts the BG **input filter** ([smoothingWindow], one of [smoothingStops]). That knob is
 * not a drawing preference despite living here: the same window filters the channel the model reads
 * and shifts the `last_bg` anchor the dose calculator gates on, so the section states as much and
 * the decision card discloses a non-default value at the point of decision.
 * [smoothingPreviewMgdl] is a recent slice of the user's own trace (oldest→newest) and [smoothMgdl]
 * the native smoother `(series, window) -> series`, so the miniature shows the effect on real data.
 */
@Composable
fun GraphSettingsScreen(
    minMgdl: Int,
    maxMgdl: Int,
    windowHours: Int,
    windowPresets: List<Int>,
    onChange: (Int, Int) -> Unit,
    onSetWindow: (Int) -> Unit,
    smoothingWindow: Int,
    smoothingStops: List<Int>,
    onSetSmoothing: (Int) -> Unit,
    smoothingPreviewMgdl: DoubleArray = DoubleArray(0),
    smoothMgdl: ((DoubleArray, Int) -> DoubleArray)? = null,
) {
    SettingsScaffold(SettingsScreenKey.GRAPH) {
        SettingsSectionHeader("Glucose axis")
        SettingsNote("Expands above the ceiling; highs never clip")

        SettingsAnchor(graphFloor) {
            Stepper(
                label = "Floor",
                value = minMgdl,
                onDown = { onChange((minMgdl - 5).coerceAtLeast(0), maxMgdl) },
                onUp = { onChange((minMgdl + 5).coerceAtMost(maxMgdl - 5), maxMgdl) },
            )
        }
        SettingsAnchor(graphCeiling) {
            Stepper(
                label = "Ceiling",
                value = maxMgdl,
                onDown = { onChange(minMgdl, (maxMgdl - 5).coerceAtLeast(minMgdl + 5)) },
                onUp = { onChange(minMgdl, maxMgdl + 5) },
            )
        }

        SettingsSectionHeader("Default time window")
        SettingsAnchor(graphWindow) {
            val haptics = rememberT1dmHaptics()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                windowPresets.forEach { h ->
                    FilterChip(
                        selected = h == windowHours,
                        onClick = { haptics.perform(HapticEvent.SegmentTick); onSetWindow(h) },
                        label = { Text("${h}h") },
                    )
                }
            }
        }

        SettingsSectionHeader("BG input filter")
        SettingsAnchor(graphSmoothing) {
            SmoothingSection(
                window = smoothingWindow,
                stops = smoothingStops,
                onSet = onSetSmoothing,
                previewMgdl = smoothingPreviewMgdl,
                smoothMgdl = smoothMgdl,
            )
        }
    }
}

@Composable
private fun SmoothingSection(
    window: Int,
    stops: List<Int>,
    onSet: (Int) -> Unit,
    previewMgdl: DoubleArray,
    smoothMgdl: ((DoubleArray, Int) -> DoubleArray)?,
) {
    val haptics = rememberT1dmHaptics()
    Text(
        "Filters the model input, not just the drawing",
        style = MaterialTheme.typography.bodyMedium,
    )
    // Snap to the five detents; thumb position ↔ window index. The haptic tick and the persisted set
    // fire only as the snapped stop crosses a boundary, not on every drag frame.
    // Keyed on `window`: it arrives from a cold flow, so the FIRST composition sees the placeholder
    // default and an unkeyed remember would seed the thumb from that and never adopt the persisted
    // value — leaving the slider showing a window the smoother is not using.
    val startIdx = stops.indexOf(window).coerceAtLeast(0)
    var sliderPos by remember(window) { mutableFloatStateOf(startIdx.toFloat()) }
    var lastIdx by remember(window) { mutableIntStateOf(startIdx) }
    Column {
        Slider(
            value = sliderPos,
            onValueChange = { raw ->
                sliderPos = raw
                val i = raw.roundToInt().coerceIn(0, stops.lastIndex)
                if (i != lastIdx) {
                    lastIdx = i
                    haptics.perform(HapticEvent.SegmentTick)
                    onSet(stops[i])
                }
            },
            valueRange = 0f..stops.lastIndex.toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Off",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                "Heavy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }

    if (smoothMgdl != null && previewMgdl.size >= 2) {
        // The smooth is a JNI hop; keep it off the composition thread and rebuild only when the
        // series or the window actually changes.
        val smoothed by produceState<DoubleArray?>(null, previewMgdl, window) {
            value = withContext(Dispatchers.Default) {
                runCatching { smoothMgdl(previewMgdl, window) }.getOrNull()?.takeIf { it.size == previewMgdl.size }
            }
        }
        SmoothingPreview(previewMgdl, smoothed)
        Text(
            "Last few hours — raw faint, filtered over it",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/** Raw (faint) against the filtered signal (accent) over the same recent slice, autoscaled to both. */
@Composable
private fun SmoothingPreview(raw: DoubleArray, smoothed: DoubleArray?) {
    val rawColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val smoothColor = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in raw) { if (v < lo) lo = v; if (v > hi) hi = v }
        smoothed?.forEach { v -> if (v < lo) lo = v; if (v > hi) hi = v }
        if (!lo.isFinite() || !hi.isFinite()) return@Canvas
        val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
        fun path(series: DoubleArray): Path = Path().apply {
            series.forEachIndexed { i, v ->
                val x = size.width * i / (series.size - 1).coerceAtLeast(1)
                val y = size.height * (1.0 - (v - lo) / span).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path(raw), rawColor, style = Stroke(width = 2f))
        smoothed?.let { drawPath(path(it), smoothColor, style = Stroke(width = 3f)) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onDown: () -> Unit, onUp: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        // I13 — the ± buttons keep their intrinsic size; the VALUE field flexes (weight) and centres, so
        // a wide value can never balloon the steppers.
        OutlinedButton(onClick = { haptics.perform(HapticEvent.SegmentTick); onDown() }) { Text("−5") }
        Text(
            text = "$value mg/dL",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { haptics.perform(HapticEvent.SegmentTick); onUp() }) { Text("+5") }
    }
}

// ── search index (see SettingsIndex.kt) ───────────────────────────────────────────────────────────

private val graphFloor = SettingsKnob(
    id = "graph.floor",
    screen = SettingsScreenKey.GRAPH,
    section = "Glucose axis",
    label = "Floor",
    subtitle = "Lowest value the Y-axis always covers, in mg/dL",
    synonyms = listOf(
        "floor", "minimum", "min", "bottom", "lower bound", "y axis", "axis", "scale", "range",
        "graph range", "chart", "zoom",
    ),
)

private val graphCeiling = SettingsKnob(
    id = "graph.ceiling",
    screen = SettingsScreenKey.GRAPH,
    section = "Glucose axis",
    label = "Ceiling",
    subtitle = "The axis top; it still expands above this so a high reading is never clipped",
    synonyms = listOf(
        "ceiling", "maximum", "max", "top", "upper bound", "y axis", "axis", "scale", "range",
        "graph range", "chart", "clip", "zoom",
    ),
)

private val graphWindow = SettingsKnob(
    id = "graph.window",
    screen = SettingsScreenKey.GRAPH,
    section = "Default time window",
    label = "Default time window",
    subtitle = "Hours of history the graph opens on",
    synonyms = listOf(
        "window", "time window", "timespan", "span", "hours", "history", "3h", "6h", "12h", "24h",
        "default view", "x axis", "zoom", "period",
    ),
)

private val graphSmoothing = SettingsKnob(
    id = "graph.smoothing",
    screen = SettingsScreenKey.GRAPH,
    section = "BG input filter",
    label = "BG input filter",
    subtitle = "Filter window fed to the model",
    synonyms = listOf(
        "smoothing", "smooth", "filter", "savgol", "savitzky", "golay", "noise", "denoise",
        "jitter", "spiky", "input filter", "causal", "window", "moving average", "quadratic",
        "anchor", "off", "heavy",
    ),
)

internal val settingsGraphKnobs = listOf(graphFloor, graphCeiling, graphWindow, graphSmoothing)
