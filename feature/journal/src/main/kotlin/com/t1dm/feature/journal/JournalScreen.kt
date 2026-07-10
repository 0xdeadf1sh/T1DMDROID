package com.t1dm.feature.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.t1dm.core.model.JournalNote
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The Phase-4 journal surface (deliverable 2 — the locked "mood + free-text journal
 * now" scope): a free-text composer that writes the local `note` table + enqueues a `NOTE` outbox
 * push (`POST /v1/notes`), and a mood picker that folds into `sample.mood` (`PUT /v1/series/mood`).
 *
 * A pure, stateless composable in the Phase-1 [com.t1dm.feature.dashboard.DashboardScreen] mould: it
 * renders the observed [notes] + [currentMood] and calls back into `:app` (which owns the Room /
 * `:sync` wiring) via [onSaveNote] / [onPickMood]. Feature modules stay dependency-light — no
 * `:data`, no `:sync`.
 */
@Composable
fun JournalScreen(
    notes: List<JournalNote> = emptyList(),
    currentMood: Int? = null,
    onSaveNote: (String) -> Unit = {},
    onPickMood: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        MoodPicker(currentMood, onPickMood)
        NoteComposer(onSaveNote)
        Text(
            "Journal",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        if (notes.isEmpty()) {
            Text(
                "No notes yet — jot how you feel, what you ate, anything worth remembering.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(notes, key = { it.tsMs }) { NoteRow(it) }
            }
        }
    }
}

/** Mood 1..5 (worst→best); the value is the integer written to `sample.mood`. */
private val MOODS = listOf(1 to "😞", 2 to "🙁", 3 to "😐", 4 to "🙂", 5 to "😀")

@Composable
private fun MoodPicker(current: Int?, onPick: (Int) -> Unit) {
    Text("Mood", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MOODS.forEach { (value, glyph) ->
            FilterChip(
                selected = current == value,
                onClick = { onPick(value) },
                label = { Text(glyph, style = MaterialTheme.typography.titleLarge) },
                shape = CircleShape,
            )
        }
    }
}

@Composable
private fun NoteComposer(onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What's on your mind?") },
            minLines = 3,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { if (text.isNotBlank()) { onSave(text.trim()); text = "" } },
                enabled = text.isNotBlank(),
            ) { Text("Save note") }
        }
    }
}

@Composable
private fun NoteRow(note: JournalNote) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                formatTs(note.tsMs, note.tzOffsetMin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                note.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · HH:mm")

private fun formatTs(ms: Long, tzOffsetMin: Int): String =
    Instant.ofEpochMilli(ms).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60)).format(TS_FMT)
