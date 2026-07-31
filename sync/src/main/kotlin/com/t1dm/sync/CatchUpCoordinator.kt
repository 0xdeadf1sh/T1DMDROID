package com.t1dm.sync

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
 * Wires [StreamClient] to the app-authoritative reconcile (§3.4/§3.5/§3.8). The phone is the sole
 * read-write author and every server write fans out `broadcast_except(origin)`, so a live `sample`
 * frame is the only thing the phone ever receives back — meal/dose/basal/stats echoes never arrive
 * and their history is hydrated over REST only. On BOTH [StreamEvent.Connected] and
 * [StreamEvent.Reconnected] this runs one reconcile pass:
 *
 *  1. §3.8 (H7) re-mirror gate — read the server `store_epoch` from `GET /v1/health`; if it differs
 *     from the last delivered epoch (a freshly-wiped/new server, or first-ever sync) run the bounded,
 *     idempotent [reMirror] upload of local history, recording the epoch only once it has been
 *     delivered. This one is *kicked* into [scope] and not awaited — see [kickReMirror].
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
    // Where the §3.8 pass runs. It must NOT be the scope collecting [events]: the pass drains the
    // outbox and waits on it, and a collector blocked doing that is a collector not merging live
    // `sample` frames and not reaching the `drainNow()` downstream of it. Process-scoped in the app.
    private val scope: CoroutineScope,
    // §3.8: bounded, idempotent upload of local authoritative history to a freshly-wiped server. The
    // default no-op never records a false epoch (returns false ⇒ "not delivered"); the real walker is
    // wired at the :app composition root (it needs the outbox enqueuers + entity→DTO mappers), and it
    // MUST be wired there — left at this default the gate is inert and no history is ever re-mirrored.
    private val reMirror: HistoryReMirror = HistoryReMirror { false },
    // §3.5: the live channel latches an overflow here (`trySend` dropped a frame); the next connect
    // then forces a full resync. MUST be the SAME instance the StreamClient sets — wired in AppContainer.
    private val desync: AtomicBoolean = AtomicBoolean(false),
    private val pageLimit: Int = 5_000,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** The §3.8 pass has exactly one owner. A connect arriving while a pass is in flight adds
     *  nothing — the pass reads the epoch itself and resumes from its own persisted cursor — so the
     *  lock is *tried*, never waited on, and a redundant connect is dropped rather than queued. */
    private val reMirrorLock = Mutex()

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
     * The (re)connect reconcile pass: kick the §3.8 upload gate, then catch the download up. The two
     * run CONCURRENTLY and the download does not wait on the upload, which costs nothing — a wiped
     * server has nothing to serve back, and the download is a presence gap-fill that never overwrites
     * what the phone already holds. A latched live-channel overflow ([desync]) escalates to a full
     * resync (`from = null`); the download is idempotent, so a complete re-pull is harmless.
     */
    private suspend fun reconcileOnConnect() {
        kickReMirror()

        val fullResync = desync.getAndSet(false)
        if (fullResync) Timber.tag(TAG).i("live-channel overflow latched → full resync")

        catchUp(if (fullResync) null else repo.newestSampleTs())
        val events = catchUpEvents(if (fullResync) null else repo.newestEventTs())
        val filled = repo.reconcileReadingsFromSamples()

        if (filled > 0) Timber.tag(TAG).i("reconciled %d sample row(s) into cgm_reading", filled)
        if (events > 0) Timber.tag(TAG).i("hydrated %d meal/dose catch-up event(s)", events)
    }

    /**
     * Start the §3.8 pass in [scope] and return at once.
     *
     * The pass enqueues history and then waits on the outbox to empty of it, so it must not run on the
     * stream collector: every wait there stalls the live [StreamEvent.Sample] merges and — worse — the
     * `drainNow()` the foreground service performs downstream of this very collector on the same
     * Connected event, which is the thing the pass is waiting for. Awaiting it inline was a
     * self-deadlock dressed as back-pressure.
     *
     * Off the collector but still a single owner: the trigger stays the (re)connect edge, the epoch
     * gate and the epoch WRITE stay inside [maybeReMirror], and [reMirrorLock] admits one pass at a
     * time. An independent driver that banked the epoch itself is what marked a partial upload
     * complete; that mistake is not to be re-made in the name of getting off the collector.
     *
     * The lock is taken INSIDE the launched coroutine, not before it. Taken outside, a [scope] that is
     * already cancelled would leave a coroutine whose body — and so whose `finally` — never runs, and a
     * lock held forever here disables the §3.8 gate for the life of the process without a sound.
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
     * §3.8 (H7): if the server's `store_epoch` differs from the last delivered epoch, re-upload the
     * phone's authoritative history via [reMirror] and persist the epoch only once that history has
     * been **delivered** — not merely enqueued. `http-api.md` is explicit that the client replays its
     * history through the write endpoints "and only then" persists the new epoch, and the distinction is
     * the whole guarantee: this key matching is the ONLY thing that ever re-triggers a mirror, so an
     * epoch banked over an outbox still holding the walk turns an interrupted upload into permanent,
     * undetectable loss (INGEST markers age out at `DrainConfig.maxAgeMs`, and nothing else re-pushes
     * scalar history). A pass that has not yet drained returns false and is re-checked on the next
     * connect. A matching epoch (the common case) costs one `GET /v1/health` and no upload.
     *
     * A `null` epoch means *unknown*, not *changed*, and is never grounds for a replay — hence the
     * blank/absent guard rather than a comparison against null.
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
    }
}

/**
 * §3.8 (H7) re-mirror hook: bulk-re-push the phone's authoritative local history — meals, doses,
 * scalar samples, and the latest-per-window stats blocks — to a freshly-wiped server as idempotent
 * PUTs, in bounded batches so the outbox size cap (§3.7) is never blown in one shot. Wired at the
 * `:app` composition root, where the outbox enqueuers, the entity→DTO mappers, and the repo range
 * readers all coexist (`:data` deliberately keeps no `:sync` dependency).
 *
 * Returns true only once that history has **left the phone** — the coordinator then records
 * [serverEpoch]. False means the walk still has ground to cover, or has covered it but not yet seen it
 * delivered; it is called again on every (re)connect while the epoch differs.
 *
 * **One pass need not finish the history, but it must bank what it did.** A month of five-minute
 * samples is tens of thousands of rows and will not drain inside one connect, so the implementation
 * owns a persisted, per-epoch cursor ([ReMirrorLedger]) and resumes from it rather than re-walking
 * from the start — a walk that begins again at row zero on every connect never converges at all.
 *
 * [serverEpoch] is the `store_epoch` being mirrored to. It is passed rather than re-read so the
 * implementation's own progress bookkeeping cannot key off a different epoch than the one the
 * coordinator will record.
 */
fun interface HistoryReMirror {
    suspend fun reMirror(serverEpoch: String): Boolean
}
