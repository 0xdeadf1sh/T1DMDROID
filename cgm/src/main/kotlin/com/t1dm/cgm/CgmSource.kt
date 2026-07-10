package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single CGM source (SPEC.private.md §3.1). The AiDEX X impl is the only one built now, but
 * the seam is frozen for extensibility. Many sources may be recorded at once; exactly one is
 * active = authoritative (see [CgmSourceRegistry]), and inference runs only on it.
 *
 * [readings] emits CRC-validated, deduped, grid-stamped readings; the implementation does that
 * work on the Default dispatcher.
 */
interface CgmSource {
    val descriptor: CgmSourceDescriptor
    val status: StateFlow<CgmSourceStatus>
    fun readings(): Flow<CgmReading>
}

/**
 * A vendor adapter that recognises its own device from advert data and constructs a
 * [CgmSource]. Matching is by name / serial suffix, NEVER by BLE address (§3.1).
 */
interface CgmVendorPlugin {
    /** Stable vendor tag, e.g. "aidexx". */
    val vendorId: String

    /**
     * If this advert belongs to this vendor, return the stable [CgmSourceId] derived from its
     * name/serial suffix; otherwise `null`. `manufacturerId` is the BLE company id (0x0059 for
     * LinX); `manufacturerData` is the raw manufacturer-specific payload.
     */
    fun recognize(name: String?, manufacturerId: Int, manufacturerData: ByteArray): CgmSourceId?

    /** Build (or rebuild) the source object for a recognised id. */
    fun createSource(id: CgmSourceId): CgmSource
}

/**
 * The persisted set of known CGM sources and the single manually-chosen active one
 * (SPEC.private.md §3.1). Auto-discovery may add sources and set the first active; the user
 * can override. The active source is authoritative for inference and alarms.
 */
interface CgmSourceRegistry {
    /** Every recorded source descriptor. */
    val sources: StateFlow<List<CgmSourceDescriptor>>

    /** The active source id, or `null` before any source is adopted. */
    val active: StateFlow<CgmSourceId?>

    /** Make [id] the single active source (persisted). */
    fun setActive(id: CgmSourceId)

    /** The live [CgmSource] for the active id, or `null` if none is active. */
    fun activeSource(): CgmSource?
}
