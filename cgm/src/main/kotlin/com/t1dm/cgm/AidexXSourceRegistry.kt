package com.t1dm.cgm

import android.util.Log
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The persisted set of AiDEX X sources and the single manually-chosen active one
 * (SPEC.private.md §3.1). It owns the one shared [BleAdvertScanner]: [start] collects raw adverts,
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
     * Begin the shared passive scan; recognized adverts are adopted and routed. Supervised: the
     * underlying [BleAdvertScanner.rawAdverts] `callbackFlow` closes itself on any `onScanFailed`
     * (an adapter blip, an MTK-stack hiccup on the screen-off transition), and without this loop a
     * single such drop would end collection permanently until the next process restart. We restart
     * with bounded exponential backoff, resetting it once a scan has stayed healthy, so a transient
     * failure self-heals while a hard-down adapter is retried gently rather than hammered.
     */
    fun start(scanner: BleAdvertScanner) {
        scope.launch {
            hydrate()
            var backoffMs = SCAN_RETRY_MIN_MS
            while (isActive) {
                val startedAt = nowMs()
                try {
                    scanner.rawAdverts().collect { raw -> onRawAdvert(raw) }
                } catch (c: CancellationException) {
                    throw c // scope shutdown — do not restart
                } catch (t: Throwable) {
                    Log.w(TAG, "passive scan dropped (${t.message}); restarting in ${backoffMs}ms", t)
                }
                if (!isActive) break
                backoffMs = if (nowMs() - startedAt >= SCAN_HEALTHY_MS) SCAN_RETRY_MIN_MS
                else (backoffMs * 2).coerceAtMost(SCAN_RETRY_MAX_MS)
                delay(backoffMs)
            }
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
            val source = live.getOrPut(d.id.value) { plugin.createSource(d.id) }
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
