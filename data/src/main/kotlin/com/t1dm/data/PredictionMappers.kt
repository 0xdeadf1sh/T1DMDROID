package com.t1dm.data

import com.t1dm.core.model.ModelPrediction
import com.t1dm.data.db.PredictionEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList

/**
 * [ModelPrediction] ⇄ [PredictionEntity] (Phase 3). The one non-trivial step is the fan layout:
 * a [ModelPrediction.bandsMgdl] is **step-major, τ-minor** (`i = s·nQ + q`), whereas the server —
 * and therefore [PredictionEntity.fanBlob] — is **quantile-major** (`fan[q·H + s]`, one row per
 * quantile in ascending-τ order `[0.05,0.1,0.25,0.5,0.75,0.9,0.95]`, row 3 == the median line).
 * Storing wire-order means a `PREDICTIONS` push is a straight reshape, and this transpose is the
 * single place the two conventions meet. The round-trip is exact (see `PredictionMappingTest`).
 */

internal fun ModelPrediction.toEntity(nowMs: Long, todConf: Double? = null): PredictionEntity {
    val h = medianBg.size
    val nq = nQuantiles
    require(bandsMgdl.size == h * nq) { "bands ${bandsMgdl.size} != H·nQ = ${h * nq}" }
    val fanQMajor = DoubleArray(h * nq)
    for (s in 0 until h) {
        for (q in 0 until nq) {
            fanQMajor[q * h + s] = bandsMgdl[s * nq + q]
        }
    }
    return PredictionEntity(
        madeAtMs = cycleTsMs,
        modelId = modelId,
        horizonSteps = h,
        nQuantiles = nq,
        stepMs = stepMs,
        anchorTsMs = anchorTsMs,
        lastBg = lastBg,
        lineBlob = medianBg.toBlob(),
        fanBlob = fanQMajor.toBlob(),
        todBlob = null,
        todConf = todConf,
        status = status,
        backend = backend,
        precision = precision,
        selected = selected,
        stale = stale,
        latencyMs = latencyMs,
        createdAtMs = nowMs,
    )
}

internal fun PredictionEntity.toModel(): ModelPrediction {
    val h = horizonSteps
    val nq = nQuantiles
    val fanQMajor = fanBlob.toDoubleList()
    val bands = DoubleArray(h * nq)
    for (s in 0 until h) {
        for (q in 0 until nq) {
            bands[s * nq + q] = fanQMajor[q * h + s]
        }
    }
    return ModelPrediction(
        modelId = modelId,
        cycleTsMs = madeAtMs,
        anchorTsMs = anchorTsMs,
        stepMs = stepMs,
        medianBg = lineBlob.toDoubleList(),
        bandsMgdl = bands.asList(),
        nQuantiles = nq,
        lastBg = lastBg,
        status = status,
        backend = backend,
        precision = precision,
        selected = selected,
        stale = stale,
        latencyMs = latencyMs,
    )
}
