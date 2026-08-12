package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/**
 * The persistence seam the CGM pipeline writes through (Phase 1 — "persist through
 * the :data Repository"). Declared here in domain types (`:core:model`) rather than Room entities
 * so the pipeline never couples to storage; the Room-backed implementation belongs to the Data
 * implementer (`@Database` + migration runner are theirs to add) and maps these calls onto
 * `CgmSourceDao` / `CgmReadingDao` / `CgmAdvertRawDao` and the wide `sample` projection.
 *
 * All methods are `suspend`; callers dispatch on IO.
 */
interface CgmRepository {

    /** Record (or refresh) a discovered source; [authoritative] participates in the
     *  exactly-one-authoritative invariant (§3.1) and must be reconciled by the implementation via a
     *  transaction. Whether the app is READING the source is not settled here — it is the user's
     *  standing decision, preserved from storage across a re-sighting. */
    suspend fun upsertSource(descriptor: CgmSourceDescriptor, authoritative: Boolean, lastSeenMs: Long)

    /** Make [id] the single authoritative source (clear-all-then-set, in a transaction). The promoted
     *  source becomes active if it was not; the one it replaces STAYS active, so a promotion moves
     *  what is believed without disturbing what is being read. */
    suspend fun setAuthoritative(id: CgmSourceId)

    /** Start reading [id]. Additive: nothing else stops. */
    suspend fun activate(id: CgmSourceId)

    /** Stop reading [id]. The implementation refuses the AUTHORITATIVE source, which is the invariant
     *  the caller relies on rather than re-checks. */
    suspend fun deactivate(id: CgmSourceId)

    /** The sources the app is reading, so the coordinator can rehydrate its session set on process
     *  start rather than re-deriving it from whatever advertises first. */
    suspend fun activeSourceIds(): List<CgmSourceId>

    /** Load the persisted sources so the registry can rehydrate on process start (§3.1); without
     *  this the first advert after a restart would seize authority, overriding the chosen source. */
    suspend fun loadSources(): List<CgmSourceDescriptor>

    /** The persisted authoritative source id, or `null` if none has been chosen yet. */
    suspend fun authoritativeSourceId(): CgmSourceId?

    /**
     * Retune one source's warm-up window (minutes) in place, leaving the rest of its row alone — the
     * window is per-source and persisted, so the user's choice outlives the process. The implementation
     * clamps to [CgmSourceDescriptor.WARMUP_WINDOW_RANGE].
     */
    suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int)

    /**
     * Take a retired sensor off the sensor lists — a display flag on the row, never a delete: the
     * source stays on record so its readings stay in the history the BG panel draws. Stops reading it
     * too. The implementation refuses the AUTHORITATIVE source, which is the invariant the caller
     * relies on rather than re-checks.
     */
    suspend fun hide(id: CgmSourceId)

    /**
     * Persist one grid-stamped reading to `cgm_reading` (upsert on `(sourceId, tsMs)`) and, ONLY for
     * the authoritative source, project its BG onto the wide `sample` row for `tsMs` (§3.5, LWW on
     * `updatedAt`). Another active source's readings are stored and drawn and reach nothing else.
     */
    suspend fun upsertReading(reading: CgmReading)

    /**
     * Persist a raw captured advert for forensics / replay (`cgm_advert_raw`), including
     * CRC-failing frames so decode regressions are diagnosable off-device.
     */
    suspend fun insertRawAdvert(
        sourceId: CgmSourceId?,
        rxWallMs: Long,
        rssi: Int?,
        payload: ByteArray,
        crcValid: Boolean,
        minFromStart: Int?,
    )
}
