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

    /** Record (or refresh) a discovered source; [active] participates in the exactly-one-active
     *  invariant (§3.1) and must be reconciled by the implementation via a transaction. */
    suspend fun upsertSource(descriptor: CgmSourceDescriptor, active: Boolean, lastSeenMs: Long)

    /** Make [id] the single authoritative source (clear-all-then-set, in a transaction). */
    suspend fun setActive(id: CgmSourceId)

    /** Load the persisted sources so the registry can rehydrate on process start (§3.1); without
     *  this the first advert after a restart would seize `active`, overriding the chosen source. */
    suspend fun loadSources(): List<CgmSourceDescriptor>

    /** The persisted authoritative source id, or `null` if none has been chosen yet. */
    suspend fun activeSourceId(): CgmSourceId?

    /**
     * Retune one source's warm-up window (minutes) in place, leaving the rest of its row alone — the
     * window is per-source and persisted, so the user's choice outlives the process. The implementation
     * clamps to [CgmSourceDescriptor.WARMUP_WINDOW_RANGE].
     */
    suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int)

    /**
     * Persist one grid-stamped reading to `cgm_reading` (upsert on `(sourceId, tsMs)`) and project
     * its BG onto the wide `sample` row for `tsMs` (§3.5, LWW on `updatedAt`).
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
