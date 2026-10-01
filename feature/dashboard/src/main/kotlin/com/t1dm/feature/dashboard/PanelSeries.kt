package com.t1dm.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.t1dm.core.model.UnitSpace
import com.t1dm.ui.graph.GraphFrame
import com.t1dm.ui.graph.HindsightFrame
import com.t1dm.ui.graph.PredSeries
import com.t1dm.ui.graph.RolledSeries
import com.t1dm.ui.graph.SmoothedMgdl
import com.t1dm.ui.graph.SmoothedTrace

/** Every unit-bearing series on the panel, built in [unit] and swapped in together. */
internal class PanelSeries(
    /** Null until the first build lands. */
    val unit: UnitSpace?,
    val frame: GraphFrame,
    val rolled: RolledSeries?,
    val overlay: List<PredSeries>,
    val smoothed: SmoothedTrace?,
    val hindsight: HindsightFrame?,
) {
    companion object {
        val EMPTY = PanelSeries(null, GraphFrame.EMPTY, null, emptyList(), null, null)
    }
}

/** Main-thread only: read and written by the panel job, which hops off-thread inside builds. */
internal class PanelMemo {
    val frame = Memo<GraphFrame>()
    val rolled = Memo<RolledSeries?>()
    val overlay = Memo<List<PredSeries>>()
    val smoothedMgdl = Memo<SmoothedMgdl>()
    val smoothed = Memo<SmoothedTrace>()
    val hindsight = Memo<HindsightFrame?>()
}

/** [key] while [current], else the last key seen while it was; a plain field, no recomposition. */
@Composable
internal fun heldWhile(current: Boolean, key: Any?): Any? {
    val held = remember { arrayOf(key) }
    if (current) held[0] = key
    return held[0]
}

/** The last build, reused while its inputs compare equal; a cancelled build stores nothing. */
internal class Memo<T> {
    private var inputs: List<Any?>? = null
    private var value: T? = null

    suspend fun get(vararg key: Any?, build: suspend () -> T): T {
        val k = key.asList()
        @Suppress("UNCHECKED_CAST")
        if (k == inputs) return value as T
        val v = build()
        inputs = k
        value = v
        return v
    }
}
