package com.t1dm.watch

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.watch.ble.WatchCentral
import com.t1dm.watch.crypto.WatchDevice
import com.t1dm.watch.crypto.WatchSessionFactory
import com.t1dm.watch.crypto.WatchStores
import com.t1dm.watch.proto.WatchCodec
import com.t1dm.watch.proto.WatchRecordKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Every pairing's link plus at most one pairing in progress; SPEC/watch.md §1, §7. */
class WatchHub(
    private val centralProvider: () -> WatchCentral,
    private val sessionFactory: WatchSessionFactory,
    private val stores: WatchStores,
    private val codec: WatchCodec,
    private val glanceSource: WatchGlanceSource,
    private val extendedSource: WatchExtendedSource,
    private val lowPower: LowPowerProvider,
    private val dispatchers: T1dmDispatchers,
    private val config: WatchLinkConfig = WatchLinkConfig(enabled = true),
) {
    private val hubMutex = Mutex()
    private val links = MutableStateFlow<List<WatchLink>>(emptyList())
    private val pairingLink = MutableStateFlow<WatchLink?>(null)

    private val _devices = MutableStateFlow<List<WatchSecurityState>>(emptyList())
    /** One entry per paired peripheral, in pairing order. */
    val devices: StateFlow<List<WatchSecurityState>> = _devices.asStateFlow()

    private val _pairing = MutableStateFlow<WatchSecurityState?>(null)
    /** The pairing in progress; null when none. */
    val pairing: StateFlow<WatchSecurityState?> = _pairing.asStateFlow()

    private val _summary = MutableStateFlow(WatchSecurityState())
    /** The best-connected link, for one status light; lastPushMs is the newest of all. */
    val summary: StateFlow<WatchSecurityState> = _summary.asStateFlow()

    @Volatile
    private var enabled = config.enabled
    private var scope: CoroutineScope? = null
    private var mirrorJob: Job? = null

    private val listener = object : WatchLink.Listener {
        override suspend fun onPaired(link: WatchLink, device: WatchDevice) {
            val replaced = hubMutex.withLock {
                if (link in links.value) return@withLock emptyList()
                stores.devices.put(device)
                if (pairingLink.value === link) pairingLink.value = null
                val (same, rest) = links.value.partition { it.device?.id == device.id }
                links.value = rest + link
                same
            }
            replaced.forEach { it.stop() }
        }

        override suspend fun onUnpaired(link: WatchLink) {
            hubMutex.withLock {
                links.value = links.value - link
                if (pairingLink.value === link) pairingLink.value = null
            }
            link.stop()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        this.scope = scope
        mirrorJob?.cancel()
        mirrorJob = scope.launch(dispatchers.default) {
            launch {
                links.flatMapLatest { ls ->
                    if (ls.isEmpty()) flowOf(emptyList()) else combine(ls.map { it.state }) { it.toList() }
                }.collect { _devices.value = it; publishSummary() }
            }
            launch {
                pairingLink.flatMapLatest { it?.state ?: flowOf(null) }
                    .collect { _pairing.value = it; publishSummary() }
            }
        }
        // Undispatched: old links are gone before start returns, so a pairing begun next survives.
        scope.launch(dispatchers.default, start = CoroutineStart.UNDISPATCHED) { rehost() }
    }

    /** After a service restart, links bound to the old scope are rebuilt from the stores. */
    private suspend fun rehost() {
        val old = hubMutex.withLock {
            (links.value + listOfNotNull(pairingLink.value)).also {
                links.value = emptyList()
                pairingLink.value = null
            }
        }
        old.forEach { it.stop() }
        load()
    }

    private suspend fun load() {
        val s = scope ?: return
        hubMutex.withLock {
            if (!enabled || links.value.isNotEmpty()) return@withLock
            links.value = stores.devices.load().mapNotNull { d ->
                if (stores.pairing(d.id).load()?.bonded == true) {
                    newLink(d).also { it.start(s) }
                } else {
                    // A device row without keys is a pairing that died half-written.
                    stores.devices.remove(d.id)
                    null
                }
            }
        }
    }

    private fun newLink(device: WatchDevice?) = WatchLink(
        known = device,
        centralProvider = centralProvider,
        sessionFactory = sessionFactory,
        stores = stores,
        codec = codec,
        glanceSource = glanceSource,
        extendedSource = extendedSource,
        lowPower = lowPower,
        dispatchers = dispatchers,
        listener = listener,
        config = config.copy(enabled = true),
    )

    private fun publishSummary() {
        val all = _devices.value + listOfNotNull(_pairing.value)
        val best = all.minByOrNull { RANK.indexOf(it.phase).takeIf { r -> r >= 0 } ?: RANK.size } ?: WatchSecurityState()
        _summary.value = best.copy(lastPushMs = all.mapNotNull { it.lastPushMs }.maxOrNull())
    }

    /** Replaces any pairing already in progress. */
    fun beginPairing() {
        val s = scope ?: return
        s.launch(dispatchers.default) {
            val old = hubMutex.withLock {
                if (!enabled) return@launch
                // A refused pairing may be paired again in place; a working one is not taken over.
                val exclude = links.value
                    .filter { it.state.value.bonded && it.state.value.phase != WatchLinkPhase.ERROR }
                    .mapNotNull { it.device?.name }
                    .toSet()
                val link = newLink(null)
                val prev = pairingLink.value
                pairingLink.value = link
                link.start(s)
                link.beginPairing(exclude)
                prev
            }
            old?.stop()
        }
    }

    fun cancelPairing() {
        val s = scope ?: return
        s.launch(dispatchers.default) {
            val link = hubMutex.withLock { pairingLink.value.also { pairingLink.value = null } }
            link?.stop()
        }
    }

    /** Null [id] is the pairing in progress; otherwise that pairing's rotation. */
    fun confirmSas(id: String?) {
        val link = if (id == null) pairingLink.value else links.value.firstOrNull { it.device?.id == id }
        link?.takeIf { it.state.value.canConfirmSas }?.confirmSas()
            ?: Timber.tag(TAG).w("confirmSas: %s awaits no code", id ?: "the new pairing")
    }

    /** Null [id] means the only pairing; with several it does nothing. */
    fun rotate(id: String?) = linkFor(id)?.rotate()

    fun unpair(id: String?) = linkFor(id)?.unpair()

    private fun linkFor(id: String?): WatchLink? {
        val ls = links.value
        return if (id == null) ls.singleOrNull() else ls.firstOrNull { it.device?.id == id }
    }

    /** The FGS grid tick: glance, recent history and forecast; stats when due. */
    suspend fun tick(nowMs: Long) = pushAll(nowMs, TICK)

    /** Each measured reading: glance and recent history, so a peripheral keeps up between ticks. */
    suspend fun pushReading(nowMs: Long) = pushAll(nowMs, READING)

    suspend fun pushDisplay(nowMs: Long) = pushAll(nowMs, setOf(WatchRecordKind.DISPLAY))

    suspend fun pushForecast(nowMs: Long) = pushAll(nowMs, setOf(WatchRecordKind.FORECAST))

    private suspend fun pushAll(nowMs: Long, kinds: Set<WatchRecordKind>) {
        if (!enabled) return
        for (link in links.value) {
            runCatching { link.push(nowMs, kinds) }
                .onFailure { Timber.tag(TAG).w(it, "push to %s failed", link.device?.name) }
        }
    }

    /** Takes the hub lock, so a later push sees no links and cannot re-persist a wiped pairing. */
    suspend fun stopForReset() {
        val all = hubMutex.withLock {
            enabled = false
            (links.value + listOfNotNull(pairingLink.value)).also {
                links.value = emptyList()
                pairingLink.value = null
            }
        }
        all.forEach { it.stop() }
    }

    /** Undoes [stopForReset]; reloads whatever pairings the store still holds. */
    suspend fun resumeAfterReset() {
        enabled = true
        load()
    }

    companion object {
        private const val TAG = "WatchLink"

        val TICK: Set<WatchRecordKind> =
            setOf(WatchRecordKind.GLANCE, WatchRecordKind.HISTORY_RECENT, WatchRecordKind.FORECAST)

        val READING: Set<WatchRecordKind> = setOf(WatchRecordKind.GLANCE, WatchRecordKind.HISTORY_RECENT)

        private val RANK = listOf(
            WatchLinkPhase.LIVE,
            WatchLinkPhase.SUSPENDED_LOW_POWER,
            WatchLinkPhase.AWAIT_SAS,
            WatchLinkPhase.HANDSHAKE,
            WatchLinkPhase.DISCOVERING,
            WatchLinkPhase.CONNECTING,
            WatchLinkPhase.SCANNING,
            WatchLinkPhase.RECONNECTING,
            WatchLinkPhase.ERROR,
            WatchLinkPhase.UNPAIRED,
        )
    }
}
