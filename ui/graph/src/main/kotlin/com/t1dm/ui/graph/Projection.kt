package com.t1dm.ui.graph

/** fun interfaces over primitives, not function types: Function1 boxes both projected ends. */

/** Absolute epoch-ms → x pixel. */
internal fun interface AbsToPx {
    fun of(tsMs: Double): Float
}

/** A value in the plot's y unit → y pixel. */
internal fun interface ValToPx {
    fun of(v: Float): Float
}

