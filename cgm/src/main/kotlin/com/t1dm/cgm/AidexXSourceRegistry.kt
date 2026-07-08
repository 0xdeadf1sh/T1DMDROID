package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The persisted set of AiDEX X sources and the single manually-chosen active one
 * (PLAN.private.md §3.1). It owns the one shared [BleAdvertScanner]: [start] collects raw adverts,
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

    /** Begin the shared passive scan; recognized adverts are adopted and routed. */
    fun start(scanner: BleAdvertScanner) {
        scope.launch {
            hydrate()
            scanner.rawAdverts().collect { raw -> onRawAdvert(raw) }
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
}
