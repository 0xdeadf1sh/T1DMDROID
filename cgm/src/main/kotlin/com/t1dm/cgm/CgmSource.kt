package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One CGM source (§3.1). Several may be read at once; exactly one is authoritative
 * ([CgmSourceRegistry]) and inference runs only on that one. [readings] emits CRC-validated,
 * deduped, grid-stamped readings, decoded on the Default dispatcher.
 */
interface CgmSource {
    val descriptor: CgmSourceDescriptor
    val status: StateFlow<CgmSourceStatus>
    fun readings(): Flow<CgmReading>
}

/** Matching is by name / serial suffix, NEVER by BLE address (§3.1). */
interface CgmVendorPlugin {
    /** Stable, e.g. "aidexx". */
    val vendorId: String

    /** Null unless the advert is this vendor's. `manufacturerId` is the BLE company id;
     *  `manufacturerData` the raw manufacturer-specific payload. */
    fun recognize(name: String?, manufacturerId: Int, manufacturerData: ByteArray): CgmSourceId?

    /** Seeded from vendor defaults. For a source already on record use the descriptor overload —
     *  the seed overwrites the tuned warm-up window. */
    fun createSource(id: CgmSourceId): CgmSource

    /** Adopts [descriptor] verbatim. */
    fun createSource(descriptor: CgmSourceDescriptor): CgmSource
}

/**
 * The known CGM sources, those being read, and the authoritative one (§3.1). [activeIds] is what the
 * BG panel may switch between; [authoritative] alone feeds inference, the statistics, the alarms and
 * the wire. Auto-discovery may set the FIRST authoritative source; after that it is the user's.
 */
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
