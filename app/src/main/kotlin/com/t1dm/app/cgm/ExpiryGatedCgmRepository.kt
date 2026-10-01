package com.t1dm.app.cgm

import com.t1dm.cgm.CgmRepository
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId

/** True once sensor age reaches stated/rated life; false when the life is unknown (fails open). */
fun isPastExpiry(reading: CgmReading, lifetimeMin: Int?): Boolean {
    val age = reading.minFromStart ?: return false
    val life = lifetimeMin ?: return false
    return age >= life
}

/** main only: drops a past-expiry reading before storage, so no expired BG is stored or shown. */
class ExpiryGatedCgmRepository(
    private val delegate: CgmRepository,
    private val lifetimeMinOf: suspend (CgmSourceId) -> Int?,
) : CgmRepository by delegate {
    override suspend fun upsertReading(reading: CgmReading) {
        if (isPastExpiry(reading, lifetimeMinOf(reading.sourceId))) return
        delegate.upsertReading(reading)
    }
}
