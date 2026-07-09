package com.t1dm.watch.proto

import com.t1dm.watch.WatchGatt

/**
 * The handshake + control wire framing (docs/WATCH_BLE.md §Handshake / §Control). Two directions:
 *
 *  - [KexFrame] — phone → watch, written to the KEX characteristic: HELLO, CONFIRM, REKEY, UNPAIR.
 *  - [ControlFrame] — watch → phone, delivered on the CONTROL notify characteristic: HELLO_ACK,
 *    CONFIRM_ACK, PUSH_ACK, and the ERR_* frames that force a re-pair.
 *
 * Every frame is `[u8 type][u8 proto][…body…]`. Frames are NOT AEAD-sealed (the handshake bootstraps
 * the keys; the SAS is the integrity check for the exchange, exactly as in an SAS-authenticated
 * key agreement). Only [WatchPush] glances are sealed.
 */
private const val PROTO = WatchGatt.PROTO_VERSION

/** Phone → watch KEX writes. */
sealed interface KexFrame {
    val type: Int

    /** HELLO: begin/renew the handshake with our 32-byte X25519 public key + the target [epoch]. */
    data class Hello(val epoch: Int, val publicKey: ByteArray) : KexFrame {
        override val type get() = TYPE_HELLO
        override fun equals(other: Any?) =
            other is Hello && epoch == other.epoch && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = 31 * epoch + publicKey.contentHashCode()
    }

    /** CONFIRM: the user confirmed the SAS matches on both screens; promote the pending keys. */
    data class Confirm(val epoch: Int, val ok: Boolean) : KexFrame {
        override val type get() = TYPE_CONFIRM
    }

    /** UNPAIR: wipe the pairing on the watch (mirrors the phone-side [crypto.WatchSession.reset]). */
    data class Unpair(val epoch: Int) : KexFrame {
        override val type get() = TYPE_UNPAIR
    }

    fun encode(): ByteArray = when (this) {
        is Hello -> byteArrayOf(TYPE_HELLO.toByte(), PROTO.toByte(), epoch.toByte()) + publicKey
        is Confirm -> byteArrayOf(TYPE_CONFIRM.toByte(), PROTO.toByte(), epoch.toByte(), if (ok) 1 else 0)
        is Unpair -> byteArrayOf(TYPE_UNPAIR.toByte(), PROTO.toByte(), epoch.toByte())
    }

    companion object {
        const val TYPE_HELLO = 0x01
        const val TYPE_CONFIRM = 0x03
        const val TYPE_UNPAIR = 0x06
    }
}

/** Watch → phone CONTROL notifications. */
sealed interface ControlFrame {
    val type: Int

    /** HELLO_ACK: the watch's 32-byte public key for [epoch]. */
    data class HelloAck(val epoch: Int, val publicKey: ByteArray) : ControlFrame {
        override val type get() = TYPE_HELLO_ACK
        override fun equals(other: Any?) =
            other is HelloAck && epoch == other.epoch && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = 31 * epoch + publicKey.contentHashCode()
    }

    /** CONFIRM_ACK: the watch acknowledges the confirmed SAS; the session is now LIVE on both sides. */
    data class ConfirmAck(val epoch: Int, val ok: Boolean) : ControlFrame {
        override val type get() = TYPE_CONFIRM_ACK
    }

    /** PUSH_ACK (optional): the watch decrypted the sealed push with this seq (liveness + seq audit). */
    data class PushAck(val epoch: Int, val seq: Long) : ControlFrame {
        override val type get() = TYPE_PUSH_ACK
    }

    /**
     * ERR_EPOCH: the watch is on a DIFFERENT epoch than the phone believes — a reflash or a persisted
     * desync. Non-recoverable in place: [com.t1dm.watch.WatchLink] forces a full re-pair.
     */
    data class ErrEpoch(val watchEpoch: Int) : ControlFrame {
        override val type get() = TYPE_ERR_EPOCH
    }

    /** ERR_AUTH: the watch's AEAD open failed (tag/nonce) — treat the keys as compromised/desynced. */
    data class ErrAuth(val epoch: Int) : ControlFrame {
        override val type get() = TYPE_ERR_AUTH
    }

    companion object {
        const val TYPE_HELLO_ACK = 0x02
        const val TYPE_CONFIRM_ACK = 0x04
        const val TYPE_PUSH_ACK = 0x20
        const val TYPE_ERR_EPOCH = 0x10
        const val TYPE_ERR_AUTH = 0x11

        /** Parse a CONTROL notification; null on a short/unknown/wrong-proto frame (fail-closed). */
        fun decode(b: ByteArray): ControlFrame? {
            if (b.size < 2 || (b[1].toInt() and 0xFF) != PROTO) return null
            val epoch = if (b.size >= 3) b[2].toInt() and 0xFF else 0
            return when (b[0].toInt() and 0xFF) {
                TYPE_HELLO_ACK -> if (b.size >= 3 + 32) HelloAck(epoch, b.copyOfRange(3, 3 + 32)) else null
                TYPE_CONFIRM_ACK -> if (b.size >= 4) ConfirmAck(epoch, b[3].toInt() != 0) else null
                TYPE_PUSH_ACK -> if (b.size >= 7) PushAck(epoch, le32(b, 3)) else null
                TYPE_ERR_EPOCH -> ErrEpoch(epoch)
                TYPE_ERR_AUTH -> ErrAuth(epoch)
                else -> null
            }
        }

        private fun le32(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (i * 8))
            return v
        }
    }
}
