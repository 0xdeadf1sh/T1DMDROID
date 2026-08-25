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
 * The persisted AiDEX X sources, those being read, and the authoritative one (§3.1). Owns the one
 * shared [BleAdvertScanner]; the first-ever source adopted becomes authoritative. Widening
 * [activeIds] never widens what is believed: that narrowing is at the `sample` projection and the
 * reading bus, not here.
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
     * A new [reportDelayMs] RESTARTS the scan ([collectLatest]); the caller drives 0 while the screen
     * is on and offloaded-batch while it is off. [BleAdvertScanner.rawAdverts] closes itself on any
     * `onScanFailed`, so a drop is retried with bounded exponential backoff.
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
                throw c // do not restart in place
            } catch (t: Throwable) {
                Log.w(TAG, "passive scan dropped (${t.message}); restarting in ${backoffMs}ms", t)
            }
            if (!isActive) break
            backoffMs = if (nowMs() - startedAt >= SCAN_HEALTHY_MS) SCAN_RETRY_MIN_MS
            else (backoffMs * 2).coerceAtMost(SCAN_RETRY_MAX_MS)
            delay(backoffMs)
        }
    }

    /** Without this the first advert after a process restart seizes authority (§3.1). */
    private suspend fun hydrate() {
        val persisted = repository.loadSources()
        persisted.forEach { d ->
            // The persisted descriptor: `createSource(id)` would revert the tuned warm-up window.
            val source = live.getOrPut(d.id.value) { plugin.createSource(d) }
            source.onScanning()
        }
        if (persisted.isNotEmpty()) _sources.value = persisted
        _activeIds.value = repository.activeSourceIds().toSet()
        repository.authoritativeSourceId()?.let { _authoritative.value = it }

        // Once per process start, so the descriptor reaches the server: `adopt` is the only other
        // caller and it returns early for a source already live. Idempotent, deduped in the outbox.
        persisted.forEach { d ->
            repository.upsertSource(d, authoritative = d.id == _authoritative.value, lastSeenMs = nowMs())
        }
    }

    /**
     * Every recognised advert is decoded and stored whatever the source's `active` flag says — a
     * deliberate divergence from the connected branch, where `active` gates a held GATT link. Here
     * `active` is only what the BG panel may switch to; `authoritative` alone decides what is believed.
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
        // Authority implies active.
        _activeIds.update { it + id }
        scope.launch { repository.setAuthoritative(id) }
    }

    /**
     * Minutes. All three copies move together: the live [AidexXSource] classifies the next advert, the
     * column survives the process, [_sources] is this registry's view. The clamp repeats
     * [AidexXSource.setWarmupWindowMin]'s so the value stored is the one installed.
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
     * Off the lists, not deleted. The live [AidexXSource] is deliberately left in place: a removed
     * sensor still advertising keeps being decoded and stored ([onRawAdvert]). The authoritative
     * source is refused — authority can move on its own before the press lands.
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

    /** Costs no radio: a recognised sensor in range is decoded whatever its flag. */
    override fun activate(id: CgmSourceId) {
        _activeIds.update { it + id }
        scope.launch { repository.activate(id) }
    }

    /** The authoritative source is refused: authority can move before the press lands. */
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
        /** A scan lasting this long before dropping is healthy: reset the backoff. */
        const val SCAN_HEALTHY_MS = 60_000L
    }
}
