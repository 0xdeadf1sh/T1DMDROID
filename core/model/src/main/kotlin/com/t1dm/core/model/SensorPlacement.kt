package com.t1dm.core.model

/** A sensor on the twin's skin: barycentric ([u], [v]) in [tri] of the mesh hashed [meshHash]. */
data class SensorPlacement(
    val sensorId: String,
    val meshHash: Int,
    val tri: Int,
    val u: Float,
    val v: Float,
)
