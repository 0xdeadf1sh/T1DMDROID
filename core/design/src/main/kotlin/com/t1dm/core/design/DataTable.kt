package com.t1dm.core.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared tabular primitives (issues 10 / 11 / 15). Every "table" in the app used to be a
 * `Row(SpaceBetween)` of two unweighted [Text]s: when the label grew, the value inherited a
 * one-glyph column and Compose broke a bare number like "54" into "5" / "4" (issue 11). These
 * components give each column a *fixed weight* so the split is stable, right-align numerics in
 * tabular monospace figures, and forbid mid-token wrapping on numeric cells.
 *
 * Two entry points:
 *  - [KeyValueRow] / [KeyValueTable] — the label ↔ value pair (Hardware, About, Model detail,
 *    Security, GPU/Vulkan…).
 *  - [DataTable] — a header + N-column grid (Model-detail reference metrics, stats episodes). Wide
 *    tables scroll horizontally rather than crushing their columns.
 */

/** Tabular (fixed-advance) figures — a "54" occupies the same width as "10", so columns line up. */
private val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun KeyValueRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    /** true ⇒ the value is a single-token number/measurement: forbid all wrapping so it can never
     *  fracture (issue 11). false ⇒ free text (a renderer name, a package id) may wrap at word
     *  boundaries. Either way the value keeps a stable, generous column, so a bare "54" never collapses
     *  into a one-glyph box the way the old `Row(SpaceBetween)` let it. */
    numeric: Boolean = false,
    labelStyle: TextStyle = MaterialTheme.typography.bodySmall,
    valueStyle: TextStyle = MaterialTheme.typography.bodySmall,
    labelWeight: Float = 1f,
    valueWeight: Float = 1.2f,
    emphasizeValue: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(labelWeight).padding(end = 12.dp),
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value ?: "n/a",
            modifier = Modifier.weight(valueWeight),
            style = valueStyle.merge(TabularFigures),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (emphasizeValue) FontWeight.Bold else null,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            // Numerics are single tokens — forbid wrapping so a "54" can never split; a value wider
            // than its column clips rather than fracturing. Free text may wrap at word boundaries.
            softWrap = !numeric,
            overflow = if (numeric) TextOverflow.Clip else TextOverflow.Visible,
        )
    }
}

@Composable
fun KeyValueTable(
    rows: List<Pair<String, String?>>,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        rows.forEach { (k, v) -> KeyValueRow(k, v, numeric = numeric) }
    }
}

/** A column spec for [DataTable]. [numeric] right-aligns + monospaces + forbids mid-token wrap. */
data class TableColumn(
    val header: String,
    val weight: Float = 1f,
    val numeric: Boolean = false,
)

@Composable
fun DataTable(
    columns: List<TableColumn>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    /** Below this width the whole grid scrolls sideways instead of crushing columns. */
    minWidth: Int = 320,
) {
    Column(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        val gridWidth = Modifier.widthIn(min = minWidth.dp)
        TableLine(gridWidth, columns, columns.map { it.header }, header = true)
        rows.forEach { r -> TableLine(gridWidth, columns, r, header = false) }
    }
}

@Composable
private fun TableLine(
    widthModifier: Modifier,
    columns: List<TableColumn>,
    cells: List<String>,
    header: Boolean,
) {
    Row(widthModifier.padding(vertical = 2.dp)) {
        columns.forEachIndexed { i, col ->
            val cell = cells.getOrElse(i) { "" }
            val alignEnd = col.numeric && i > 0
            Text(
                cell,
                modifier = Modifier.weight(col.weight).padding(end = 8.dp),
                style = (if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall)
                    .merge(TabularFigures),
                fontFamily = if (col.numeric || header) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (header) FontWeight.Bold else null,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                softWrap = !col.numeric,
                overflow = if (col.numeric) TextOverflow.Clip else TextOverflow.Visible,
                maxLines = if (col.numeric) 1 else Int.MAX_VALUE,
            )
        }
    }
}
