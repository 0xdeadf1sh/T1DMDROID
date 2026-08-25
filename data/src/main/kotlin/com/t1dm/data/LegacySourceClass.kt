package com.t1dm.data

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId

/**
 * The model class of a source row written before the `sensorModelId` column existed (§3.1). Every
 * such row is AiDEX X — that plugin was the only one the app had. The migration repeats the answer
 * as frozen SQL literals rather than calling this; `MigrationConstantsTest` holds the two together.
 */
internal fun legacySensorModelIdFor(sourceId: String): String =
    if (sourceId == CgmSourceId.DEBUG.value) CgmSensorModelId.AIDEX_DEBUG else CgmSensorModelId.AIDEX_X
