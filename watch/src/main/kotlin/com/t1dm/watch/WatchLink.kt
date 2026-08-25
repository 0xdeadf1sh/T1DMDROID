package com.t1dm.watch

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.watch.ble.WatchCentral
import com.t1dm.watch.ble.WatchCentralEvent
import com.t1dm.watch.crypto.NonceStore
import com.t1dm.watch.crypto.WatchPairingStore
import com.t1dm.watch.crypto.WatchSession
import com.t1dm.watch.crypto.WatchSessionFactory
import com.t1dm.watch.crypto.WatchSessionState
import com.t1dm.watch.proto.ControlFrame
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchPushCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** Runs on [T1dmDispatchers.default] (crypto/seal) and `.io` (GATT), never main. Dormant unless
 *  [WatchLinkConfig.enabled]. */
class WatchLink(
    private val centralProvider: () -> WatchCentral,
    private val sessionFactory: WatchSessionFactory,
    private val nonceStore: NonceStore,
    private val pairingStore: WatchPairingStore,
    private val glanceSource: WatchGlanceSource,
    private val lowPower: LowPowerProvider,
    private val dispatchers: T1dmDispatchers,
    @Volatile private var config: WatchLinkConfig = WatchLinkConfig(),
) {
    private val _state = MutableStateFlow(WatchSecurityState())
    val state: StateFlow<WatchSecurityState> = _state.asStateFlow()

    private val linkMutex = Mutex()
    private var scope: CoroutineScope? = null
    private var central: WatchCentral? = null
    private var session: WatchSession? = null
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null
    private var rssiJob: Job? = null

    private var helloAck: CompletableDeferred<ControlFrame.HelloAck>? = null
    private var confirmAck: CompletableDeferred<ControlFrame.ConfirmAck>? = null
    private var ready: CompletableDeferred<Unit>? = null

    /** Hosted from the FGS scope. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch(dispatchers.default) {
            val pairing = pairingStore.load()
            when {
                !config.enabled -> setPhase(WatchLinkPhase.UNPAIRED)
                pairing?.bonded == true && config.autoConnect -> resumeAndConnect(pairing)
                else -> setPhase(if (pairing?.bonded == true) WatchLinkPhase.RECONNECTING else WatchLinkPhase.UNPAIRED)
            }
        }
        startRssiPoll(scope)
    }

    /** Clears the reading to null when disconnected; a failed read is swallowed. */
    private fun startRssiPoll(scope: CoroutineScope) {
        rssiJob?.cancel()
        rssiJob = scope.launch(dispatchers.io) {
            while (true) {
                val dbm = if (config.enabled && central?.isReady == true) {
                    runCatching { central?.readRssi() }.getOrNull()
                } else null
                if (_state.value.rssiDbm != dbm) _state.update { it.copy(rssiDbm = dbm) }
                delay(RSSI_POLL_MS)
            }
        }
    }

    fun setConfig(newConfig: WatchLinkConfig) { config = newConfig }

    /** Undoes [stopForReset]: `enabled = false` is teardown state, not a preference, and nothing
     *  else clears it. Deliberately does NOT re-pair — the reset erased the key material. */
    suspend fun resumeAfterReset() {
        linkMutex.withLock {
            config = config.copy(enabled = true)
            scope?.let { startRssiPoll(it) }
        }
    }

    /** Takes [linkMutex] so it serialises with an in-flight [pushNow]: a push starting later finds
     *  the link disabled and returns before persisting, so it cannot re-persist key material or a
     *  nonce ceiling into a store the reset is wiping. Does not touch the stores. Idempotent. */
    suspend fun stopForReset() {
        linkMutex.withLock {
            config = config.copy(enabled = false)
            rssiJob?.cancel()
            eventJob?.cancel()
            reconnectJob?.cancel()
            runCatching { central?.disconnect() }
            central = null
            session = null
            _state.value = WatchSecurityState()
        }
    }


    fun beginPairing() {
        val s = scope ?: return
        s.launch(dispatchers.default) {
            runCatching {
                val session = sessionFactory.fresh().also { this@WatchLink.session = it }
                connectTransport()
                doHandshake(session)
            }.onFailure { fail("Pairing failed: ${it.message}") }
        }
    }

    fun confirmSas() {
        val s = scope ?: return
        s.launch(dispatchers.default) {
            val session = session ?: return@launch fail("No session to confirm")
            runCatching {
                val c = central ?: error("not connected")
                val d = CompletableDeferred<ControlFrame.ConfirmAck>().also { confirmAck = it }
                c.writeKex(WatchHandshake.confirm(session).encode())
                val ack = withTimeout(config.handshakeTimeoutMs) { d.await() }.also { confirmAck = null }
                check(WatchHandshake.onConfirmAck(session, ack)) { "watch rejected the SAS" }
                onSessionLive()
            }.onFailure { fail("SAS confirm failed: ${it.message}") }
        }
    }

    fun rotate() {
        val s = scope ?: return
        s.launch(dispatchers.default) {
            val session = session ?: return@launch fail("No session to rotate")
            runCatching {
                session.rotate()
                nonceStore.recordCeiling(session.epoch, 0) // fresh epoch, fresh window
                if (central?.isReady != true) connectTransport()
                doHandshakeFromHello(session)
            }.onFailure { fail("Rotation failed: ${it.message}") }
        }
    }

    fun unpair() {
        val s = scope ?: return
        s.launch(dispatchers.default) {
            runCatching {
                session?.let { sess ->
                    runCatching { central?.writeKex(com.t1dm.watch.proto.KexFrame.Unpair(sess.epoch).encode()) }
                    sess.reset()
                }
                nonceStore.clear()
                pairingStore.clear()
            }
            teardown()
            session = null
            _state.value = WatchSecurityState(phase = WatchLinkPhase.UNPAIRED)
        }
    }

    private suspend fun doHandshake(session: WatchSession) {
        doHandshakeFromHello(session)
    }

    private suspend fun doHandshakeFromHello(session: WatchSession) {
        val c = central ?: error("not connected")
        setPhase(WatchLinkPhase.HANDSHAKE)
        val d = CompletableDeferred<ControlFrame.HelloAck>().also { helloAck = it }
        c.writeKex(WatchHandshake.hello(session).encode())
        val ack = withTimeout(config.handshakeTimeoutMs) { d.await() }.also { helloAck = null }
        val sas = WatchHandshake.onHelloAck(session, ack)
        _state.update { it.copy(phase = WatchLinkPhase.AWAIT_SAS, sas = sas, sessionState = session.state) }
        syncCrypto()
    }

    private suspend fun onSessionLive() {
        val session = session ?: return
        // The real session refuses to seal until a send-nonce window is reserved (§5.5).
        persistSession(session)
        _state.update { it.copy(phase = WatchLinkPhase.LIVE, bonded = true, sas = null, lastError = null) }
        syncCrypto()
    }

    /** Reserves and persists a fresh send-nonce window. The loopback double exports null material. */
    private suspend fun persistSession(session: WatchSession) {
        val material = runCatching { session.exportState() }.getOrNull()
        pairingStore.save(WatchPairingStore.Pairing(epoch = session.epoch, bonded = true, material = material))
    }


    /**
     * Checkpoints the nonce ceiling BEFORE the write, so a crash mid-write cannot re-issue the seq.
     * In low power it sends one final `lowPowerSuspending` frame, then idles. Off-main.
     */
    suspend fun pushNow(nowMs: Long): Unit = withContext(dispatchers.default) {
        linkMutex.withLock {
            val session = session
            if (!config.enabled || session == null || session.state != WatchSessionState.LIVE) return@withLock
            val lp = runCatching { lowPower.isLowPower() }.getOrDefault(false)
            if (lp && _state.value.lowPowerSuspended) return@withLock // already suspended; stay idle

            val c = central
            if (c?.isReady != true) {
                setPhase(WatchLinkPhase.RECONNECTING)
                return@withLock
            }

            val glance = runCatching { glanceSource.currentGlance(nowMs) }.getOrNull() ?: return@withLock
            val push = glance.copy(status = glance.status.copy(lowPowerSuspending = lp))
            val sealed = session.seal(WatchPushCodec.encode(push))
            // The link may have been disabled while we were suspended in the currentGlance() read; do
            // not persist into kv rows a reset is wiping. The burnMargin resume floor covers this seq.
            if (!config.enabled) return@withLock
            nonceStore.recordCeiling(session.epoch, sealed.seq)
            persistSession(session)
            runCatching { c.writePush(WatchPushCodec.wireFrame(sealed)) }
                .onFailure { Timber.tag(TAG).w(it, "push write failed"); return@withLock }

            _state.update {
                it.copy(
                    lastPushMs = nowMs,
                    lowPowerSuspended = lp,
                    phase = if (lp) WatchLinkPhase.SUSPENDED_LOW_POWER else WatchLinkPhase.LIVE,
                )
            }
            syncCrypto()
            if (lp) Timber.tag(TAG).i("low-power: sent final flagged frame seq=%d, suspending pusher", sealed.seq)
        }
    }


    private suspend fun resumeAndConnect(pairing: WatchPairingStore.Pairing) {
        val ceiling = nonceStore.loadCeiling(pairing.epoch)
        val session = sessionFactory.resume(pairing.material, ceiling).also { this.session = it }
        _state.update { it.copy(bonded = true, epoch = pairing.epoch) }
        // The restore burned the send window; reserve a fresh one now, independently of transport
        // success, so the first push can seal without reusing a (key, nonce) (§5.5). The only
        // reservation on this path.
        if (session.state == WatchSessionState.LIVE) persistSession(session)
        runCatching {
            connectTransport()
            if (session.state == WatchSessionState.LIVE) {
                _state.update { it.copy(phase = WatchLinkPhase.LIVE) }
                syncCrypto()
            } else {
                fail("Session could not be resumed from persisted keys — re-pair required")
            }
        }.onFailure { scheduleReconnect() }
    }

    private suspend fun connectTransport() {
        teardown()
        val c = centralProvider().also { central = it }
        ready = CompletableDeferred()
        eventJob = scope!!.launch(dispatchers.default) { collectEvents(c) }
        setPhase(WatchLinkPhase.CONNECTING)
        withContext(dispatchers.io) { c.connectByName(WatchGatt.ADV_NAME_PREFIX, config.connectTimeoutMs) }
        withTimeout(config.connectTimeoutMs) { ready!!.await() }
        runCatching { c.readStatus() }.getOrNull()?.let { detectEpochDesync(it) }
    }

    private suspend fun collectEvents(c: WatchCentral) {
        c.events.collect { ev ->
            when (ev) {
                is WatchCentralEvent.Ready -> {
                    _state.update { it.copy(deviceName = ev.deviceName) }
                    ready?.complete(Unit)
                }
                is WatchCentralEvent.Notified -> routeControl(ev.bytes)
                is WatchCentralEvent.Disconnected -> {
                    _state.update { it.copy(lastError = ev.reason) }
                    scheduleReconnect()
                }
                is WatchCentralEvent.Failed -> {
                    ready?.completeExceptionally(IllegalStateException(ev.reason))
                    fail(ev.reason)
                }
            }
        }
    }

    private fun routeControl(bytes: ByteArray) {
        when (val f = ControlFrame.decode(bytes)) {
            is ControlFrame.HelloAck -> helloAck?.complete(f)
            is ControlFrame.ConfirmAck -> confirmAck?.complete(f)
            is ControlFrame.PushAck -> _state.update { it.copy(lastAckSeq = f.seq) }
            is ControlFrame.ErrEpoch -> forceRepair("Watch epoch ${f.watchEpoch} desynced (reflash?) — re-pair required")
            is ControlFrame.ErrAuth -> forceRepair("Watch could not authenticate the link — re-pair required")
            null -> Timber.tag(TAG).w("unparseable control frame (%d bytes)", bytes.size)
        }
    }

    private fun detectEpochDesync(status: ByteArray) {
        // STATUS: [u8 proto][u8 epoch][…]
        val sess = session ?: return
        if (status.size >= 2) {
            val watchEpoch = status[1].toInt() and 0xFF
            if (sess.state == WatchSessionState.LIVE && watchEpoch != sess.epoch) {
                forceRepair("Watch epoch $watchEpoch != phone ${sess.epoch} (reflash?) — re-pair required")
            }
        }
    }

    private fun forceRepair(reason: String) {
        Timber.tag(TAG).w("force re-pair: %s", reason)
        scope?.launch(dispatchers.default) {
            session?.reset()
            pairingStore.clear()
            nonceStore.clear()
            teardown()
            session = null
            _state.value = WatchSecurityState(phase = WatchLinkPhase.ERROR, lastError = reason)
        }
    }

    private fun scheduleReconnect() {
        if (!config.enabled || _state.value.bonded.not()) { setPhase(WatchLinkPhase.UNPAIRED); return }
        if (reconnectJob?.isActive == true) return
        val s = scope ?: return
        reconnectJob = s.launch(dispatchers.default) {
            setPhase(WatchLinkPhase.RECONNECTING)
            var backoff = config.backoffInitialMs
            while (config.enabled && _state.value.bonded) {
                delay(backoff)
                val ok = runCatching { connectTransport() }.isSuccess
                if (ok && central?.isReady == true) {
                    if (session?.state == WatchSessionState.LIVE) setPhase(WatchLinkPhase.LIVE)
                    return@launch
                }
                backoff = (backoff * 2).coerceAtMost(config.backoffMaxMs)
            }
        }
    }

    private fun teardown() {
        eventJob?.cancel(); eventJob = null
        runCatching { central?.disconnect() }
        central = null
    }


    private fun setPhase(phase: WatchLinkPhase) = _state.update { it.copy(phase = phase) }

    private fun fail(reason: String) {
        Timber.tag(TAG).w(reason)
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

    companion object {
        private const val TAG = "WatchLink"
        private const val RSSI_POLL_MS = 15_000L
    }
}
