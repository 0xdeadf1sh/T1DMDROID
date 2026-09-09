package com.t1dm.feature.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.KeyValueTable
import com.t1dm.core.design.exerciseKindLabel as kindLabel
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.logTimeLabel
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberHapticDetent
import com.t1dm.core.design.verticalScrollbar
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.LogMarker
import com.t1dm.core.model.EXERCISE_MAX_BOUT_MS
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.HindsightFrame
import com.t1dm.ui.graph.SessionScrubGraph
import com.t1dm.ui.graph.scrubCursorOf
import com.t1dm.ui.graph.sessionScrubRows

/** Display-only, via §8.4; no alarm/rail/calc/wire reads it. session null while loading. */
@Composable
fun ExerciseSessionScreen(
    session: ExerciseSession?,
    gridMs: Long,
    track: List<TrackPoint> = emptyList(),
    frame: GraphFrame = GraphFrame.EMPTY,
    hindsight: HindsightFrame? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    kovatchevF: ((Double) -> Double)? = null,
    thresholds: AlertThresholds? = null,
    /** Loaded over exactly reviewWindow, not the live Logs feed, bounded at a few hundred rows. */
    logMarkers: List<LogMarker> = emptyList(),
    rangeMinMgdl: Int? = null,
    rangeMaxMgdl: Int? = null,
) {
    if (session == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                "No session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        return
    }

    val scroll = rememberScrollState()
    val window = reviewWindow(session)
    val spanMs = window.last - window.first
    var fraction by remember(session.id) { mutableFloatStateOf(0f) }
    val cursorMs = scrubCursorOf(window.first, spanMs, fraction, gridMs)
    val detent = rememberHapticDetent(HapticEvent.ScrubTick)

    Column(
        Modifier.fillMaxSize().verticalScrollbar(scroll).fadingEdges(scroll).verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(kindLabel(session.kind), style = MaterialTheme.typography.titleMedium)
        Text(
            logTimeLabel(session.startMs, session.tzOffsetMin),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(summaryLine(session), style = MaterialTheme.typography.bodyMedium)
        interruptedNote(session)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (track.isEmpty()) {
            Text(
                "No track",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Card(colors = panelCardColors(), modifier = Modifier.fillMaxWidth()) {
                // The slider drives the dot, not the camera.
                ExerciseMap(
                    track,
                    Modifier.fillMaxWidth().height(MAP_HEIGHT),
                    cursor = trackPositionAt(track, cursorMs, gridMs),
                )
            }
        }

        SessionScrubGraph(
            frame = frame,
            cursorMs = cursorMs,
            windowStartMs = window.first,
            windowSpanMs = spanMs,
            sessionStartMs = session.startMs,
            sessionEndMs = session.endMs ?: session.startMs,
            modifier = Modifier.fillMaxWidth().height(GRAPH_HEIGHT),
            hindsight = hindsight,
            unit = unit,
            kovatchevF = kovatchevF,
            thresholds = thresholds,
            logMarkers = logMarkers,
            tzOffsetMin = session.tzOffsetMin,
            rangeMinMgdl = rangeMinMgdl,
            rangeMaxMgdl = rangeMaxMgdl,
        )

        Slider(
            value = fraction,
            onValueChange = { f ->
                fraction = f
                // The grid slot, never the raw Float: a pointer-move stream saturates the LRA.
                if (gridMs > 0L) detent.at(scrubCursorOf(window.first, spanMs, f, gridMs) / gridMs)
            },
        )

        KeyValueTable(
            sessionScrubRows(frame, hindsight, cursorMs, gridMs, unit, session.tzOffsetMin),
            numeric = true,
        )
        // Only where there is an absence to explain: over a drawn fan it reads as a disclaimer.
        val cycle = hindsight?.cycleAt(cursorMs.toDouble()) ?: -1
        val why = when {
            hindsight == null -> "No stored forecasts"
            cycle < 0 -> "No forecast issued here"
            hindsight.degenerateAt(cycle) -> "Forecast degenerate"
            hindsight.staleAt(cycle) -> "Anchor reading stale"
            else -> null
        }
        why?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.6f),
            )
        }
    }
}

/** The trailing reach is the point: the response a bout provokes lands after the bout ends. */
fun reviewWindow(session: ExerciseSession): LongRange {
    val end = session.endMs ?: session.startMs
    return (session.startMs - REVIEW_LEAD_MS)..(end + REVIEW_TRAIL_MS)
}

/** Stored flag says only "not the user": ran to EXERCISE_MAX_BOUT_MS, or cut short by death. */
internal fun interruptedNote(session: ExerciseSession): String? {
    if (!session.interrupted) return null
    val ranMs = (session.endMs ?: session.startMs) - session.startMs
    return if (ranMs >= EXERCISE_MAX_BOUT_MS) "Ended at the ${EXERCISE_MAX_BOUT_MS / 3_600_000L} h limit"
    else "Ended early — app stopped"
}

private const val REVIEW_LEAD_MS = 30L * 60_000L
private const val REVIEW_TRAIL_MS = 120L * 60_000L

private val MAP_HEIGHT = 200.dp
private val GRAPH_HEIGHT = 200.dp
