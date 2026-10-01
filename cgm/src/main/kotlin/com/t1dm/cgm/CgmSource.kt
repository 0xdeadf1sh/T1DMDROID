package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A single CGM source (§3.1); several may be READ, exactly one authoritative feeds inference. */
interface CgmSource {
    val descriptor: CgmSourceDescriptor
    val status: StateFlow<CgmSourceStatus>
    fun readings(): Flow<CgmReading>
}

/** The known sources, the set being READ, and the single authoritative one among them (§3.1). */
interface CgmSourceRegistry {
    val sources: StateFlow<List<CgmSourceDescriptor>>

    val activeIds: StateFlow<Set<CgmSourceId>>

    /** `null` before any source is adopted. Always in [activeIds]. */
    val authoritative: StateFlow<CgmSourceId?>

    /** Activates [id] if it was not; the source it replaces keeps being read. */
    fun setAuthoritative(id: CgmSourceId)

    /** Additive — nothing else stops. */
    fun activate(id: CgmSourceId)

    /** Refuses the authoritative source. */
    fun deactivate(id: CgmSourceId)

    fun authoritativeSource(): CgmSource?

    fun liveSource(id: CgmSourceId): CgmSource?
}
