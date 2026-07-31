package com.t1dm.cgm

import android.util.Log
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The persisted set of AiDEX X sources and the single manually-chosen active one
 * (§3.1). It owns the one shared [BleAdvertScanner]: [start] collects raw adverts,
 * recognizes them via [AidexXPlugin], auto-adopts new sources (setting the first-ever one active),
 * and routes every recognized advert to its [AidexXSource]. Many sources may be recorded at once
 * but exactly one is active = authoritative; inference and alarms consume only [activeSource].
 */
class AidexXSourceRegistry(
    private val plugin: AidexXPlugin,
    private val repository: CgmRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CgmSourceRegistry {

    private val _sources = MutableStateFlow<List<CgmSourceDescriptor>>(emptyList())
    override val sources: StateFlow<List<CgmSourceDescriptor>> = _sources.asStateFlow()

    private val _active = MutableStateFlow<CgmSourceId?>(null)
    override val active: StateFlow<CgmSourceId?> = _active.asStateFlow()

    private val live = ConcurrentHashMap<String, AidexXSource>()

    /**
     * Begin the shared passive scan; recognized adverts are adopted and routed. [scannerFor] builds a
     * scanner for a given report-delay; [reportDelayMs] selects the mode and RESTARTS the scan on every
     * change (via [collectLatest]) — the caller drives it real-time (0) while the screen is on for full
     * capture sensitivity and offloaded-batch while it is off so HyperOS does not suspend the scan
     * ([BleAdvertScanner]). Within each mode the scan is supervised: [BleAdvertScanner.rawAdverts]'
     * `callbackFlow` closes itself on any `onScanFailed` (an adapter blip, an MTK-stack hiccup), and
     * without the loop a single drop would end collection until the next process restart. We restart
     * with bounded exponential backoff, resetting it once a scan has stayed healthy.
     */
    fun start(reportDelayMs: Flow<Long>, scannerFor: (Long) -> BleAdvertScanner) {
        scope.launch {
            hydrate()
            reportDelayMs.collectLatest { delayMs -> superviseScan(scannerFor(delayMs)) }
        }
    }

    private suspend fun superviseScan(scanner: BleAdvertScanner): Unit = coroutineScope {
        var backoffMs = SCAN_RETRY_MIN_MS
        while (isActive) {
            val startedAt = nowMs()
            try {
                scanner.rawAdverts().collect { raw -> onRawAdvert(raw) }
            } catch (c: CancellationException) {
                throw c // mode switch or scope shutdown — do not restart in place
            } catch (t: Throwable) {
                Log.w(TAG, "passive scan dropped (${t.message}); restarting in ${backoffMs}ms", t)
            }
            if (!isActive) break
            backoffMs = if (nowMs() - startedAt >= SCAN_HEALTHY_MS) SCAN_RETRY_MIN_MS
            else (backoffMs * 2).coerceAtMost(SCAN_RETRY_MAX_MS)
            delay(backoffMs)
        }
    }

    /**
     * Rehydrate the persisted sources and the chosen active id before the first advert. Without
     * this, `_active` starts null and the first advert seen would seize `active` (§3.1), silently
     * overriding the user's authoritative choice across a process restart.
     */
    private suspend fun hydrate() {
        val persisted = repository.loadSources()
        persisted.forEach { d ->
            // The PERSISTED descriptor, not a fresh seed from the id: `createSource(id)` rebuilds the
            // vendor default, which would silently revert the user's tuned warm-up window on every
            // process start — the window would then only ever hold until the next launch.
            val source = live.getOrPut(d.id.value) { plugin.createSource(d) }
            source.onScanning()
        }
        if (persisted.isNotEmpty()) _sources.value = persisted
        repository.activeSourceId()?.let { _active.value = it }
    }

    /** Visible for the service and for tests: process one captured advert end-to-end. */
    suspend fun onRawAdvert(raw: RawAdvert) {
        val payload = AdStructureParser.manufacturerPayload(raw.adBytes)
        val id = plugin.recognize(
            name = raw.name,
            manufacturerId = CgmConstants.MANUFACTURER_ID,
            manufacturerData = payload ?: ByteArray(0),
        ) ?: return
        adopt(id).ingest(raw)
    }

    override fun setActive(id: CgmSourceId) {
        _active.value = id
        scope.launch { repository.setActive(id) }
    }

    /**
     * CGM panel: retune one source's warm-up window (minutes). All three copies move together: the live
     * [AidexXSource] is what classifies the next advert, the column is what the panel and `:app` read
     * back and what survives the process (and what [hydrate] rebuilds the live source from), and
     * [_sources] keeps this registry's own [sources] view consistent with both.
     *
     * On this branch the configured window is the ONLY warm-up evidence there is — a passive
     * advertisement carries no warm-up bit — so an edit that did not reach the live source would leave
     * the panel showing one duration while the pipeline applied another.
     *
     * [AidexXSource.setWarmupWindowMin] owns the clamp; this repeats it only so the value written to
     * [_sources] and to storage is the same one that was installed.
     */
    fun setWarmupWindowMin(id: CgmSourceId, minutes: Int) {
        val clamped = minutes.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE)
        live[id.value]?.setWarmupWindowMin(clamped)
        _sources.update { current ->
            current.map { if (it.id == id) it.copy(warmupWindowMin = clamped) else it }
        }
        scope.launch { repository.setWarmupWindowMin(id, clamped) }
    }

    override fun activeSource(): AidexXSource? = _active.value?.let { live[it.value] }

    private suspend fun adopt(id: CgmSourceId): AidexXSource {
        live[id.value]?.let { return it }

        val source = plugin.createSource(id)
        live[id.value] = source
        source.onScanning()
        _sources.update { current ->
            if (current.any { it.id == id }) current else current + source.descriptor
        }

        val firstEver = _active.value == null
        repository.upsertSource(source.descriptor, active = firstEver, lastSeenMs = nowMs())
        if (firstEver) {
            _active.value = id
            repository.setActive(id)
        }
        return source
    }

    private companion object {
        const val TAG = "CgmScan"
        const val SCAN_RETRY_MIN_MS = 1_000L
        const val SCAN_RETRY_MAX_MS = 30_000L
        /** A scan that ran at least this long before dropping is treated as healthy: reset backoff. */
        const val SCAN_HEALTHY_MS = 60_000L
    }
}
