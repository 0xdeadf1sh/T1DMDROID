package com.t1dm.data

import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.PredictedTime
import com.t1dm.data.db.PredictionEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.toDoubleList

/** bandsMgdl is step-major tau-minor (s*nQ+q); fanBlob is quantile-major (q*H+s); the transpose. */

internal fun ModelPrediction.toEntity(nowMs: Long): PredictionEntity {
    val h = medianBg.size
    val nq = nQuantiles
    require(bandsMgdl.size == h * nq) { "bands ${bandsMgdl.size} != H·nQ = ${h * nq}" }
    val fanQMajor = DoubleArray(h * nq)
    for (s in 0 until h) {
        for (q in 0 until nq) {
            fanQMajor[q * h + s] = bandsMgdl[s * nq + q]
        }
    }
    val pt = predictedTime
    val todBlob = pt?.let { (listOf(it.predictedHour, it.binHours) + it.probs).toBlob() }
    return PredictionEntity(
        madeAtMs = cycleTsMs,
        modelId = modelId,
        horizonSteps = h,
        nQuantiles = nq,
        stepMs = stepMs,
        anchorTsMs = anchorTsMs,
        sourceId = sourceId,
        lastBg = lastBg,
        lineBlob = medianBg.toBlob(),
        fanBlob = fanQMajor.toBlob(),
        todBlob = todBlob,
        todConf = pt?.resultantR,
        status = status,
        backend = backend,
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
    val predictedTime: PredictedTime? = todBlob?.toDoubleList()?.takeIf { it.size >= 3 }?.let { packed ->
        val probs = packed.subList(2, packed.size)
        PredictedTime(
            probs = probs,
            predictedHour = packed[0],
            resultantR = todConf ?: 0.0,
            nBins = probs.size,
            binHours = packed[1],
        )
    }
    return ModelPrediction(
        modelId = modelId,
        cycleTsMs = madeAtMs,
        anchorTsMs = anchorTsMs,
        sourceId = sourceId,
        stepMs = stepMs,
        medianBg = lineBlob.toDoubleList(),
        bandsMgdl = bands.asList(),
        nQuantiles = nq,
        lastBg = lastBg,
        status = status,
        backend = backend,
        selected = selected,
        stale = stale,
        latencyMs = latencyMs,
        predictedTime = predictedTime,
    )
}
