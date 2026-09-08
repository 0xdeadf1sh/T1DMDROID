package com.t1dm.data

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId

/** Model class for rows predating sensorModelId (§3.1): all AiDEX X; migration freezes in SQL. */
internal fun legacySensorModelIdFor(sourceId: String): String =
    if (sourceId == CgmSourceId.DEBUG.value) CgmSensorModelId.AIDEX_DEBUG else CgmSensorModelId.AIDEX_X
