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

/** Fixed-advance figures, so columns line up. */
private val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun KeyValueRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    /** true ⇒ a single-token number: never wraps, so it cannot fracture mid-token. */
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
    /** Below this the grid scrolls sideways rather than crushing columns. */
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
