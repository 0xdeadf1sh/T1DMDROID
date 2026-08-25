package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/**
 * The persistence seam the CGM pipeline writes through, in domain types rather than Room entities so
 * the pipeline never couples to storage. All methods are `suspend`; callers dispatch on IO.
 */
interface CgmRepository {

    /**
     * [authoritative] participates in the exactly-one-authoritative invariant (§3.1), reconciled in a
     * transaction. Whether the app is reading the source is not settled here. Answers the
     * [CgmSourceDescriptor.ordinal] on record; the caller must carry it back into its own copy.
     */
    suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int

    /** Clear-all-then-set, in a transaction. The promoted source becomes active; the one it replaces
     *  stays active. */
    suspend fun setAuthoritative(id: CgmSourceId)

    /** Additive: nothing else stops. */
    suspend fun activate(id: CgmSourceId)

    /** The implementation refuses the AUTHORITATIVE source; callers rely on that rather than re-check. */
    suspend fun deactivate(id: CgmSourceId)

    suspend fun activeSourceIds(): List<CgmSourceId>

    /** §3.1. */
    suspend fun loadSources(): List<CgmSourceDescriptor>

    /** Null before any source has been chosen. */
    suspend fun authoritativeSourceId(): CgmSourceId?

    /** Minutes, in place, leaving the rest of the row alone. The implementation clamps to
     *  [CgmSourceDescriptor.WARMUP_WINDOW_RANGE]. */
    suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int)

    /** A display flag, never a delete: the readings stay in the history. Stops reading it too. The
     *  implementation refuses the AUTHORITATIVE source. */
    suspend fun hide(id: CgmSourceId)

    /**
     * Upsert on `(sourceId, tsMs)`. ONLY the authoritative source projects onto the wide `sample` row
     * (§3.5); another active source's readings reach nothing else. Which of a slot's samples the
     * projection carries is the storage layer's slot contest, not the `sample` row's `updatedAt`.
     */
    suspend fun upsertReading(reading: CgmReading)

    /**
     * Null when none is held. Opaque: the meaning, and its versioning, belong to the plugin that wrote
     * it. Sealed at rest and unrecoverable — losing it retires a sensor still on the patient's arm.
     */
    suspend fun loadSensorSecret(id: CgmSourceId): ByteArray?

    suspend fun saveSensorSecret(id: CgmSourceId, blob: ByteArray)

    /**
     * `0` when none stored. Deliberately NOT part of the sealed secret: losing this costs one catch-up
     * pull, where that blob's strict version check could strand a live wear.
     */
    suspend fun loadSourceCursor(id: CgmSourceId): Int

    suspend fun saveSourceCursor(id: CgmSourceId, cursor: Int)

    /**
     * Destructive and unrecoverable — for a deliberately retired sensor, never a global reset. A plugin
     * calls it alone only for state the sensor itself discarded: a stored secret makes the plugin
     * refuse to re-establish one.
     */
    suspend fun clearSensorSecret(id: CgmSourceId)

    /** Includes CRC-failing frames, so decode regressions are diagnosable off-device. */
    suspend fun insertRawAdvert(
        sourceId: CgmSourceId?,
        rxWallMs: Long,
        rssi: Int?,
        payload: ByteArray,
        crcValid: Boolean,
        minFromStart: Int?,
    )
}
