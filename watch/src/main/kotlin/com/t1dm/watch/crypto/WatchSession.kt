package com.t1dm.watch.crypto

/** X25519→HKDF→AES-128-GCM keys, SAS-confirmed. Nonce monotonic: no (key,nonce) pair repeats. */
interface WatchSession {

    val state: WatchSessionState

    /** Stays 0: [rotate] re-handshakes at epoch 0 rather than advancing it. */
    val epoch: Int

    /** Fresh ephemeral keypair; returns our 32-byte public key for the HELLO body. */
    fun startHandshake(): ByteArray

    /** Peer's 32-byte public key from HELLO_ACK. Derived keys stay pending until [confirm]. */
    fun acceptPeer(peerPublic: ByteArray)

    /** Valid only in [WatchSessionState.AWAIT_SAS]. */
    fun sas(): SasCode

    fun confirm()

    /** Throws unless LIVE. */
    fun seal(plaintext: ByteArray): SealedFrame

    /** [frame] is the whole record. Throws on a bad tag, non-advancing seq, or epoch mismatch. */
    fun open(frame: ByteArray): ByteArray

    /** Full fresh-key re-handshake at epoch 0; old keys retire at once. Returns new HELLO key. */
    fun rotate(): ByteArray

    fun reset()

    /** Reserves and persists a fresh send-nonce window. Null with no durable key material. */
    fun exportState(): WatchKeyMaterial?

    /** Fingerprints and counters, never raw keys. */
    fun snapshot(): WatchCryptoSnapshot
}

enum class WatchSessionState { UNPAIRED, AWAIT_PEER, AWAIT_SAS, LIVE }

/** [frame]: version(1)||epoch:u32le||seq:u64le||ct||16B GCM tag (§6.1); 13B header is AEAD AAD. */
data class SealedFrame(val seq: Long, val frame: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SealedFrame && seq == other.seq && frame.contentEquals(other.frame)

    override fun hashCode(): Int = 31 * seq.hashCode() + frame.contentHashCode()
}

data class SasCode(val digits: String, val words: String)

fun sasWords(digits: String): String {
    val n = digits.toIntOrNull() ?: 0
    return "${SAS_WORDS[(n ushr 8) and 0x0F]}-${SAS_WORDS[(n ushr 4) and 0x0F]}-${SAS_WORDS[n and 0x0F]}"
}

private val SAS_WORDS = listOf(
    "amber", "basil", "cobalt", "delta", "ember", "fjord", "gamma", "harbor",
    "indigo", "jade", "krypton", "lumen", "maple", "nimbus", "onyx", "quartz",
)

data class WatchCryptoSnapshot(
    val state: WatchSessionState,
    val epoch: Int,
    /** Short hex fingerprint of k_p2w; null before keys exist. */
    val keyFingerprint: String?,
    val sendSeq: Long,
    val recvSeq: Long,
    val sas: SasCode?,
)

/** Sealed at rest by the caller's keystore. */
class WatchKeyMaterial(val bytes: ByteArray)

interface WatchSessionFactory {
    fun fresh(): WatchSession

    /** Seeds send counter above [burnedCeiling], so no nonce repeats across process death. */
    fun resume(material: WatchKeyMaterial?, burnedCeiling: Long): WatchSession
}
