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
 * The SHIPPING watch crypto session: the authoritative X25519 → HKDF-SHA256 → per-direction
 * AES-128-GCM [RustWatchSession] (t1dm-core, uniffi) adapted to the `:watch` [WatchSession] port.
 * This lives in `:app` — the composition root — precisely because `:watch` is a clean removable seam
 * that may not depend on `:core:native`; the module ships a loopback double for its pure-JVM tests,
 * and `:app` binds THIS in its place (retiring the loopback from every shipped path).
 *
 * The Rust session establishes its keys the moment [acceptPeer] runs (there is no separate "confirm"
 * in the cipher); the SAS-gated promotion to LIVE is a phone-side UX concern, so this adapter tracks
 * the {UNPAIRED, AWAIT_PEER, AWAIT_SAS, LIVE} lifecycle locally while delegating every byte on the
 * wire to Rust. Seal/open exchange the FULL authoritative record (`ver||epoch||seq||ct||tag`,
 * docs/WATCH_BLE.md §6.1) with EMPTY caller AAD (§6.1); the 13-byte header is the AEAD AAD internally.
 */
class UniffiWatchSession internal constructor(
    private val rust: RustWatchSession,
    established: Boolean,
) : WatchSession {

    override var state: WatchSessionState =
        if (established) WatchSessionState.LIVE else WatchSessionState.UNPAIRED
        private set

    /** Surfaced for the panel only; the authoritative counters live inside Rust's session. */
    private var lastSendSeq: Long = 0L
    private var lastRecvSeq: Long = 0L

    override val epoch: Int
        get() = runCatching { rust.epoch().toInt() }.getOrDefault(0)

    override fun startHandshake(): ByteArray {
        // Mint a fresh ephemeral X25519 keypair (reset returns the session to the Handshake phase).
        rust.reset()
        state = WatchSessionState.AWAIT_PEER
        return rust.publicKey()
    }

    override fun acceptPeer(peerPublic: ByteArray) {
        rust.acceptPeer(peerPublic) // runs ECDH + HKDF, establishes epoch-0 keys (still SAS-pending)
        state = WatchSessionState.AWAIT_SAS
    }

    override fun sas(): SasCode {
        val digits = rust.sas()
        return SasCode(digits = digits, words = sasWords(digits))
    }

    override fun confirm() {
        check(state == WatchSessionState.AWAIT_SAS) { "confirm requires AWAIT_SAS, was $state" }
        state = WatchSessionState.LIVE // keys were already derived by acceptPeer; the SAS gates trust
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

    /** Manual rotation is exposed as a fresh SAS-authenticated re-handshake (a new ephemeral keypair);
     *  [com.t1dm.watch.WatchLink] drives the HELLO/HELLO_ACK/CONFIRM that follow. */
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
        // The authoritative counters live in Rust and survive a restore (send_seq resumes at the burned
        // ceiling, not 0); the local vars only cover the pre-LIVE handshake, where the getters Err.
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

        /** A short, displayable identifier for the pairing — the first 4 bytes of SHA-256(our public
         *  key), never any secret key material. */
        fun fingerprint(publicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(4).joinToString(":") { "%02x".format(it) }
    }
}

/**
 * Builds the authoritative uniffi-backed [UniffiWatchSession]. [resume] restores the durable blob the
 * pairing store kept — Rust's `restore` BURNS the send-nonce window from the blob's own persisted
 * ceiling, so the [WatchSessionFactory.resume] `burnedCeiling` hint is redundant here (the blob is the
 * source of truth). A missing/undecodable blob falls back to a [fresh] session ⇒ a re-pair.
 */
class UniffiWatchSessionFactory : WatchSessionFactory {
    override fun fresh(): WatchSession = UniffiWatchSession(RustWatchSession(), established = false)

    override fun resume(material: WatchKeyMaterial?, burnedCeiling: Long): WatchSession {
        val bytes = material?.bytes ?: return fresh()
        return runCatching { UniffiWatchSession(RustWatchSession.restore(bytes), established = true) }
            .getOrElse { fresh() }
    }
}
