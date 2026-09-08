package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/** Persistence seam in domain types (not Room entities), so pipeline doesn't couple to storage. */
interface CgmRepository {

    /** [authoritative]: exactly-one invariant (§3.1); returns [ordinal] to copy back. */
    suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int

    /** Clear-all-then-set, transactional; promoted source active, the one it replaces stays too. */
    suspend fun setAuthoritative(id: CgmSourceId)

    /** Additive: nothing else stops. */
    suspend fun activate(id: CgmSourceId)

    /** Refuses the AUTHORITATIVE source; callers rely on that, no need to re-check. */
    suspend fun deactivate(id: CgmSourceId)

    suspend fun activeSourceIds(): List<CgmSourceId>

    /** §3.1. */
    suspend fun loadSources(): List<CgmSourceDescriptor>

    /** Null before any source has been chosen. */
    suspend fun authoritativeSourceId(): CgmSourceId?

    /** Minutes, in place; clamped to [CgmSourceDescriptor.WARMUP_WINDOW_RANGE]. */
    suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int)

    /** A display flag, not a delete (history stays); stops reading too. Refuses AUTHORITATIVE. */
    suspend fun hide(id: CgmSourceId)

    /** Upsert on (sourceId, tsMs); only authoritative source projects onto sample row (§3.5). */
    suspend fun upsertReading(reading: CgmReading)

    /** Null when unheld; opaque (plugin-owned). Sealed, unrecoverable — loss retires a sensor. */
    suspend fun loadSensorSecret(id: CgmSourceId): ByteArray?

    suspend fun saveSensorSecret(id: CgmSourceId, blob: ByteArray)

    /** 0 when unstored; outside the sealed secret — losing it costs one catch-up pull only. */
    suspend fun loadSourceCursor(id: CgmSourceId): Int

    suspend fun saveSourceCursor(id: CgmSourceId, cursor: Int)

    /** Destructive, unrecoverable; for a retired sensor only, else plugin refuses re-establish. */
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
