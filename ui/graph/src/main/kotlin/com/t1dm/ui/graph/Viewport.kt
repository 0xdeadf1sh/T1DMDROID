package com.t1dm.ui.graph

/** Visible window, held in ABSOLUTE time, moved only when necessary. Returns clamped start/span. */
fun clampViewport(
    startMs: Double,
    spanMs: Double,
    domainStartMs: Double,
    panEndMs: Double,
    minSpanMs: Double,
    maxSpanMs: Double,
): Pair<Double, Double> {
    val span = spanMs.coerceIn(minSpanMs, maxSpanMs)
    val range = panEndMs - domainStartMs
    // Wider than the record: any start showing it all is kept, wherever it lies in that range.
    val start = if (span >= range) {
        startMs.coerceIn(panEndMs - span, domainStartMs)
    } else {
        startMs.coerceIn(domainStartMs, panEndMs - span)
    }
    return start to span
}
