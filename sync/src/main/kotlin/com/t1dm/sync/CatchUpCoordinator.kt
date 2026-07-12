package com.t1dm.sync

import com.t1dm.data.T1dmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Wires [StreamClient] to the wide-table LWW merge (deliverable 4). Live `sample` events fold in as
 * they arrive; on [StreamEvent.Reconnected] it does a REST catch-up from the last-seen cursor via
 * `GET /v1/series`, paging forward and merging every row with LWW on `updated_at` — closing the gap
 * a disconnect opened. Alerts pass through untouched for the alert subsystem + Network panel.
 *
 * [events] is the merged pass-through stream: collect it from the foreground service. The merges are
 * a side-effect of collection, so nothing runs until someone subscribes.
 */
class CatchUpCoordinator(
    private val stream: StreamClient,
    private val http: SyncHttpClient,
    private val repo: T1dmRepository,
    private val pageLimit: Int = 5_000,
) {
    fun events(): Flow<StreamEvent> = stream.events().onEach { ev ->
        when (ev) {
            is StreamEvent.Sample -> repo.mergeServerSample(ev.patch)
            // First connect of the session: back-fill from the local head so a fresh (or post-reset)
            // phone pulls the history it is missing, not only live samples going forward (empty
            // projection ⇒ null cursor ⇒ full download), then reconcile the wide `sample` history into
            // `cgm_reading` so the graph and the model actually see it — including history synced in
            // before the reading hydration existed.
            is StreamEvent.Connected -> runCatching {
                catchUp(repo.newestSampleTs())
                val filled = repo.reconcileReadingsFromSamples()
                if (filled > 0) Timber.tag(TAG).i("reconciled %d sample rows into cgm_reading", filled)
                val doses = repo.reconcileDoseEventsFromSamples()
                if (doses > 0) Timber.tag(TAG).i("reconciled %d carb/bolus events from samples", doses)
            }.onFailure { Timber.tag(TAG).w(it, "connect catch-up failed") }
            is StreamEvent.Reconnected -> runCatching { catchUp(ev.cursor) }
                .onFailure { Timber.tag(TAG).w(it, "catch-up failed") }
            else -> Unit
        }
    }

    /**
     * Page `GET /v1/series` forward from [fromCursor] (exclusive), merging each row. Returns the
     * number of rows that actually wrote (LWW may reject rows the phone already has newer). Stops on
     * an empty page or a null continuation cursor.
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

    private companion object {
        const val TAG = "CatchUp"
    }
}
