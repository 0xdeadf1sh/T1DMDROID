package com.t1dm.watch.crypto

/**
 * The Kotlin-facing surface of the Rust `t1dm-core` watch cryptography (architecture-native-core.md:
 * "watch AES-128-GCM AEAD lives in the Rust core"). :watch speaks THIS port; :app binds the real
 * uniffi-backed `WatchSession` once the Rust `wc_*` module lands, or [LoopbackWatchSession] for the
 * desktop-emulator scaffolding phase. The intended uniffi object exposes
 * `startHandshake / acceptPeer / sas / confirm / seal / open / rotate / reset`; should a signature
 * drift, the integrate phase reconciles here (task note).
 *
 * Security model (watch-link.md, locked): X25519 ECDH on first pair → HKDF-SHA256 → **per-direction**
 * AES-128-GCM keys (k_p2w for phone→watch, k_w2p for the optional ACK path); the derivation is
 * confirmed out-of-band by a Short Authentication String ([sas]) the user compares on both screens.
 * The nonce is a **monotonic windowed counter** namespaced by direction + epoch, so no `(key,nonce)`
 * pair ever repeats — [seal] returns the [SealedFrame.seq] it consumed, and cold-start recovery
 * BURNS the window past any persisted ceiling ([WatchSessionFactory.resume]).
 */
interface WatchSession {

    /** Current session lifecycle, mirrored into the Security panel. */
    val state: WatchSessionState

    /** The current key epoch (0 = first pairing; incremented by [rotate]). */
    val epoch: Int

    /**
     * Begin (or, via [rotate], renew) the handshake: generate a fresh ephemeral X25519 keypair and
     * return OUR 32-byte public key to place in the HELLO body. Moves state to
     * [WatchSessionState.AWAIT_PEER]. Idempotent within one attempt.
     */
    fun startHandshake(): ByteArray

    /**
     * Ingest the peer's 32-byte public key (from HELLO_ACK), run ECDH + HKDF, and derive the
     * per-direction keys for [epoch]. Keys are PENDING — not yet trusted — until [confirm]; state
     * moves to [WatchSessionState.AWAIT_SAS]. Throws on a malformed key.
     */
    fun acceptPeer(peerPublic: ByteArray)

    /**
     * The Short Authentication String to render on BOTH the phone and the watch (a 6+ digit /
     * word code deterministically derived from both public keys + epoch). Valid only in
     * [WatchSessionState.AWAIT_SAS]. If they match, the user confirms on each side.
     */
    fun sas(): SasCode

    /**
     * The user confirmed the SAS matches: promote the pending keys to live. State → [WatchSessionState.LIVE].
     * The seal counter starts from the resumed ceiling (window-burn already applied at construction).
     */
    fun confirm()

    /**
     * AES-128-GCM seal a phone→watch plaintext under the send key with the next windowed nonce.
     * Returns the full self-describing authoritative record ([SealedFrame.frame] =
     * `version || epoch:u32le || seq:u64le || ciphertext || 16-byte GCM tag`, docs/WATCH_BLE.md §6.1)
     * plus the consumed [SealedFrame.seq] (for the persisted ceiling). Only valid in
     * [WatchSessionState.LIVE]; throws otherwise (fail-closed).
     */
    fun seal(plaintext: ByteArray): SealedFrame

    /**
     * AES-128-GCM open a watch→phone authoritative record (the optional ACK path) under the receive
     * key, verifying the tag and that the frame's seq strictly advances the receive watermark (replay
     * guard). [frame] is the full `version || epoch || seq || ct || tag` record. Throws on a tag
     * failure, a stale seq, or an epoch mismatch.
     */
    fun open(frame: ByteArray): ByteArray

    /**
     * Manual key ROTATION (watch-link.md — "manual key rotation must be exposed"): bump [epoch],
     * generate a fresh ephemeral, and return the new HELLO public key. The peer must re-handshake
     * ([acceptPeer]/[confirm]) before the new epoch is LIVE; the old epoch is retired immediately.
     */
    fun rotate(): ByteArray

    /**
     * Manual session RESET / UNPAIR: wipe all key material and counters. State → [WatchSessionState.UNPAIRED].
     */
    fun reset()

    /**
     * Serialize the resumable session and RESERVE + persist a fresh send-nonce window (the real
     * uniffi session's blob carries the epoch root + keys + the burned ceiling, sealed at rest by the
     * caller's keystore). Returns null for sessions with no durable key material (the loopback double).
     * [com.t1dm.watch.WatchLink] persists this via the pairing store after going LIVE and after every
     * push, so a process death resumes strictly above any seq that could have been emitted.
     */
    fun exportState(): WatchKeyMaterial?

    /** An immutable snapshot for the Security panel (fingerprints + counters, never raw keys). */
    fun snapshot(): WatchCryptoSnapshot
}

/** Session lifecycle, surfaced (mapped) to the Security/Crypto panel. */
enum class WatchSessionState { UNPAIRED, AWAIT_PEER, AWAIT_SAS, LIVE }

/**
 * A sealed AEAD record. [frame] is the FULL self-describing authoritative wire record —
 * `version(1) || epoch:u32le || seq:u64le || ciphertext || 16-byte GCM tag` (docs/WATCH_BLE.md §6.1);
 * the 13-byte header is also the AEAD associated data, so version/epoch/seq are authenticated. [seq]
 * is the windowed nonce counter consumed for this frame (surfaced for the persisted ceiling; it is
 * also embedded in [frame]).
 */
data class SealedFrame(val seq: Long, val frame: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SealedFrame && seq == other.seq && frame.contentEquals(other.frame)

    override fun hashCode(): Int = 31 * seq.hashCode() + frame.contentHashCode()
}

/**
 * The Short Authentication String. [digits] is the canonical decimal form shown by default; [words]
 * an optional PGP-style word-pair rendering of the same entropy for easier eyeball comparison.
 */
data class SasCode(val digits: String, val words: String)

/**
 * A tiny closed word list to render a SAS's 6 digits as a friendlier PGP-style triple for eyeball
 * comparison; the [SasCode.digits] remain canonical. Shared so the loopback double and the real
 * uniffi-backed session (which only returns the digits) produce the identical [SasCode.words].
 */
fun sasWords(digits: String): String {
    val n = digits.toIntOrNull() ?: 0
    return "${SAS_WORDS[(n ushr 8) and 0x0F]}-${SAS_WORDS[(n ushr 4) and 0x0F]}-${SAS_WORDS[n and 0x0F]}"
}

private val SAS_WORDS = listOf(
    "amber", "basil", "cobalt", "delta", "ember", "fjord", "gamma", "harbor",
    "indigo", "jade", "krypton", "lumen", "maple", "nimbus", "onyx", "quartz",
)

/**
 * What the Security panel may display: never raw key bytes, only a truncated fingerprint. [sendSeq]
 * and [recvSeq] are the current windowed nonce counters; [rekeyable] gates the ROTATE action.
 */
data class WatchCryptoSnapshot(
    val state: WatchSessionState,
    val epoch: Int,
    /** Short hex fingerprint of k_p2w (e.g. first 8 bytes of SHA-256), or null before keys exist. */
    val keyFingerprint: String?,
    val sendSeq: Long,
    val recvSeq: Long,
    val sas: SasCode?,
)

/** Opaque persisted key material (epoch + keys, sealed at rest by the caller's keystore). */
class WatchKeyMaterial(val bytes: ByteArray)

/**
 * Constructs / resumes a [WatchSession]. :app binds either the uniffi-backed factory or the
 * loopback one. [resume] is the cold-start path: it reloads persisted keys AND seeds the send
 * counter to `max(persistedSeq, burnedCeiling) + 1`, guaranteeing no `(key,nonce)` reuse across
 * process death / a battery yank (risk S6, "burn-the-window").
 */
interface WatchSessionFactory {
    /** A brand-new, UNPAIRED session. */
    fun fresh(): WatchSession

    /** Resume a persisted session, burning the send window past [burnedCeiling]; null material ⇒ [fresh]. */
    fun resume(material: WatchKeyMaterial?, burnedCeiling: Long): WatchSession
}
