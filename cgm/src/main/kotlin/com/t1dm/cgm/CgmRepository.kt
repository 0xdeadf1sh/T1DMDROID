package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId

/** All methods are `suspend`; callers dispatch on IO. */
interface CgmRepository {

    /** [descriptor]'s ordinal as recorded; minted in the write transaction, no collision. */
    suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int

    /** Activates [id] if it was not; the source it replaces keeps being read. */
    suspend fun setAuthoritative(id: CgmSourceId)

    /** Additive: nothing else stops. */
    suspend fun activate(id: CgmSourceId)

    /** Refuses the AUTHORITATIVE source. */
    suspend fun deactivate(id: CgmSourceId)

    suspend fun activeSourceIds(): List<CgmSourceId>

    suspend fun loadSources(): List<CgmSourceDescriptor>

    /** `null` before any source is adopted. */
    suspend fun authoritativeSourceId(): CgmSourceId?

    /** Clamped to [CgmSourceDescriptor.WARMUP_WINDOW_RANGE]. */
    suspend fun setWarmupWindowMin(id: CgmSourceId, minutes: Int)

    /** Display flag only, not delete — readings stay; refuses the AUTHORITATIVE source. */
    suspend fun hide(id: CgmSourceId)

    /** Upserts on (sourceId, tsMs); projects to `sample` only if authoritative (§3.5). */
    suspend fun upsertReading(reading: CgmReading)

    /** Stored rates under [sourcePrefix] before [beforeMs] ÷10, once per [onceKey]. */
    suspend fun divideRatesByTenOnce(sourcePrefix: String, beforeMs: Long, onceKey: String): Int

    /** Opaque bytes, plugin-versioned; `null` if none held. Loss retires a still-worn sensor. */
    suspend fun loadSensorSecret(id: CgmSourceId): ByteArray?

    suspend fun saveSensorSecret(id: CgmSourceId, blob: ByteArray)

    /** `0` when none stored; kept outside the secret so a lost value costs one catch-up pull. */
    suspend fun loadSourceCursor(id: CgmSourceId): Int

    suspend fun saveSourceCursor(id: CgmSourceId, cursor: Int)

    /** Highest delivered index, `0` when none; apart from cursor so repair resets don't lose it. */
    suspend fun loadHighestDeliveredId(id: CgmSourceId): Int

    suspend fun saveHighestDeliveredId(id: CgmSourceId, glucoseId: Int)

    /** Last BLE address a session authenticated on, `null` when none; plain, not key material. */
    suspend fun loadSensorAddress(id: CgmSourceId): String?

    suspend fun saveSensorAddress(id: CgmSourceId, address: String)

    /** Minutes of wear the sensor stated for itself, `null` when none stored. */
    suspend fun loadSensorLifetimeMin(id: CgmSourceId): Int?

    suspend fun saveSensorLifetimeMin(id: CgmSourceId, minutes: Int)

    /** Every `minFromStart` filed since the retention floor or [notBeforeMs], if that is later. */
    suspend fun receivedSampleMinutes(
        id: CgmSourceId,
        notBeforeMs: Long = Long.MIN_VALUE,
    ): CgmReceivedSamples

    /** Arrival time beside its sample index; only witness to a wear start with no anchor. */
    suspend fun advertArrivals(id: CgmSourceId): List<Ct5AnchorRepair.Arrival>

    /** Every reading and raw sample this source has filed, gone. Its secret and its row stay. */
    suspend fun deleteReadingsForSource(id: CgmSourceId): Int

    /** Destructive, unrecoverable; only when the sensor disowns a pairing, stops a second one. */
    suspend fun clearSensorSecret(id: CgmSourceId)

    suspend fun insertRawAdvert(
        sourceId: CgmSourceId?,
        rxWallMs: Long,
        rssi: Int?,
        payload: ByteArray,
        crcValid: Boolean,
        minFromStart: Int?,
    )
}

/** [minutes] is complete from [completeSinceMs] on and says nothing about anything earlier. */
data class CgmReceivedSamples(val completeSinceMs: Long, val minutes: Set<Int>)
