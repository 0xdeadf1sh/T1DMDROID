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

/**
 * One recorded bout, reviewed: where it went, what glucose did around it, and what the model believed
 * at any instant the slider is dragged to.
 *
 * The window deliberately opens before the bout and closes well after it — see [reviewWindow] — because
 * the response a bout provokes lands after the bout has ended, and a review cut at the stop instant
 * would show the cause and hide the effect.
 *
 * Everything drawn here is DISPLAY-ONLY. The swept fan is stored rows read back through the same §8.4
 * band correction the BG panel's own fans wear, so the three state one uncertainty rather than three;
 * none of it reaches an alarm, a rail, a calculator or the wire.
 *
 * [session] is null while the lookup is in flight and for an id that no longer resolves — a row deleted
 * from the hub with its review still on the back stack. Both say so rather than render an empty frame
 * that reads as a bout with nothing in it.
 */
@Composable
fun ExerciseSessionScreen(
    session: ExerciseSession?,
    gridMs: Long,
    track: List<TrackPoint> = emptyList(),
    frame: GraphFrame = GraphFrame.EMPTY,
    hindsight: HindsightFrame? = null,
    unit: UnitSpace = UnitSpace.MgDl,
    thresholds: AlertThresholds? = null,
    /**
     * The carbohydrate and insulin logged over the review window.
     *
     * Loaded over exactly [reviewWindow] by the caller rather than taken from the live Logs feed:
     * that feed is bounded at a few hundred rows, so it would be empty for a bout from last month —
     * which is exactly the bout a review is for.
     */
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

        // The map is an opaque View that follows neither the palette nor the font, so it is clipped
        // into the panel card's own shape rather than left to punch a raw rectangle through the
        // backdrop. Withheld entirely for a bout with no fixes: a map centred on nothing says nothing
        // and fetches tiles to say it.
        if (track.isEmpty()) {
            Text(
                "No track",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Card(colors = panelCardColors(), modifier = Modifier.fillMaxWidth()) {
                // The slider drives the dot, and nothing else: the camera does not follow it. The
                // dot is withheld outside the track's own span and across a rest with nothing
                // recorded near the cursor — see [trackPositionAt] — rather than pinned to an end.
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
                // The GRID SLOT, never the raw Float: a pointer-move stream fed straight to the detent
                // saturates the LRA into a flat buzz instead of a texture that tracks the data.
                if (gridMs > 0L) detent.at(scrubCursorOf(window.first, spanMs, f, gridMs) / gridMs)
            },
        )

        KeyValueTable(
            sessionScrubRows(frame, hindsight, cursorMs, gridMs, unit, session.tzOffsetMin),
            numeric = true,
        )
        // The one thing two blank rows cannot say on their own: WHY there is no forecast to read. Only
        // ever shown where there is one to explain — an absence with no cause reads as a broken chart,
        // and a caption standing over a fan that IS drawn reads as a disclaimer on it.
        //
        // The last two branches are the cursor sitting on a cycle the app itself refused: the chart
        // dashes that median and the read-out withholds its number, so this is the only thing on the
        // screen that can name which refusal it was. The wording is the BG panel's own.
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

/**
 * The stretch of record a bout is reviewed over: the half hour before it, and the two hours after it.
 *
 * The trailing reach is the point of the window rather than padding on it. A bout moves glucose for
 * hours after it ends, and the forecasts worth sweeping are the ones issued while that was happening —
 * cut at the stop instant, the review would show only the forecasts made before anything had happened
 * yet. Defined here once so `:app` loads exactly the window this screen draws.
 */
fun reviewWindow(session: ExerciseSession): LongRange {
    val end = session.endMs ?: session.startMs
    return (session.startMs - REVIEW_LEAD_MS)..(end + REVIEW_TRAIL_MS)
}

/**
 * Why a bout ended where it did, when the user is not the one who ended it — or null when they were.
 *
 * The stored flag says only "not the user", so the two causes are told apart by the one thing that
 * distinguishes them: a bout closed by [EXERCISE_MAX_BOUT_MS] ran exactly to it, and a bout closed
 * after a process death was cut at its last recorded fix. The limit is quoted rather than described,
 * and read from the constant the service enforces so the two cannot drift apart.
 */
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
