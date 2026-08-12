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
 * The persisted set of AiDEX X sources, the set the app is READING, and the single authoritative one
 * among them (§3.1). It owns the one shared [BleAdvertScanner]: [start] collects raw adverts,
 * recognizes them via [AidexXPlugin], auto-adopts new sources (setting the first-ever one
 * authoritative), and routes every recognized advert to its own [AidexXSource].
 *
 * **Several sensors at once comes free here.** One passive scan hears every sensor in range, so every
 * recognised advert has always been decoded and stored by its own source with its own dedup ring and
 * grid stamper. What v14 adds is not concurrency but the vocabulary for it: [activeIds] names the
 * sensors the BG panel may be switched between, and [authoritative] names the one that feeds the
 * model, the statistics, the alarms and the wire. Widening what is drawn never widens what is
 * believed — the narrowing itself lives at the `sample` projection and at the reading bus, not here.
 */
class AidexXSourceRegistry(
    private val plugin: AidexXPlugin,
    private val repository: CgmRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CgmSourceRegistry {

    private val _sources = MutableStateFlow<List<CgmSourceDescriptor>>(emptyList())
    override val sources: StateFlow<List<CgmSourceDescriptor>> = _sources.asStateFlow()

    private val _authoritative = MutableStateFlow<CgmSourceId?>(null)
    override val authoritative: StateFlow<CgmSourceId?> = _authoritative.asStateFlow()

    private val _activeIds = MutableStateFlow<Set<CgmSourceId>>(emptySet())
    override val activeIds: StateFlow<Set<CgmSourceId>> = _activeIds.asStateFlow()

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
     * this, `_authoritative` starts null and the first advert seen would seize authority (§3.1), silently
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
        _activeIds.value = repository.activeSourceIds().toSet()
        repository.authoritativeSourceId()?.let { _authoritative.value = it }

        // Re-record every persisted source once per process start, so its descriptor reaches the
        // server. `adopt` is the only other caller and it returns early for a source already live, so
        // without this a sensor the phone met before the descriptor push existed would never be
        // described — the server would hold its readings' labels and nothing to resolve them to. The
        // upsert is idempotent and the outbox row deduplicates on the source id, so this costs one
        // write per known sensor per launch.
        persisted.forEach { d ->
            repository.upsertSource(d, authoritative = d.id == _authoritative.value, lastSeenMs = nowMs())
        }
    }

    /**
     * Visible for the service and for tests: process one captured advert end-to-end.
     *
     * **Every recognised advert is decoded and stored, whatever the source's `active` flag says.**
     * That is a deliberate difference from the connected branch, not drift. There, `active` gates a
     * held GATT link and costs battery; here one passive scan hears every sensor in range and the
     * marginal cost of keeping what it already received is a row. Dropping it instead would throw
     * away the only copy of a reading nothing else in the system heard — and on an upgrade, where
     * `MIGRATION_13_14` seeds `active` from the single pre-v14 authoritative flag, it would silently
     * end the recording of a second sensor a user was already wearing.
     *
     * So on this branch `active` means what the BG panel can be switched to and what the list calls
     * live, and `authoritative` alone decides what is believed. §7.1 still holds: an active source's
     * readings are retained. This branch simply also retains the others'.
     */
    suspend fun onRawAdvert(raw: RawAdvert) {
        val payload = AdStructureParser.manufacturerPayload(raw.adBytes)
        val id = plugin.recognize(
            name = raw.name,
            manufacturerId = CgmConstants.MANUFACTURER_ID,
            manufacturerData = payload ?: ByteArray(0),
        ) ?: return
        adopt(id).ingest(raw)
    }

    override fun setAuthoritative(id: CgmSourceId) {
        _authoritative.value = id
        // Authority implies the app is reading it, so the two move together here as they do in storage.
        _activeIds.update { it + id }
        scope.launch { repository.setAuthoritative(id) }
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

    /**
     * Settings → CGM source "Remove": take a retired sensor off the list. The column is what the list
     * is drawn from on this branch — it reads `cgm_source` through Room, not [sources] — so the write
     * that matters is the persisted one. [_sources] is marked alongside it to keep this registry's own
     * view honest and the two branches' registries the same shape, not because anything reads it here.
     *
     * The live [AidexXSource] is deliberately left in place: on this branch a removed sensor still
     * advertising keeps being decoded and stored exactly as before ([onRawAdvert]). It is off the
     * lists, no longer among the sensors the BG panel may be switched to, and never was
     * authoritative — but its readings are not thrown away, because this scan is the only thing that
     * heard them.
     *
     * The AUTHORITATIVE source is refused. The ✕ is drawn only on the other rows, but the first-ever
     * advert adopts a source on its own, so the id the user pressed may be it by the time this runs.
     */
    fun hide(id: CgmSourceId) {
        if (id == _authoritative.value) return
        _sources.update { current ->
            current.map { if (it.id == id) it.copy(hidden = true) else it }
        }
        _activeIds.update { it - id }
        scope.launch { repository.hide(id) }
    }

    override fun authoritativeSource(): AidexXSource? = _authoritative.value?.let { live[it.value] }

    override fun liveSource(id: CgmSourceId): AidexXSource? = live[id.value]

    /**
     * Start reading [id]. On this branch a recognised sensor in range is decoded whatever its flag —
     * one scan hears them all and dropping a frame would cost more than keeping it — so this widens
     * what the BG panel may be switched to and what the CGM list calls live, and costs no radio.
     */
    override fun activate(id: CgmSourceId) {
        _activeIds.update { it + id }
        scope.launch { repository.activate(id) }
    }

    /** Stop reading [id]. The authoritative source is refused: it cannot be reached from the list,
     *  but authority moves on its own, so the id the user pressed may have become it by now. */
    override fun deactivate(id: CgmSourceId) {
        if (id == _authoritative.value) return
        _activeIds.update { it - id }
        scope.launch { repository.deactivate(id) }
    }

    private suspend fun adopt(id: CgmSourceId): AidexXSource {
        live[id.value]?.let { return it }

        val source = plugin.createSource(id)
        live[id.value] = source
        source.onScanning()
        _sources.update { current ->
            if (current.any { it.id == id }) current else current + source.descriptor
        }

        val firstEver = _authoritative.value == null
        repository.upsertSource(source.descriptor, authoritative = firstEver, lastSeenMs = nowMs())
        if (firstEver) {
            _authoritative.value = id
            _activeIds.update { it + id }
            repository.setAuthoritative(id)
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
