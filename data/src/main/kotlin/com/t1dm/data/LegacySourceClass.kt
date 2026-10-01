package com.t1dm.data

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId

/** Pre-§3.1 rows are all AiDEX X; frozen in migration SQL too, see MigrationConstantsTest. */
internal fun legacySensorModelIdFor(sourceId: String): String =
    if (sourceId == CgmSourceId.DEBUG.value) CgmSensorModelId.AIDEX_DEBUG else CgmSensorModelId.AIDEX_X
