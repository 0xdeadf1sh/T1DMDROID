package com.t1dm.feature.twin

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.TwinAction
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinSource
import com.t1dm.core.model.TwinTrait
import java.util.Locale

/** [latest] null: no scan yet, and the twin stands at MakeHuman's neutral. */
@Composable
fun TwinScreen(
    latest: TwinScan?,
    scanCount: Int,
    overrides: Map<String, Double>,
    showTutorial: Boolean,
    onScan: () -> Unit,
    onMockScan: () -> Unit,
    onTrends: () -> Unit,
    onOverride: (key: String, value: Double?) -> Unit,
    onResetOverrides: () -> Unit,
    onTutorialDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberT1dmHaptics()
    val cs = MaterialTheme.colorScheme
    val draft = remember { mutableStateMapOf<String, Double>() }
    var editing by remember { mutableStateOf(false) }
    var tutorial by remember(showTutorial) { mutableStateOf(showTutorial) }
    val tut = remember { TutorialState() }
    // A draft yields only once the stored edit matches it, so the body never snaps back mid-write.
    LaunchedEffect(overrides) {
        draft.keys.toList().forEach { k -> if (overrides[k] == draft[k]) draft.remove(k) }
    }
    val effective = effectiveScan(latest, overrides, draft)
    var trait by remember { mutableStateOf(TwinTrait.HEIGHT) }
    val edited = overrides.keys + draft.keys
    val panelAlpha by animateFloatAsState(if (editing) 0f else 1f, label = "panel")

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().weight(1f).tutorialTarget(tut, TutorialTarget.BODY)) {
                TwinView(
                    effective,
                    Modifier.fillMaxSize(),
                    onOrbit = { tut.orbitRad += it },
                    onZoom = { tut.zoomLog += it },
                    callouts = !editing,
                )
                if (panelAlpha > 0f) {
                    TwinPanel(
                        effective,
                        listOf(
                            PanelAction(TwinAction.SCAN, "Scan", { haptics.perform(HapticEvent.Tap); onScan() }),
                            PanelAction(TwinAction.EDIT, "Edit", { editing = true }, latest != null, TutorialTarget.EDIT),
                            PanelAction(TwinAction.TRENDS, "Trends", onTrends, scanCount > 0),
                            PanelAction(
                                TwinAction.MOCK, "Mock",
                                { haptics.perform(HapticEvent.Tap); onMockScan() },
                                target = TutorialTarget.MOCK,
                            ),
                            PanelAction(TwinAction.HELP, "Help", { tutorial = true }),
                        ),
                        tut,
                        Modifier.fillMaxSize().alpha(panelAlpha),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tag = when {
                        latest == null -> "NO SCAN"
                        latest.source == TwinSource.MOCK -> "MOCK"
                        else -> latest.source.uppercase(Locale.ROOT)
                    }
                    Text(
                        if (overrides.isEmpty()) tag else "$tag | EDITED",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.secondary,
                    )
                    Text("$scanCount scans", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
            if (editing && effective != null) {
                BackHandler { editing = false }
                TwinEditBar(
                    selected = trait,
                    value = effective[trait] ?: trait.mid(),
                    edited = edited,
                    onSelect = { haptics.perform(HapticEvent.Tap); trait = it },
                    onDrag = { draft[trait.key] = it },
                    onCommit = {
                        draft[trait.key]?.let { onOverride(trait.key, it) }
                        tut.slides++
                    },
                    onReset = {
                        draft.clear()
                        onResetOverrides()
                    },
                    onDone = { editing = false },
                    modifier = Modifier.tutorialTarget(tut, TutorialTarget.PANEL),
                )
            }
        }
        if (tutorial) {
            TwinTutorial(tut, hasScan = latest != null, editing = editing, onDone = {
                tutorial = false
                onTutorialDone()
            })
        }
    }
}
