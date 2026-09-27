package com.t1dm.watch

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.watch.ble.WatchCentral
import com.t1dm.watch.ble.WatchCentralEvent
import com.t1dm.watch.ble.WatchTarget
import com.t1dm.watch.crypto.WatchDevice
import com.t1dm.watch.crypto.WatchPairingStore
import com.t1dm.watch.crypto.WatchSession
import com.t1dm.watch.crypto.WatchSessionFactory
import com.t1dm.watch.crypto.WatchSessionState
import com.t1dm.watch.crypto.WatchStores
import com.t1dm.watch.proto.ControlFrame
import com.t1dm.watch.proto.WatchCodec
import com.t1dm.watch.proto.WatchDeviceStatus
import com.t1dm.watch.proto.WatchRecordKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** One pairing's link, owned by [WatchHub]; crypto on dispatchers.default, GATT on .io. */
class WatchLink internal constructor(
    known: WatchDevice?,
    private val centralProvider: () -> WatchCentral,
    private val sessionFactory: WatchSessionFactory,
    private val stores: WatchStores,
    private val codec: WatchCodec,
    private val glanceSource: WatchGlanceSource,
    private val extendedSource: WatchExtendedSource,
    private val lowPower: LowPowerProvider,
    private val dispatchers: T1dmDispatchers,
    private val listener: Listener,
    @Volatile private var config: WatchLinkConfig,
) {
    internal interface Listener {
        /** Its keys just went live; a no-op when the hub already holds it. */
        suspend fun onPaired(link: WatchLink, device: WatchDevice)

        /** The user unpaired it; the hub drops it. */
        suspend fun onUnpaired(link: WatchLink)
    }

    /** Null only on a pairing link that has not read STATUS yet. */
    @Volatile
    var device: WatchDevice? = known
        private set

    private val _state = MutableStateFlow(
        WatchSecurityState(deviceId = known?.id, deviceName = known?.name, bonded = known != null),
    )
    val state: StateFlow<WatchSecurityState> = _state.asStateFlow()

    private val linkMutex = Mutex()
    private var scope: CoroutineScope? = null
    private var central: WatchCentral? = null
    /** The keys in use; a rotation leaves them live until [handshake] replaces them. */
    private var session: WatchSession? = null
    /** A pairing or rotation between HELLO and CONFIRM_ACK. */
    private var handshake: WatchSession? = null
    /** The one user operation in flight: resume, pairing, rotation, confirm or unpair. */
    private var opJob: Job? = null
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null
    private var rssiJob: Job? = null
    private var pairExclude: Set<String> = emptySet()

    private var helloAck: CompletableDeferred<ControlFrame.HelloAck>? = null
    private var confirmAck: CompletableDeferred<ControlFrame.ConfirmAck>? = null

    /** False until STATUS names this pairing's device: nothing before that may touch its keys. */
    @Volatile
    private var verified = false

    /** Set on every connect and every low-power resume: the next push carries every kind. */
    @Volatile
    private var needsFull = true
    private var lastStatsMs = Long.MIN_VALUE / 2

    /** Hosted from the FGS scope; a pairing link waits for [beginPairing]. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        startRssiPoll(scope)
        val d = device ?: return
        launchOp(scope) {
            val pairing = stores.pairing(d.id).load()
            when {
                !config.enabled -> setPhase(WatchLinkPhase.UNPAIRED)
                pairing?.bonded == true && config.autoConnect -> resumeAndConnect(pairing)
                else -> setPhase(if (pairing?.bonded == true) WatchLinkPhase.RECONNECTING else WatchLinkPhase.UNPAIRED)
            }
        }
    }

    private fun launchOp(s: CoroutineScope, block: suspend CoroutineScope.() -> Unit) {
        opJob?.cancel()
        opJob = s.launch(dispatchers.default, block = block)
    }

    /** RSSI, null when disconnected; on a live link a failed STATUS read is a lost link (§7). */
    private fun startRssiPoll(scope: CoroutineScope) {
        rssiJob?.cancel()
        rssiJob = scope.launch(dispatchers.io) {
            while (true) {
                val c = central?.takeIf { config.enabled && it.isReady }
                val dbm = c?.let { runCatching { it.readRssi() }.getOrNull() }
                if (_state.value.rssiDbm != dbm) _state.update { it.copy(rssiDbm = dbm) }
                if (c != null && _state.value.phase == WatchLinkPhase.LIVE &&
                    runCatching { c.readStatus() }.getOrNull() == null
                ) {
                    lost(c)
                }
                delay(config.pollMs)
            }
        }
    }

    /** A peripheral that re-registered its service keeps the link up and drops every push. */
    private suspend fun lost(c: WatchCentral) {
        linkMutex.withLock { if (central === c) teardown() else return }
        _state.update { it.copy(lastError = "STATUS unreadable") }
        scheduleReconnect()
    }

    /** Teardown for good: the hub drops this link or wipes everything. Keys stay persisted. */
    suspend fun stop() {
        config = config.copy(enabled = false)
        opJob?.cancel()
        reconnectJob?.cancel()
        rssiJob?.cancel()
        linkMutex.withLock {
            teardown()
            session = null
            handshake = null
        }
    }

    /** Connects to an advertiser named by none of [exclude], reads STATUS, starts the handshake. */
    fun beginPairing(exclude: Set<String>) {
        val s = scope ?: return
        pairExclude = exclude
        launchOp(s) {
            attempt(::handshakeFailed, "Pairing failed") {
                val fresh = sessionFactory.fresh().also { handshake = it }
                connectTransport()
                doHandshakeFromHello(fresh)
            }
        }
    }

    fun confirmSas() {
        val s = scope ?: return
        launchOp(s) {
            val hs = handshake ?: return@launchOp Timber.tag(TAG).w("confirmSas: no handshake")
            attempt(::handshakeFailed, "Code not confirmed") {
                val c = central ?: error("not connected")
                val d = CompletableDeferred<ControlFrame.ConfirmAck>().also { confirmAck = it }
                c.writeKex(codec.kex(WatchHandshake.confirm(hs)))
                val ack = withTimeoutOrNull(config.handshakeTimeoutMs) { d.await() }.also { confirmAck = null }
                    ?: error("no answer from the device")
                check(WatchHandshake.onConfirmAck(hs, ack)) { "refused on the device" }
                onSessionLive(hs)
            }
        }
    }

    /** Fresh keys on a separate session; the live one keeps pushing until the swap. */
    fun rotate() {
        val s = scope ?: return
        launchOp(s) {
            if (session?.state != WatchSessionState.LIVE) return@launchOp Timber.tag(TAG).w("rotate: not live")
            attempt(::handshakeFailed, "Rotation failed") {
                val fresh = sessionFactory.fresh().also { handshake = it }
                if (central?.isReady != true) connectTransport()
                doHandshakeFromHello(fresh)
            }
        }
    }

    fun unpair() {
        val s = scope ?: return
        launchOp(s) {
            val d = device
            reconnectJob?.cancel()
            linkMutex.withLock {
                tellUnpair()
                runCatching { session?.reset() }
                session = null
                handshake = null
                d?.let { wipe(it) }
                teardown()
            }
            _state.value = WatchSecurityState(phase = WatchLinkPhase.UNPAIRED, deviceId = d?.id, deviceName = d?.name)
            listener.onUnpaired(this@WatchLink)
        }
    }

    /** Sealed, so no one else can drop the peripheral's keys (§7). Best effort: only when up. */
    private suspend fun tellUnpair() {
        val sess = session ?: return
        val d = device ?: return
        val c = central ?: return
        if (!verified || sess.state != WatchSessionState.LIVE || !c.isReady) return
        try {
            persistSession(d, sess)
            c.writePush(sess.seal(codec.unpair()).frame)
            delay(UNPAIR_FLUSH_MS) // write-without-response: let it leave before the disconnect
        } catch (e: Exception) {
            if (e.isCancellation()) throw e
            Timber.tag(TAG).w(e, "unpair record not sent")
        }
    }

    private suspend fun wipe(d: WatchDevice) {
        runCatching { stores.nonces(d.id).clear() }
        runCatching { stores.pairing(d.id).clear() }
        runCatching { stores.devices.remove(d.id) }
    }

    private suspend fun doHandshakeFromHello(hs: WatchSession) {
        val c = central ?: error("not connected")
        setPhase(WatchLinkPhase.HANDSHAKE)
        val d = CompletableDeferred<ControlFrame.HelloAck>().also { helloAck = it }
        c.writeKex(codec.kex(WatchHandshake.hello(hs)))
        val ack = withTimeoutOrNull(config.handshakeTimeoutMs) { d.await() }.also { helloAck = null }
            ?: error("no answer — open pairing on the device")
        val sas = WatchHandshake.onHelloAck(hs, ack)
        _state.update { it.copy(phase = WatchLinkPhase.AWAIT_SAS, sas = sas) }
    }

    /** A rotation falls back to the live keys; a pairing ends in ERROR. */
    private fun handshakeFailed(reason: String) {
        handshake = null
        helloAck = null
        confirmAck = null
        if (session?.state != WatchSessionState.LIVE) return fail(reason)
        Timber.tag(TAG).w(reason)
        val up = central?.isReady == true
        _state.update {
            it.copy(phase = if (up) WatchLinkPhase.LIVE else WatchLinkPhase.RECONNECTING, sas = null, lastError = reason)
        }
        if (up) pushSoon() else scheduleReconnect()
    }

    private suspend fun onSessionLive(hs: WatchSession) {
        val d = device ?: return fail("No device")
        linkMutex.withLock {
            session = hs
            handshake = null
            // The real session refuses to seal until a send-nonce window is reserved (§4.5).
            persistSession(d, hs)
        }
        _state.update { it.copy(phase = WatchLinkPhase.LIVE, bonded = true, sas = null, lastError = null) }
        syncCrypto()
        listener.onPaired(this, d)
        needsFull = true
        pushSoon()
    }

    /** Reserves and persists a fresh send-nonce window. Loopback double exports null material. */
    private suspend fun persistSession(d: WatchDevice, session: WatchSession) {
        val material = runCatching { session.exportState() }.getOrNull()
        stores.pairing(d.id).save(WatchPairingStore.Pairing(epoch = session.epoch, bonded = true, material = material))
    }

    private fun pushSoon() {
        scope?.launch(dispatchers.default) {
            try {
                push(System.currentTimeMillis(), WatchHub.TICK)
            } catch (e: Exception) {
                if (e.isCancellation()) throw e
                Timber.tag(TAG).w(e, "push failed")
            }
        }
    }

    /** Seals everything [requested] under one checkpointed window, then writes it in order. */
    suspend fun push(nowMs: Long, requested: Set<WatchRecordKind>): Unit = withContext(dispatchers.default) {
        linkMutex.withLock {
            val session = session
            val d = device
            if (!config.enabled || session == null || d == null || session.state != WatchSessionState.LIVE) {
                return@withLock
            }
            if (handshake != null) return@withLock // a rotation owns the phase until it resolves
            val lp = runCatching { lowPower.isLowPower() }.getOrDefault(false)
            val wasSuspended = _state.value.lowPowerSuspended
            if (lp && wasSuspended) return@withLock // already idle

            val c = central
            if (c?.isReady != true) {
                setPhase(WatchLinkPhase.RECONNECTING)
                return@withLock
            }

            val full = needsFull || wasSuspended
            val kinds = when {
                lp -> setOf(WatchRecordKind.GLANCE)
                full -> FULL
                WatchRecordKind.GLANCE in requested && nowMs - lastStatsMs >= config.statsIntervalMs ->
                    requested + WatchRecordKind.STATS
                else -> requested
            }
            val plaintexts = records(nowMs, kinds, lp, _state.value.extended)
            if (plaintexts.isEmpty()) return@withLock

            persistSession(d, session)
            val sealed = plaintexts.map { session.seal(it) }
            // Link may disable while the sources suspend; don't persist into wiped rows.
            if (!config.enabled) return@withLock
            stores.nonces(d.id).recordCeiling(session.epoch, sealed.last().seq)
            for (s in sealed) {
                runCatching { c.writePush(s.frame) }
                    .onFailure { Timber.tag(TAG).w(it, "push write failed"); return@withLock }
            }

            if (full && !lp) needsFull = false
            if (WatchRecordKind.STATS in kinds) lastStatsMs = nowMs
            _state.update {
                it.copy(
                    lastPushMs = nowMs,
                    lowPowerSuspended = lp,
                    phase = if (lp) WatchLinkPhase.SUSPENDED_LOW_POWER else WatchLinkPhase.LIVE,
                )
            }
            syncCrypto()
            if (lp) Timber.tag(TAG).i("low-power: sent final flagged frame seq=%d, suspending pusher", sealed.last().seq)
        }
    }

    /** Display first and glance last, so the peripheral holds the rest when the headline moves. */
    private suspend fun records(
        nowMs: Long,
        kinds: Set<WatchRecordKind>,
        lp: Boolean,
        extended: Boolean,
    ): List<ByteArray> {
        val out = ArrayList<ByteArray>(8)
        suspend fun add(kind: WatchRecordKind, build: suspend () -> List<ByteArray>?) {
            if (kind !in kinds || (kind != WatchRecordKind.GLANCE && !extended)) return
            runCatching { build() }
                .onSuccess { it?.let(out::addAll) }
                .onFailure { Timber.tag(TAG).w(it, "%s record skipped", kind) }
        }
        add(WatchRecordKind.DISPLAY) { extendedSource.display()?.let { listOf(codec.display(it)) } }
        if (WatchRecordKind.HISTORY_DAY in kinds) {
            add(WatchRecordKind.HISTORY_DAY) { extendedSource.history(nowMs, DAY_SLOTS)?.let(codec::history) }
        } else {
            add(WatchRecordKind.HISTORY_RECENT) { extendedSource.history(nowMs, RECENT_SLOTS)?.let(codec::history) }
        }
        add(WatchRecordKind.STATS) { extendedSource.stats()?.let { listOf(codec.stats(it)) } }
        add(WatchRecordKind.FORECAST) { extendedSource.forecast()?.let(codec::forecast) }
        add(WatchRecordKind.GLANCE) {
            glanceSource.currentGlance(nowMs)?.let {
                listOf(codec.glance(it.copy(status = it.status.copy(lowPowerSuspending = lp))))
            }
        }
        return out
    }

    private suspend fun resumeAndConnect(pairing: WatchPairingStore.Pairing) {
        val d = device ?: return
        val ceiling = stores.nonces(d.id).loadCeiling(pairing.epoch)
        val session = sessionFactory.resume(pairing.material, ceiling).also { this.session = it }
        _state.update { it.copy(bonded = true, epoch = pairing.epoch) }
        // Restore burned the send window; reserve fresh now, no key/nonce reuse (§4.5).
        if (session.state == WatchSessionState.LIVE) persistSession(d, session)
        try {
            connectTransport()
            if (session.state == WatchSessionState.LIVE) {
                onReconnected()
            } else {
                fail("Session could not be resumed from persisted keys — re-pair required")
            }
        } catch (e: Exception) {
            if (e.isCancellation()) throw e
            _state.update { it.copy(lastError = e.message) }
            scheduleReconnect()
        }
    }

    private fun onReconnected() {
        _state.update { it.copy(phase = WatchLinkPhase.LIVE) }
        syncCrypto()
        needsFull = true
        pushSoon()
    }

    /** Connects and reads STATUS; another device's STATUS disconnects it, keys untouched. */
    private suspend fun connectTransport(): WatchDeviceStatus {
        teardown()
        val c = centralProvider().also { central = it }
        // Undispatched: the hot flow is subscribed before connect, so no early CONTROL is dropped.
        eventJob = scope!!.launch(dispatchers.default, start = CoroutineStart.UNDISPATCHED) { collectEvents(c) }
        setPhase(WatchLinkPhase.CONNECTING)
        val known = device
        val target = if (known == null) {
            WatchTarget.New(WatchGatt.ADV_NAME_PREFIX, pairExclude)
        } else {
            WatchTarget.Known(known.name, known.address)
        }
        val conn = try {
            c.connect(target, config.connectTimeoutMs)
        } catch (e: Throwable) {
            runCatching { c.disconnect() }
            throw e
        }
        if (central !== c || !config.enabled) {
            runCatching { c.disconnect() }
            throw CancellationException("link stopped while connecting")
        }
        val status = c.readStatus()?.let(codec::status)
        if (status == null) {
            teardown()
            error("${conn.deviceName} sent no readable STATUS")
        }
        // A name derives from its id (§1); a mismatch is not the device that advertised.
        if (known == null && (status.name != conn.deviceName || status.name in pairExclude)) {
            teardown()
            error("${conn.deviceName} reports ${status.name}")
        }
        if (known != null && status.deviceId != known.id) {
            teardown()
            // Someone else answered at the cached address: forget the address, keep the keys.
            device = known.copy(address = null).also { stores.devices.put(it) }
            error("${conn.deviceName} is not ${known.name}")
        }
        val live = session
        if (live?.state == WatchSessionState.LIVE && status.epoch != live.epoch) {
            teardown()
            error("${conn.deviceName} at epoch ${status.epoch}, not ${live.epoch} — pair again")
        }
        val now = (known ?: WatchDevice(status.deviceId, status.name, null)).copy(address = conn.address)
        device = now
        if (known != null && known.address != conn.address) stores.devices.put(now)
        verified = true
        _state.update {
            it.copy(
                deviceId = now.id,
                deviceName = now.name,
                extended = status.extended && conn.mtu >= WatchGatt.MTU_TARGET,
            )
        }
        return status
    }

    private suspend fun collectEvents(c: WatchCentral) {
        c.events.collect { ev ->
            when (ev) {
                is WatchCentralEvent.Notified -> routeControl(ev.bytes)
                is WatchCentralEvent.Disconnected -> {
                    _state.update { it.copy(lastError = ev.reason) }
                    scheduleReconnect()
                }
            }
        }
    }

    private fun routeControl(bytes: ByteArray) {
        val settled = verified && handshake == null && session?.state == WatchSessionState.LIVE
        when (val f = codec.control(bytes)) {
            is ControlFrame.HelloAck -> helloAck?.complete(f)
            is ControlFrame.ConfirmAck -> confirmAck?.complete(f)
            is ControlFrame.PushAck -> _state.update { it.copy(lastAckSeq = f.seq) }
            is ControlFrame.ErrEpoch -> if (settled) rejected("Epoch ${f.watchEpoch} refused — pair again")
            is ControlFrame.ErrAuth -> if (settled) rejected("Keys refused — pair again")
            null -> Timber.tag(TAG).w("unparseable control frame (%d bytes)", bytes.size)
        }
    }

    /** ERR frames are unauthenticated (§7): drop the connection, keep the keys, retry slowly. */
    private fun rejected(reason: String) {
        val s = scope ?: return
        Timber.tag(TAG).w("rejected: %s", reason)
        s.launch(dispatchers.default) {
            linkMutex.withLock { teardown() }
            _state.update { it.copy(phase = WatchLinkPhase.ERROR, lastError = reason) }
            scheduleReconnect(config.backoffMaxMs)
        }
    }

    private fun scheduleReconnect(firstDelayMs: Long = config.backoffInitialMs) {
        if (!config.enabled || !_state.value.bonded) return
        if (reconnectJob?.isActive == true) return
        val s = scope ?: return
        reconnectJob = s.launch(dispatchers.default) {
            if (_state.value.phase != WatchLinkPhase.ERROR) setPhase(WatchLinkPhase.RECONNECTING)
            var backoff = firstDelayMs
            while (config.enabled && _state.value.bonded) {
                delay(backoff)
                val ok = try {
                    connectTransport()
                    true
                } catch (e: Exception) {
                    if (e.isCancellation()) throw e
                    _state.update { it.copy(lastError = e.message) }
                    false
                }
                if (ok && central?.isReady == true) {
                    if (session?.state == WatchSessionState.LIVE) onReconnected()
                    return@launch
                }
                backoff = (backoff * 2).coerceIn(config.backoffInitialMs, config.backoffMaxMs)
            }
        }
    }

    private fun teardown() {
        verified = false
        eventJob?.cancel(); eventJob = null
        runCatching { central?.disconnect() }
        central = null
    }

    private fun setPhase(phase: WatchLinkPhase) = _state.update { it.copy(phase = phase) }

    /** Nothing retries from ERROR, so the connection would only hold the peripheral. */
    private fun fail(reason: String) {
        Timber.tag(TAG).w(reason)
        teardown()
        _state.update { it.copy(phase = WatchLinkPhase.ERROR, lastError = reason) }
    }

    private fun syncCrypto() {
        val snap = session?.snapshot() ?: return
        _state.update {
            it.copy(
                sessionState = snap.state,
                epoch = snap.epoch,
                keyFingerprint = snap.keyFingerprint,
                sendSeq = snap.sendSeq,
                recvSeq = snap.recvSeq,
                sas = snap.sas ?: it.sas,
            )
        }
    }

    private companion object {
        const val TAG = "WatchLink"
        const val UNPAIR_FLUSH_MS = 500L
        const val RECENT_SLOTS = 12
        const val DAY_SLOTS = 288
        val FULL: Set<WatchRecordKind> = WatchRecordKind.entries.toSet() - WatchRecordKind.HISTORY_RECENT

        /** Runs [block]; any failure but cancellation goes to [onFail] as "[what]: reason". */
        inline fun attempt(onFail: (String) -> Unit, what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (e.isCancellation()) throw e
                onFail("$what: ${e.message}")
            }
        }

        /** A timeout is a failure like any other; only a cancelled job propagates. */
        fun Throwable.isCancellation() = this is CancellationException && this !is TimeoutCancellationException
    }
}
