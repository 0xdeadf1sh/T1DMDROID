package com.t1dm.watch.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/** Host-test stand-in for Rust WatchSession: SHA-256 keystream/MAC, NOT secure, never shipped. */
class LoopbackWatchSession internal constructor(
    override var epoch: Int,
    private var sendSeq: Long,
) : WatchSession {

    override var state: WatchSessionState = WatchSessionState.UNPAIRED
        private set

    private val rng = SecureRandom()
    private var myPublic: ByteArray = ByteArray(0)
    private var peerPublic: ByteArray? = null
    private var kP2W: ByteArray? = null
    private var kW2P: ByteArray? = null
    private var recvSeq: Long = 0L

    override fun startHandshake(): ByteArray {
        myPublic = ByteArray(32).also(rng::nextBytes)
        peerPublic = null
        kP2W = null
        kW2P = null
        state = WatchSessionState.AWAIT_PEER
        return myPublic.copyOf()
    }

    override fun acceptPeer(peerPublic: ByteArray) {
        require(peerPublic.size == 32) { "watch public key must be 32 bytes, got ${peerPublic.size}" }
        check(state == WatchSessionState.AWAIT_PEER) { "acceptPeer requires AWAIT_PEER, was $state" }
        this.peerPublic = peerPublic.copyOf()
        val shared = sha256(canonical(myPublic, peerPublic) + epochBytes())
        kP2W = sha256(shared + "p2w".toByteArray()).copyOf(16)
        kW2P = sha256(shared + "w2p".toByteArray()).copyOf(16)
        state = WatchSessionState.AWAIT_SAS
    }

    override fun sas(): SasCode {
        check(state == WatchSessionState.AWAIT_SAS) { "sas requires AWAIT_SAS, was $state" }
        val peer = requireNotNull(peerPublic)
        val h = sha256("sas".toByteArray() + canonical(myPublic, peer) + epochBytes())
        // 20 bits → 6 decimal digits.
        val n = ((h[0].toInt() and 0xFF) shl 12) or ((h[1].toInt() and 0xFF) shl 4) or (h[2].toInt() and 0x0F)
        val digits = (n % 1_000_000).toString().padStart(6, '0')
        return SasCode(digits = digits, words = sasWords(digits))
    }

    override fun confirm() {
        check(state == WatchSessionState.AWAIT_SAS) { "confirm requires AWAIT_SAS, was $state" }
        state = WatchSessionState.LIVE
    }

    override fun seal(plaintext: ByteArray): SealedFrame {
        check(state == WatchSessionState.LIVE) { "seal requires a LIVE session, was $state" }
        val key = requireNotNull(kP2W)
        val seq = ++sendSeq
        return SealedFrame(seq = seq, frame = sealRecord(key, DIR_P2W, seq, plaintext))
    }

    override fun open(frame: ByteArray): ByteArray {
        check(state == WatchSessionState.LIVE) { "open requires a LIVE session, was $state" }
        val key = requireNotNull(kW2P)
        val (seq, pt) = openRecord(key, DIR_W2P, frame) { it > recvSeq }
        recvSeq = seq
        return pt
    }

    override fun exportState(): WatchKeyMaterial? = null // the loopback has no durable key material


    private var watchSendSeq: Long = 0L

    /** Watch-side decrypt of a phone→watch push; throws on a bad tag. */
    fun emulatorOpenPush(frame: ByteArray): ByteArray {
        val key = requireNotNull(kP2W) { "no session key" }
        return openRecord(key, DIR_P2W, frame) { true }.second
    }

    /** Watch-side seal of a watch→phone ACK, which the phone [open]s. */
    fun emulatorSealAck(plaintext: ByteArray): SealedFrame {
        val key = requireNotNull(kW2P) { "no session key" }
        val seq = ++watchSendSeq
        return SealedFrame(seq, sealRecord(key, DIR_W2P, seq, plaintext))
    }

    override fun rotate(): ByteArray {
        epoch += 1
        sendSeq = 0L
        recvSeq = 0L
        return startHandshake()
    }

    override fun reset() {
        state = WatchSessionState.UNPAIRED
        myPublic = ByteArray(0)
        peerPublic = null
        kP2W = null
        kW2P = null
        sendSeq = 0L
        recvSeq = 0L
    }

    override fun snapshot(): WatchCryptoSnapshot = WatchCryptoSnapshot(
        state = state,
        epoch = epoch,
        keyFingerprint = kP2W?.let { fingerprint(it) },
        sendSeq = sendSeq,
        recvSeq = recvSeq,
        sas = if (state == WatchSessionState.AWAIT_SAS) sas() else null,
    )

    // Record: ver||epoch||seq||ct||tag (WATCH_BLE.md §6.1); 13-byte header is the AAD; no dir byte.

    private fun authHeader(seq: Long): ByteArray {
        val b = ByteArray(HDR_LEN)
        b[0] = FRAME_VER
        val e = epoch
        b[1] = e.toByte(); b[2] = (e ushr 8).toByte(); b[3] = (e ushr 16).toByte(); b[4] = (e ushr 24).toByte()
        for (i in 0 until 8) b[5 + i] = ((seq ushr (i * 8)) and 0xFF).toByte()
        return b
    }

    private fun sealRecord(key: ByteArray, dir: Byte, seq: Long, plaintext: ByteArray): ByteArray {
        val header = authHeader(seq)
        val ct = xorKeystream(key, dir, seq, plaintext)
        val tag = mac(key, dir, seq, header + ct)
        return header + ct + tag
    }

    /** [seqOk] gates replay. Returns (seq, plaintext). Fails closed. */
    private inline fun openRecord(key: ByteArray, dir: Byte, frame: ByteArray, seqOk: (Long) -> Boolean): Pair<Long, ByteArray> {
        require(frame.size >= HDR_LEN + 16) { "sealed record shorter than header+tag" }
        require(frame[0] == FRAME_VER) { "unknown frame version ${frame[0]}" }
        val fEpoch = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8) or
            ((frame[3].toInt() and 0xFF) shl 16) or ((frame[4].toInt() and 0xFF) shl 24)
        require(fEpoch == epoch) { "epoch mismatch: frame $fEpoch, session $epoch" }
        var seq = 0L
        for (i in 0 until 8) seq = seq or ((frame[5 + i].toLong() and 0xFF) shl (i * 8))
        require(seqOk(seq)) { "replayed/stale seq $seq" }
        val header = frame.copyOf(HDR_LEN)
        val ct = frame.copyOfRange(HDR_LEN, frame.size - 16)
        val tag = frame.copyOfRange(frame.size - 16, frame.size)
        require(mac(key, dir, seq, header + ct).contentEquals(tag)) { "record failed authentication" }
        return seq to xorKeystream(key, dir, seq, ct)
    }


    private fun xorKeystream(key: ByteArray, dir: Byte, seq: Long, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var block = 0
        var i = 0
        while (i < data.size) {
            val ks = sha256(key + byteArrayOf(dir) + epochBytes() + longLe(seq) + intLe(block))
            var j = 0
            while (j < ks.size && i < data.size) {
                out[i] = (data[i].toInt() xor ks[j].toInt()).toByte()
                i++; j++
            }
            block++
        }
        return out
    }

    private fun mac(key: ByteArray, dir: Byte, seq: Long, ct: ByteArray): ByteArray =
        sha256(key + "tag".toByteArray() + byteArrayOf(dir) + epochBytes() + longLe(seq) + ct).copyOf(16)

    private fun epochBytes() = intLe(epoch)

    private fun canonical(a: ByteArray, b: ByteArray): ByteArray =
        if (compareLex(a, b) <= 0) a + b else b + a

    companion object {
        private const val DIR_P2W: Byte = 0x01
        private const val DIR_W2P: Byte = 0x02
        private const val FRAME_VER: Byte = 0x01
        private const val HDR_LEN = 13 // ver(1) + epoch(u32le) + seq(u64le)

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

        private fun intLe(v: Int) =
            byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

        private fun longLe(v: Long) = ByteArray(8) { ((v ushr (it * 8)) and 0xFF).toByte() }

        private fun compareLex(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        internal fun fingerprint(key: ByteArray): String =
            sha256(key).copyOf(4).joinToString(":") { "%02x".format(it) }
    }
}

class LoopbackWatchSessionFactory(private val burnMargin: Long = 256L) : WatchSessionFactory {

    override fun fresh(): WatchSession = LoopbackWatchSession(epoch = 0, sendSeq = 0L)

    override fun resume(material: WatchKeyMaterial?, burnedCeiling: Long): WatchSession {
        // No persisted keys, so a fresh session, but seq still begins above the ceiling + margin.
        val start = if (burnedCeiling > 0) burnedCeiling + burnMargin else 0L
        return LoopbackWatchSession(epoch = 0, sendSeq = start)
    }
}
