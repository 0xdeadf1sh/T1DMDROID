package com.t1dm.data

import com.t1dm.core.model.CgmRawSample
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.PaintStroke
import com.t1dm.data.db.CgmRawSampleEntity
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.PaintStrokeBlob
import com.t1dm.data.db.PaintStrokeEntity

internal fun CgmReadingEntity.toModel(): CgmReading = CgmReading(
    sourceId = CgmSourceId(sourceId),
    tsMs = tsMs,
    bgMgdl = bgMgdl,
    trendTenthsPerMin = trendTenthsPerMin,
    minFromStart = minFromStart,
    quality = quality,
    provenance = provenance,
    flag = flag,
    tzOffsetMin = tzOffsetMin,
    rxWallMs = rxWallMs,
    rssi = rssi,
)

internal fun CgmReading.toEntity(): CgmReadingEntity = CgmReadingEntity(
    sourceId = sourceId.value,
    tsMs = tsMs,
    bgMgdl = bgMgdl,
    trendTenthsPerMin = trendTenthsPerMin,
    minFromStart = minFromStart,
    quality = quality,
    provenance = provenance,
    flag = flag,
    tzOffsetMin = tzOffsetMin,
    rxWallMs = rxWallMs,
    rssi = rssi,
)

/** No `provenance`: caller keeps gap-fills out, all MEASURED; no `tsMs`, snapToGrid's job. */
internal fun CgmReading.toRawEntity(): CgmRawSampleEntity = CgmRawSampleEntity(
    sourceId = sourceId.value,
    rxWallMs = rxWallMs,
    bgMgdl = bgMgdl,
    trendTenthsPerMin = trendTenthsPerMin,
    minFromStart = minFromStart,
    quality = quality,
    flag = flag,
    tzOffsetMin = tzOffsetMin,
    rssi = rssi,
)

internal fun CgmRawSampleEntity.toModel(): CgmRawSample = CgmRawSample(
    sourceId = CgmSourceId(sourceId),
    rxWallMs = rxWallMs,
    bgMgdl = bgMgdl,
    trendTenthsPerMin = trendTenthsPerMin,
    minFromStart = minFromStart,
    quality = quality,
    flag = flag,
    tzOffsetMin = tzOffsetMin,
    rssi = rssi,
)

internal fun CgmSourceEntity.toDescriptor(): CgmSourceDescriptor = CgmSourceDescriptor(
    id = CgmSourceId(sourceId),
    vendorId = vendorId,
    sensorModelId = sensorModelId,
    advertName = advertName,
    displayName = displayName,
    serialSuffix = serialSuffix,
    warmupWindowMin = warmupWindowMin,
    // A vendor constant, not a persisted column; informational only, nothing branches on it.
    passiveOnly = false,
    hidden = hidden,
    ordinal = ordinal,
)

internal fun PaintStrokeEntity.toModel(): PaintStroke {
    val p = PaintStrokeBlob.decode(points)
    return PaintStroke(
        id = id,
        createdAtMs = createdAtMs,
        tool = tool,
        colorArgb = colorArgb,
        widthDp = widthDp,
        tsMs = p.tsMs,
        yFrac = p.yFrac,
    )
}

/** A stroke may double back in X, so the time bounds are scanned, not read off the ends. */
internal fun PaintStroke.toEntity(): PaintStrokeEntity = PaintStrokeEntity(
    id = id,
    createdAtMs = createdAtMs,
    tool = tool,
    colorArgb = colorArgb,
    widthDp = widthDp,
    minTsMs = tsMs.min(),
    maxTsMs = tsMs.max(),
    points = PaintStrokeBlob.encode(tsMs, yFrac),
)
