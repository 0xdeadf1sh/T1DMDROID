package com.t1dm.sync

import com.t1dm.core.model.CurveKind
import com.t1dm.data.T1dmRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires [StreamClient] to the app-authoritative reconcile (§3.4/§3.5/§3.8). Only `sample` frames
 * ever come back; meal/dose/basal/stats history is hydrated over REST. The reconcile is a side
 * effect of collecting [events], so nothing runs until someone subscribes.
 */
class CatchUpCoordinator(
    private val stream: StreamClient,
    private val http: SyncHttpClient,
    private val repo: T1dmRepository,
    // Where the §3.8 pass runs. Must NOT be the scope collecting [events]: the pass waits on a
    // drain that happens downstream of that collector.
    private val scope: CoroutineScope,
    // Must be wired at the `:app` root; left at this default the §3.8 gate is inert.
    private val reMirror: HistoryReMirror = HistoryReMirror { false },
    // Must be wired at the `:app` root; left at this default an unfiled deletion is lost silently.
    private val tombstones: TombstoneReplay = TombstoneReplay { 0 },
    // MUST be the SAME instance the StreamClient sets.
    private val desync: AtomicBoolean = AtomicBoolean(false),
    private val pageLimit: Int = 5_000,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Tried, never waited on: a pass resumes from its own cursor, so a redundant connect is dropped. */
    private val reMirrorLock = Mutex()

    fun events(): Flow<StreamEvent> = stream.events().onEach { ev ->
        when (ev) {
            is StreamEvent.Sample -> repo.mergeServerSample(ev.patch)
            is StreamEvent.Connected -> runCatching { reconcileOnConnect() }
                .onFailure { Timber.tag(TAG).w(it, "connect catch-up failed") }
            is StreamEvent.Reconnected -> runCatching { reconcileOnConnect() }
                .onFailure { Timber.tag(TAG).w(it, "reconnect catch-up failed") }
            else -> Unit
        }
    }

    private suspend fun reconcileOnConnect() {
        kickReMirror()

        val fullResync = desync.getAndSet(false)
        if (fullResync) Timber.tag(TAG).i("live-channel overflow latched → full resync")

        catchUp(if (fullResync) null else repo.newestSampleTsAtOrBefore(nowMs()))
        val events = catchUpEvents(if (fullResync) null else repo.newestEventTs())
        val filled = repo.reconcileReadingsFromSamples()
        val replayed = tombstones.replay()

        if (filled > 0) Timber.tag(TAG).i("reconciled %d sample row(s) into cgm_reading", filled)
        if (events > 0) Timber.tag(TAG).i("hydrated %d meal/dose catch-up event(s)", events)
        if (replayed > 0) Timber.tag(TAG).i("re-filed %d unpushed tombstone(s)", replayed)
    }

    /**
     * Off the collector: the pass waits on an outbox drain the collector's own downstream performs.
     * The lock is taken INSIDE the coroutine — taken outside, an already-cancelled [scope] would hold
     * it forever and disable the §3.8 gate for the life of the process.
     */
    private fun kickReMirror() {
        scope.launch {
            if (!reMirrorLock.tryLock()) {
                Timber.tag(TAG).i("re-mirror pass already in flight; this connect adds nothing")
                return@launch
            }
            try {
                maybeReMirror()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "re-mirror check failed")
            } finally {
                reMirrorLock.unlock()
            }
        }
    }

    /**
     * §3.8. The epoch is recorded only once the history is DELIVERED, not merely enqueued: banked
     * over an outbox still holding the walk, an interrupted upload is permanent silent loss. A blank
     * or absent epoch is unknown, not changed, and never grounds for a replay.
     */
    private suspend fun maybeReMirror() {
        val serverEpoch = http.health().store_epoch?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (serverEpoch == repo.getKv(ReMirrorKeys.MIRRORED_EPOCH)) return
        Timber.tag(TAG).i("server store_epoch changed → re-mirroring local history")
        if (reMirror.reMirror(serverEpoch)) {
            repo.putKv(ReMirrorKeys.MIRRORED_EPOCH, serverEpoch, nowMs())
            Timber.tag(TAG).i("re-mirror delivered; recorded epoch")
        } else {
            Timber.tag(TAG).i("re-mirror not yet delivered; re-checked next connect")
        }
    }

    /**
     * Pages `GET /v1/series` forward from [fromCursor] (exclusive; null = full re-download). Returns
     * the rows that actually wrote — the presence merge rejects fields the phone already holds.
     */
    suspend fun catchUp(fromCursor: Long?): Int {
        var cursor = fromCursor
        var merged = 0
        while (true) {
            val page = http.getSeries(from = null, to = null, cursor = cursor, limit = pageLimit, fields = null)
            if (page.rows.isEmpty()) break
            for (row in page.rows) if (repo.mergeServerSample(row.toPatch())) merged++
            cursor = page.next_cursor ?: break
        }
        Timber.tag(TAG).i("catch-up merged %d row(s) from cursor %s", merged, fromCursor)
        return merged
    }

    /** Meal and dose events with `ts >= from` (null = full history), hydrated by `client_id`. */
    suspend fun catchUpEvents(from: Long?): Int {
        var processed = 0
        val now = nowMs()
        for (dto in http.getMeals(from = from, to = null).meals) {
            applyMeal(dto, now); processed++
        }
        for (dto in http.getDoses(from = from, to = null).doses) {
            applyDose(dto, now); processed++
        }
        return processed
    }

    /**
     * A tombstone is applied, not hydrated. A `client_id` already here is an EDIT — `insertIgnore`
     * would drop it and leave the local row stale.
     */
    private suspend fun applyMeal(dto: MealEventDto, now: Long) {
        if (dto.deleted) {
            repo.applyServerTombstone(
                clientId = dto.client_id,
                kind = CurveKind.CARB,
                tsMs = dto.ts,
                tzOffsetMin = dto.tz_offset,
                updatedAt = dto.updated_at,
                nowMs = now,
            )
            return
        }
        val row = dto.toLoggedMealEntity()
        if (repo.hydrateMealEvent(row) == -1L) repo.applyServerMealEdit(row)
    }

    private suspend fun applyDose(dto: DoseEventDto, now: Long) {
        if (dto.deleted) {
            repo.applyServerTombstone(
                clientId = dto.client_id,
                kind = CurveKind.INSULIN,
                tsMs = dto.ts,
                tzOffsetMin = dto.tz_offset,
                updatedAt = dto.updated_at,
                nowMs = now,
            )
            return
        }
        val row = dto.toLoggedDoseEntity()
        if (repo.hydrateDoseEvent(row) == -1L) repo.applyServerDoseEdit(row)
    }

    private companion object {
        const val TAG = "CatchUp"
    }
}

/**
 * §3.8: bulk re-push of the phone's local history to a wiped server, in bounded batches. True only
 * once that history has LEFT the phone. One pass need not finish it, but must persist a per-epoch
 * cursor ([ReMirrorLedger]) and resume from it, or a walk restarted each connect never converges.
 */
fun interface HistoryReMirror {
    suspend fun reMirror(serverEpoch: String): Boolean
}

/**
 * Re-file the outbox push for every deletion that lacks one — a process death between `:data`'s
 * delete transaction and `:app` filing the push, or a tombstone the size cap evicted.
 */
fun interface TombstoneReplay {
    suspend fun replay(): Int
}
