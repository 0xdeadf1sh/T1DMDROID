package com.t1dm.sync

import com.t1dm.data.T1dmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires [StreamClient] to the app-authoritative reconcile (§3.4/§3.5/§3.8). The phone is the sole
 * read-write author and every server write fans out `broadcast_except(origin)`, so a live `sample`
 * frame is the only thing the phone ever receives back — meal/dose/basal/stats echoes never arrive
 * and their history is hydrated over REST only. On BOTH [StreamEvent.Connected] and
 * [StreamEvent.Reconnected] this runs one reconcile pass:
 *
 *  1. §3.8 (H7) re-mirror gate — read the server `store_epoch` from `GET /v1/health`; if it differs
 *     from the last fully-mirrored epoch (a freshly-wiped/new server, or first-ever sync) run the
 *     bounded, idempotent [reMirror] upload of local history, recording the epoch only on completion.
 *  2. §3.5 download catch-up from the LOCAL high-water marks [T1dmRepository.newestSampleTs] (scalar
 *     samples via `GET /v1/series`) and [T1dmRepository.newestEventTs] (meal/dose curve events via
 *     `GET /v1/meals` + `GET /v1/doses`), IGNORING the WS cursor entirely. A live-channel overflow
 *     latched in [desync] escalates the whole pass to a full resync (`from = null`).
 *  3. §3.4 hydrate each curve event by phone `client_id` (idempotent `insertIgnore`; never
 *     re-projected into `sample`, never re-enqueued — this data originated on the phone).
 *  4. Reconcile the wide `sample` BG history into `cgm_reading` so the graph and model see it.
 *
 * [events] is the merged pass-through stream: collect it from the foreground service. The reconcile
 * is a side-effect of collection, so nothing runs until someone subscribes.
 */
class CatchUpCoordinator(
    private val stream: StreamClient,
    private val http: SyncHttpClient,
    private val repo: T1dmRepository,
    // §3.8: bounded, idempotent upload of local authoritative history to a freshly-wiped server. The
    // default no-op never records a false epoch (returns false ⇒ "incomplete"); the real walker is
    // wired at the :app composition root (it needs the outbox enqueuers + entity→DTO mappers).
    private val reMirror: HistoryReMirror = HistoryReMirror { false },
    // §3.5: the live channel latches an overflow here (`trySend` dropped a frame); the next connect
    // then forces a full resync. MUST be the SAME instance the StreamClient sets — wired in AppContainer.
    private val desync: AtomicBoolean = AtomicBoolean(false),
    private val pageLimit: Int = 5_000,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun events(): Flow<StreamEvent> = stream.events().onEach { ev ->
        when (ev) {
            is StreamEvent.Sample -> repo.mergeServerSample(ev.patch)
            // A (re)connect is the phone's cue to reconcile with the server. Connected and Reconnected
            // do the SAME pass now — catch up from the local high-water marks, never the poisoned WS
            // cursor (§3.5) — so a mid-session server wipe (surfacing as Reconnected) is also caught by
            // the §3.8 re-mirror gate. Wrapped so a transient REST failure never tears down the stream.
            is StreamEvent.Connected -> runCatching { reconcileOnConnect() }
                .onFailure { Timber.tag(TAG).w(it, "connect catch-up failed") }
            is StreamEvent.Reconnected -> runCatching { reconcileOnConnect() }
                .onFailure { Timber.tag(TAG).w(it, "reconnect catch-up failed") }
            else -> Unit
        }
    }

    /**
     * The (re)connect reconcile pass. The re-mirror gate runs first (so a wiped server is re-populated
     * before we read our own history back) and is isolated in its own `runCatching` — a health/upload
     * hiccup must not block the download. A latched live-channel overflow ([desync]) escalates to a
     * full resync (`from = null`); the download is idempotent, so a complete re-pull is harmless.
     */
    private suspend fun reconcileOnConnect() {
        runCatching { maybeReMirror() }.onFailure { Timber.tag(TAG).w(it, "re-mirror check failed") }

        val fullResync = desync.getAndSet(false)
        if (fullResync) Timber.tag(TAG).i("live-channel overflow latched → full resync")

        catchUp(if (fullResync) null else repo.newestSampleTs())
        val events = catchUpEvents(if (fullResync) null else repo.newestEventTs())
        val filled = repo.reconcileReadingsFromSamples()

        if (filled > 0) Timber.tag(TAG).i("reconciled %d sample row(s) into cgm_reading", filled)
        if (events > 0) Timber.tag(TAG).i("hydrated %d meal/dose catch-up event(s)", events)
    }

    /**
     * §3.8 (H7): if the server's `store_epoch` differs from the last fully-mirrored epoch, re-upload
     * the phone's authoritative history via [reMirror] (bounded, idempotent PUTs) and persist the epoch
     * only once the FULL history has been enqueued — a partial pass returns false and simply resumes on
     * the next connect. A matching epoch (the common case) costs one `GET /v1/health` and no upload.
     */
    private suspend fun maybeReMirror() {
        val serverEpoch = http.health().store_epoch?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (serverEpoch == repo.getKv(KV_MIRRORED_EPOCH)) return
        Timber.tag(TAG).i("server store_epoch changed → re-mirroring local history")
        if (reMirror.reMirror()) {
            repo.putKv(KV_MIRRORED_EPOCH, serverEpoch, nowMs())
            Timber.tag(TAG).i("re-mirror complete; recorded epoch")
        } else {
            Timber.tag(TAG).i("re-mirror bounded pass incomplete; resumes next connect")
        }
    }

    /**
     * Page `GET /v1/series` forward from [fromCursor] (exclusive), presence-merging each row into the
     * wide `sample` table. Returns the number of rows that actually wrote (the presence gap-fill
     * rejects fields the phone already holds). Stops on an empty page or a null continuation cursor.
     * Public: the debug `resyncFromServer()` hook drives a full sample re-download with `null`.
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

    /**
     * §3.4 curve-event catch-up: pull meal and dose events with `ts >= from` ([from] = null for the
     * full history) and hydrate each by phone `client_id`. The inclusive boundary re-fetch is harmless
     * — hydration is an id-keyed `insertIgnore`, never re-projected into `sample` and never re-enqueued
     * (this data originated on the phone). Returns the number of events processed. Public: the reset→
     * resync hook drives a full event re-download with `null`.
     */
    suspend fun catchUpEvents(from: Long?): Int {
        var processed = 0
        for (dto in http.getMeals(from = from, to = null).meals) {
            repo.hydrateMealEvent(dto.toLoggedMealEntity()); processed++
        }
        for (dto in http.getDoses(from = from, to = null).doses) {
            repo.hydrateDoseEvent(dto.toLoggedDoseEntity()); processed++
        }
        return processed
    }

    private companion object {
        const val TAG = "CatchUp"
        const val KV_MIRRORED_EPOCH = "sync.mirrored_epoch"
    }
}

/**
 * §3.8 (H7) re-mirror hook: bulk-re-push the phone's authoritative local history — meals, doses, the
 * active basal schedule, scalar samples, and the latest-per-window stats blocks — to a freshly-wiped
 * server as idempotent PUTs, in bounded batches so the outbox size cap (§3.7) is never blown in one
 * shot. Wired at the `:app` composition root, where the outbox enqueuers, the entity→DTO mappers, and
 * the repo range readers all coexist (`:data` deliberately keeps no `:sync` dependency). Returns true
 * once the FULL history has been enqueued (the coordinator then records the epoch); false when a
 * bounded pass left more to do, so the coordinator retries on the next (re)connect without recording.
 */
fun interface HistoryReMirror {
    suspend fun reMirror(): Boolean
}
