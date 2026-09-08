package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One CGM source (§3.1); several may be read, exactly one authoritative feeds inference. */
interface CgmSource {
    val descriptor: CgmSourceDescriptor
    val status: StateFlow<CgmSourceStatus>
    fun readings(): Flow<CgmReading>
}

/** Matching is by name / serial suffix, NEVER by BLE address (§3.1). */
interface CgmVendorPlugin {
    /** Stable, e.g. "aidexx". */
    val vendorId: String

    /** Null unless this vendor's; manufacturerId = BLE company id, manufacturerData = payload. */
    fun recognize(name: String?, manufacturerId: Int, manufacturerData: ByteArray): CgmSourceId?

    /** Seeded from vendor defaults; a known source should use the descriptor overload instead. */
    fun createSource(id: CgmSourceId): CgmSource

    /** Adopts [descriptor] verbatim. */
    fun createSource(descriptor: CgmSourceDescriptor): CgmSource
}

/** Known/active/authoritative sources (§3.1); [authoritative] alone feeds inference/stats/wire. */
interface CgmSourceRegistry {
    val sources: StateFlow<List<CgmSourceDescriptor>>

    /** Empty before any source is adopted. */
    val activeIds: StateFlow<Set<CgmSourceId>>

    /** Null before any source is adopted. Always in [activeIds]. */
    val authoritative: StateFlow<CgmSourceId?>

    /** Persisted. Activates [id] if it was not; the source it replaces keeps being read. */
    fun setAuthoritative(id: CgmSourceId)

    /** Persisted. Additive — nothing else stops. */
    fun activate(id: CgmSourceId)

    /** Persisted. Refuses the authoritative source. */
    fun deactivate(id: CgmSourceId)

    /** Null if none is live. */
    fun authoritativeSource(): CgmSource?

    /** Null if [id] is not live. */
    fun liveSource(id: CgmSourceId): CgmSource?
}
