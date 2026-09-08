package com.t1dm.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.hapticClickable
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.design.verticalScrollbar

/** Tall enough for a screen of hits, short enough to leave the hub's rows visible. */
private val RESULTS_MAX_HEIGHT = 320.dp

/** Grouped by destination (labels alone collide); recorded on RESULT TAP; [recent] via `:app`. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsSearchBar(
    index: List<SettingsKnob>,
    recent: List<String>,
    onOpen: (SettingsKnob) -> Unit,
    onRecordSearch: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, index) { searchSettings(query, index) }
    val haptics = rememberT1dmHaptics()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search settings") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { haptics.perform(HapticEvent.Reject); query = "" }) { Text("✕") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (query.isBlank()) {
            if (recent.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    recent.forEach { past ->
                        SuggestionChip(
                            onClick = { haptics.perform(HapticEvent.Tap); query = past },
                            label = { Text(past) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent searches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { haptics.perform(HapticEvent.Reject); onClearRecentSearches() }) {
                        Text("Clear recents")
                    }
                }
            }
            return@Column
        }

        if (results.isEmpty()) {
            Text(
                "No match for “${query.trim()}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            return@Column
        }

        Text(
            "${results.size} setting${if (results.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        // Bounded and independently scrollable so it can nest without fighting the outer scroll.
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = RESULTS_MAX_HEIGHT)
                .verticalScrollbar(listState),
        ) {
            groupByScreen(results).forEach { (screen, knobs) ->
                item(key = "hdr-${screen.name}") {
                    Text(
                        "Settings › ${screen.breadcrumb}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(knobs, key = { it.id }) { knob ->
                    ResultRow(knob) {
                        onRecordSearch(query.trim())
                        onOpen(knob)
                    }
                }
            }
        }
    }
}

/** Screens in first-hit order, each carrying its own hits in rank order. */
private fun groupByScreen(results: List<SettingsKnob>): List<Pair<SettingsScreenKey, List<SettingsKnob>>> {
    val grouped = LinkedHashMap<SettingsScreenKey, MutableList<SettingsKnob>>()
    results.forEach { grouped.getOrPut(it.screen) { mutableListOf() }.add(it) }
    return grouped.map { (screen, knobs) -> screen to knobs.toList() }
}

@Composable
private fun ResultRow(knob: SettingsKnob, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hapticClickable(HapticEvent.Tap, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
    ) {
        Text(knob.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        val detail = listOf(knob.section, knob.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
            )
        }
    }
}
