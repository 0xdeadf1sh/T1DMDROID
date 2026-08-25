package com.t1dm.app.watch

import com.t1dm.watch.crypto.SasCode
import com.t1dm.watch.crypto.SealedFrame
import com.t1dm.watch.crypto.WatchCryptoSnapshot
import com.t1dm.watch.crypto.WatchKeyMaterial
import com.t1dm.watch.crypto.WatchSession
import com.t1dm.watch.crypto.WatchSessionFactory
import com.t1dm.watch.crypto.WatchSessionState
import com.t1dm.watch.crypto.sasWords
import java.security.MessageDigest
import uniffi.t1dm_core.WatchSession as RustWatchSession

/**
 * The `:watch` [WatchSession] port over Rust's [RustWatchSession]; it lives in `:app` because
 * `:watch` may not depend on `:core:native`. [acceptPeer] establishes the keys, so the SAS gate to
 * LIVE is phone-side only. Seal/open carry the full record with EMPTY caller AAD (docs/WATCH_BLE.md §6.1).
 */
class UniffiWatchSession internal constructor(
    private val rust: RustWatchSession,
    established: Boolean,
) : WatchSession {

    override var state: WatchSessionState =
        if (established) WatchSessionState.LIVE else WatchSessionState.UNPAIRED
        private set

    /** Display only; Rust's session holds the authoritative counters. */
    private var lastSendSeq: Long = 0L
    private var lastRecvSeq: Long = 0L

    override val epoch: Int
        get() = runCatching { rust.epoch().toInt() }.getOrDefault(0)

    override fun startHandshake(): ByteArray {
        // reset mints a fresh ephemeral X25519 keypair.
        rust.reset()
        state = WatchSessionState.AWAIT_PEER
        return rust.publicKey()
    }

    override fun acceptPeer(peerPublic: ByteArray) {
        rust.acceptPeer(peerPublic) // ECDH + HKDF; the epoch-0 keys exist from here
        state = WatchSessionState.AWAIT_SAS
    }

    override fun sas(): SasCode {
        val digits = rust.sas()
        return SasCode(digits = digits, words = sasWords(digits))
    }

    override fun confirm() {
        check(state == WatchSessionState.AWAIT_SAS) { "confirm requires AWAIT_SAS, was $state" }
        state = WatchSessionState.LIVE
    }

    override fun seal(plaintext: ByteArray): SealedFrame {
        check(state == WatchSessionState.LIVE) { "seal requires a LIVE session, was $state" }
        val frame = rust.seal(plaintext, EMPTY_AAD)
        val seq = seqOf(frame)
        lastSendSeq = seq
        return SealedFrame(seq = seq, frame = frame)
    }

    override fun open(frame: ByteArray): ByteArray {
        check(state == WatchSessionState.LIVE) { "open requires a LIVE session, was $state" }
        val pt = rust.open(frame, EMPTY_AAD)
        lastRecvSeq = seqOf(frame)
        return pt
    }

    override fun rotate(): ByteArray = startHandshake()

    override fun reset() {
        rust.reset()
        state = WatchSessionState.UNPAIRED
        lastSendSeq = 0L
        lastRecvSeq = 0L
    }

    override fun exportState(): WatchKeyMaterial? =
        runCatching { WatchKeyMaterial(rust.exportState()) }.getOrNull()

    override fun snapshot(): WatchCryptoSnapshot = WatchCryptoSnapshot(
        state = state,
        epoch = epoch,
        keyFingerprint = runCatching { fingerprint(rust.publicKey()) }.getOrNull(),
        // Rust's counters survive a restore; the locals only cover the pre-LIVE handshake, where these Err.
        sendSeq = runCatching { rust.sendSeq().toLong() }.getOrDefault(lastSendSeq),
        recvSeq = runCatching { rust.recvMin().toLong() }.getOrDefault(lastRecvSeq),
        sas = if (state == WatchSessionState.AWAIT_SAS) runCatching { sas() }.getOrNull() else null,
    )

    private companion object {
        val EMPTY_AAD = ByteArray(0)

        /** seq is the u64le at offset 5 of the authoritative record (§6.1). */
        fun seqOf(frame: ByteArray): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((frame[5 + i].toLong() and 0xFF) shl (i * 8))
            return v
        }

        fun fingerprint(publicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(4).joinToString(":") { "%02x".format(it) }
    }
}

/** `burnedCeiling` is ignored: Rust's `restore` burns the send-nonce window from the blob itself,
 *  which is the source of truth. An undecodable blob falls back to [fresh] ⇒ a re-pair. */
class UniffiWatchSessionFactory : WatchSessionFactory {
    override fun fresh(): WatchSession = UniffiWatchSession(RustWatchSession(), established = false)

    override fun resume(material: WatchKeyMaterial?, burnedCeiling: Long): WatchSession {
        val bytes = material?.bytes ?: return fresh()
        return runCatching { UniffiWatchSession(RustWatchSession.restore(bytes), established = true) }
            .getOrElse { fresh() }
    }
}
