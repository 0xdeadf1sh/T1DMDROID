package com.t1dm.watch

import com.t1dm.watch.crypto.SasCode
import com.t1dm.watch.crypto.WatchSessionState

/**
 * The data model the Security/Crypto panel renders (ux-decisions.md — a dedicated section showing
 * BOTH low- and high-level crypto state). :app maps this to a feature-local UI state so nothing in
 * `:feature:security` depends on `:watch` (the removable seam). Everything here is safe to display:
 * a truncated key fingerprint, counters, the SAS, and plain-language status — never raw key bytes.
 */
data class WatchSecurityState(
    val phase: WatchLinkPhase = WatchLinkPhase.UNPAIRED,
    val deviceName: String? = null,
    val bonded: Boolean = false,

    // ── high-level session state ──
    val sessionState: WatchSessionState = WatchSessionState.UNPAIRED,
    val epoch: Int = 0,

    // ── low-level crypto state ──
    /** Truncated hex fingerprint of the phone→watch key (never the key itself). */
    val keyFingerprint: String? = null,
    /** Current windowed send-nonce counter (the seq of the last sealed push). */
    val sendSeq: Long = 0,
    /** Receive-nonce counter (the optional watch→phone ACK path). */
    val recvSeq: Long = 0,
    /** The SAS to compare on both screens while awaiting confirmation; null otherwise. */
    val sas: SasCode? = null,

    // ── push liveness ──
    val lastPushMs: Long? = null,
    val lastAckSeq: Long? = null,
    val lowPowerSuspended: Boolean = false,

    /** Connected-peripheral signal strength in dBm from a periodic `readRemoteRssi` (Phase 7C);
     *  null when disconnected or not yet sampled. Surfaced in the Security panel + BG-panel WCH light. */
    val rssiDbm: Int? = null,

    /** The most recent plain-language error/refusal, if any (human-readable everywhere). */
    val lastError: String? = null,
) {
    /** Which manual actions the panel should enable, given the current phase. */
    val canPair: Boolean get() = phase == WatchLinkPhase.UNPAIRED || phase == WatchLinkPhase.ERROR
    val canConfirmSas: Boolean get() = phase == WatchLinkPhase.AWAIT_SAS
    val canRotate: Boolean get() = phase == WatchLinkPhase.LIVE || phase == WatchLinkPhase.SUSPENDED_LOW_POWER
    val canReset: Boolean get() = phase != WatchLinkPhase.UNPAIRED
}

/**
 * The link lifecycle surfaced to the panel + logs. Distinct from [WatchSessionState] (the crypto
 * side): this tracks the RADIO + orchestration, that tracks the KEY state; the panel shows both.
 */
enum class WatchLinkPhase {
    /** No pairing exists; the "Pair watch" action is available. */
    UNPAIRED,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    /** HELLO sent, awaiting HELLO_ACK. */
    HANDSHAKE,
    /** SAS derived on both sides; awaiting the user's confirmation. */
    AWAIT_SAS,
    /** Paired + connected; pushing every 5 min. */
    LIVE,
    /** Battery-saver active: the 5-min scheduler is suspended (the last frame flagged it). */
    SUSPENDED_LOW_POWER,
    /** Dropped link; reconnect/backoff in progress. */
    RECONNECTING,
    /** A bond-loss / epoch-desync / handshake failure that requires a manual re-pair. */
    ERROR,
}
