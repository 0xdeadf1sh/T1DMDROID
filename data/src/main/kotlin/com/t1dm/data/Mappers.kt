package com.t1dm.data

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.JournalNote
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.NoteEntity

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
 * The `passiveOnly` flag is a vendor constant, not a persisted column (the AiDEX X impl is the
 * only Phase-1 source and is advertisement-only), so it is reconstructed as `true`.
 */
internal fun NoteEntity.toJournalNote(): JournalNote = JournalNote(
    tsMs = tsMs,
    tzOffsetMin = tzOffsetMin,
    text = text,
)

internal fun CgmSourceEntity.toDescriptor(): CgmSourceDescriptor = CgmSourceDescriptor(
    id = CgmSourceId(sourceId),
    vendorId = vendorId,
    displayName = displayName,
    serialSuffix = serialSuffix,
    warmupWindowMin = warmupWindowMin,
    passiveOnly = true,
)
