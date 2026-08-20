package com.t1dm.data

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId

/**
 * The model class a CGM source belongs to when nothing recorded one (§3.1).
 *
 * Two readers arrive at a source row that predates the `sensorModelId` column: the schema migration, and
 * an archive restored from a file written before the column existed. **They must agree.** If they
 * did not, the same physical sensor would land in one class by upgrading in place and a different
 * one by restoring a backup, and its history would silently split in two on the BG panel.
 *
 * The migration cannot call this — a migration describes what the schema became at a fixed point and
 * must never read a constant a later edit could move underneath it — so it repeats the answer as
 * frozen SQL literals and `MigrationConstantsTest` holds the two together.
 *
 * The answer is exact rather than a guess, and stays exact as more vendor plugins ship. It is only ever
 * asked about a row written BEFORE the `sensorModelId` column existed, and at that point the AiDEX X
 * plugin was the only one the app had — so every such row is one of its sensors, save the synthetic debug
 * one. A source recorded by a later plugin carries its own model id and never reaches here.
 */
internal fun legacySensorModelIdFor(sourceId: String): String =
    if (sourceId == CgmSourceId.DEBUG.value) CgmSensorModelId.AIDEX_DEBUG else CgmSensorModelId.AIDEX_X
