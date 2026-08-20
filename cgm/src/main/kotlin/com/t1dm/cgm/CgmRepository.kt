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

    /**
     * Record (or refresh) a discovered source; [authoritative] participates in the
     * exactly-one-authoritative invariant (§3.1) and must be reconciled by the implementation via a
     * transaction. Whether the app is READING the source is not settled here — it is the user's
     * standing decision, preserved from storage across a re-sighting.
     *
     * Answers the source's [CgmSourceDescriptor.ordinal] as it now stands on record — the stored one, or
     * the one this call minted. **The caller is expected to carry it back into whatever in-memory copy of
     * the descriptor it holds.** The number is minted here, inside the write transaction, because that is
     * the only place two sensors recorded at once cannot claim the same one; a caller that discards the
     * answer keeps a descriptor labelled `CGM` while every surface reading storage says `CGM #n`, for as
     * long as its copy lives.
     */
    suspend fun upsertSource(
        descriptor: CgmSourceDescriptor,
        authoritative: Boolean,
        lastSeenMs: Long,
    ): Int

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
     * the authoritative source, project its BG onto the wide `sample` row for `tsMs` (§3.5). Another
     * active source's readings are stored and drawn and reach nothing else.
     *
     * Which of a slot's several samples the projection carries is settled ONCE, on the reading side, by
     * the storage layer's slot contest — the projection does not re-decide it against the `sample` row's
     * `updatedAt`, which other channels also bump.
     */
    suspend fun upsertReading(reading: CgmReading)

    /**
     * The sealed per-sensor secret for [id], or `null` if none is held.
     *
     * **Opaque by design.** Some sensor families hold per-sensor state that cannot be recovered from
     * the sensor afterwards. This port carries it as an undifferentiated byte string so the storage
     * layer neither names nor interprets any of it: the meaning belongs to the plugin that wrote it,
     * and its versioning with it.
     *
     * The implementation is expected to seal these bytes at rest. Nothing about them is derivable from
     * anything else, so losing them retires a sensor that is still on the patient's arm.
     */
    suspend fun loadSensorSecret(id: CgmSourceId): ByteArray?

    /** Store (or replace) [id]'s sealed secret. */
    suspend fun saveSensorSecret(id: CgmSourceId, blob: ByteArray)

    /**
     * A small per-source progress marker, or `0` when none has been stored.
     *
     * **Deliberately NOT part of the sealed secret**, and the distinction is the whole point of it
     * being a separate pair of calls. That blob holds what cannot be recovered from a sensor at all,
     * so its format is versioned strictly and a version it does not recognise is refused rather than
     * half-read — which means anything kept in it is lost to a downgrade, and losing that blob retires
     * a sensor that is still on the patient's arm. This number is the opposite kind of thing: it
     * records how far a family has read a sensor's own history, and the worst a lost one costs is one
     * catch-up pull. Storing the disposable beside the irreplaceable would have made a routine version
     * bump able to strand a live wear.
     */
    suspend fun loadSourceCursor(id: CgmSourceId): Int

    /** Store [id]'s progress marker. See [loadSourceCursor]. */
    suspend fun saveSourceCursor(id: CgmSourceId, cursor: Int)

    /**
     * Forget [id]'s secret. Destructive and unrecoverable — for a sensor deliberately retired, never as
     * part of a global reset.
     *
     * The one case where a plugin calls it by itself is state the sensor itself has already discarded:
     * the stored bytes then unlock nothing, and keeping them would leave a sensor the plugin will not
     * take up again, since a stored secret is what makes it refuse to re-establish one twice.
     */
    suspend fun clearSensorSecret(id: CgmSourceId)

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
