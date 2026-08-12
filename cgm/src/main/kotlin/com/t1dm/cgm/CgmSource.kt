package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single CGM source (§3.1). The AiDEX X impl is the only one built now, but
 * the seam is frozen for extensibility. Several sources may be READ at once; exactly one of them is
 * authoritative (see [CgmSourceRegistry]), and inference runs only on that one.
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

    /** Build (or rebuild) the source object for a recognised id, seeded from this vendor's own
     *  defaults. For a source already on record use [createSource] with its persisted descriptor —
     *  the seed would otherwise overwrite the warm-up window the user tuned. */
    fun createSource(id: CgmSourceId): CgmSource

    /** Build the source object for a descriptor already on record, adopting it verbatim. */
    fun createSource(descriptor: CgmSourceDescriptor): CgmSource
}

/**
 * The persisted set of known CGM sources, the set the app is currently READING, and the single
 * authoritative one among them (§3.1).
 *
 * Two distinct decisions, and keeping them apart is the whole of this interface:
 *
 *  - [activeIds] — the app holds these sensors open and stores their readings. The BG panel may be
 *    switched between them. Auto-discovery may add to this set.
 *  - [authoritative] — the one source that feeds inference, the statistics, the alarm engine and the
 *    wire. Always a member of [activeIds]. Auto-discovery may set the FIRST one; after that it is
 *    the user's, and nothing takes it from them implicitly.
 *
 * Widening what is read never widens what is believed.
 */
interface CgmSourceRegistry {
    /** Every recorded source descriptor. */
    val sources: StateFlow<List<CgmSourceDescriptor>>

    /** The sources being read right now. Empty before any source is adopted. */
    val activeIds: StateFlow<Set<CgmSourceId>>

    /** The authoritative source id, or `null` before any source is adopted. Always in [activeIds]. */
    val authoritative: StateFlow<CgmSourceId?>

    /** Make [id] the single authoritative source (persisted). Activates it if it was not active; the
     *  source it replaces keeps being read. */
    fun setAuthoritative(id: CgmSourceId)

    /** Begin reading [id] (persisted). Additive — nothing else stops. */
    fun activate(id: CgmSourceId)

    /** Stop reading [id] (persisted). Refuses the authoritative source. */
    fun deactivate(id: CgmSourceId)

    /** The live [CgmSource] for the authoritative id, or `null` if none is live. */
    fun authoritativeSource(): CgmSource?

    /** The live [CgmSource] for [id], or `null` if that source is not currently live. */
    fun liveSource(id: CgmSourceId): CgmSource?
}
