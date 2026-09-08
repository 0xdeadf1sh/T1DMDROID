package com.t1dm.watch

import com.t1dm.watch.crypto.SasCode
import com.t1dm.watch.crypto.WatchSessionState

/** Everything here is safe to display: truncated fingerprint, counters, SAS - never key bytes. */
data class WatchSecurityState(
    val phase: WatchLinkPhase = WatchLinkPhase.UNPAIRED,
    val deviceName: String? = null,
    val bonded: Boolean = false,

    val sessionState: WatchSessionState = WatchSessionState.UNPAIRED,
    val epoch: Int = 0,

    /** Truncated hex fingerprint of the phone→watch key, never the key itself. */
    val keyFingerprint: String? = null,
    /** Windowed send-nonce counter: the seq of the last sealed push. */
    val sendSeq: Long = 0,
    /** Receive-nonce counter, on the watch→phone ACK path. */
    val recvSeq: Long = 0,
    /** Null unless awaiting confirmation. */
    val sas: SasCode? = null,

    val lastPushMs: Long? = null,
    val lastAckSeq: Long? = null,
    val lowPowerSuspended: Boolean = false,

    /** dBm; null when disconnected or not yet sampled. */
    val rssiDbm: Int? = null,

    val lastError: String? = null,
) {
    val canPair: Boolean get() = phase == WatchLinkPhase.UNPAIRED || phase == WatchLinkPhase.ERROR
    val canConfirmSas: Boolean get() = phase == WatchLinkPhase.AWAIT_SAS
    val canRotate: Boolean get() = phase == WatchLinkPhase.LIVE || phase == WatchLinkPhase.SUSPENDED_LOW_POWER
    val canReset: Boolean get() = phase != WatchLinkPhase.UNPAIRED
}

/** The radio + orchestration lifecycle; [WatchSessionState] tracks the key state. */
enum class WatchLinkPhase {
    UNPAIRED,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    /** HELLO sent, awaiting HELLO_ACK. */
    HANDSHAKE,
    AWAIT_SAS,
    LIVE,
    SUSPENDED_LOW_POWER,
    RECONNECTING,
    /** Requires a manual re-pair. */
    ERROR,
}
