package com.t1dm.data

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.PaintStroke
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.PaintStrokeBlob
import com.t1dm.data.db.PaintStrokeEntity

/** Entity ⇄ domain mappings kept out of the DAOs so Room only ever sees flat rows. */

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

/**
 * The `passiveOnly` flag is a vendor constant, not a persisted column (the AiDEX X impl is the only
 * source and now uses a CONNECTED GATT session as the sole read path), so it is reconstructed as
 * `false`. It is informational only — nothing branches on it.
 */
internal fun CgmSourceEntity.toDescriptor(): CgmSourceDescriptor = CgmSourceDescriptor(
    id = CgmSourceId(sourceId),
    vendorId = vendorId,
    displayName = displayName,
    serialSuffix = serialSuffix,
    warmupWindowMin = warmupWindowMin,
    // The AiDEX X impl is now the CONNECTED (GATT) read path, not passive advertisement. The flag is a
    // vendor constant (not a persisted column) and is informational only — nothing branches on it.
    passiveOnly = false,
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

/**
 * The time bounds are scanned, not read off the ends: a freehand stroke may double back in X (drag
 * left, then right), so `tsMs.first()`/`last()` are not its extremes and an index built on them would
 * cull a stroke that is on screen. Empty strokes have no bounds at all and are refused upstream by
 * [T1dmRepository.addPaintStroke].
 */
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
