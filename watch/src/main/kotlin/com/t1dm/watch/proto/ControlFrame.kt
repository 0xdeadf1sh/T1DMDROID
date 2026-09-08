package com.t1dm.watch.proto

import com.t1dm.watch.WatchGatt

/** Handshake/control framing [type][proto][body]; not AEAD-sealed, only WatchPush is. */
private const val PROTO = WatchGatt.PROTO_VERSION

/** Phone -> watch KEX writes. */
sealed interface KexFrame {
    val type: Int

    /** [publicKey] is 32 bytes. */
    data class Hello(val epoch: Int, val publicKey: ByteArray) : KexFrame {
        override val type get() = TYPE_HELLO
        override fun equals(other: Any?) =
            other is Hello && epoch == other.epoch && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = 31 * epoch + publicKey.contentHashCode()
    }

    /** The user confirmed the SAS matches on both screens; promote the pending keys. */
    data class Confirm(val epoch: Int, val ok: Boolean) : KexFrame {
        override val type get() = TYPE_CONFIRM
    }

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

/** Watch -> phone CONTROL notifications. */
sealed interface ControlFrame {
    val type: Int

    data class HelloAck(val epoch: Int, val publicKey: ByteArray) : ControlFrame {
        override val type get() = TYPE_HELLO_ACK
        override fun equals(other: Any?) =
            other is HelloAck && epoch == other.epoch && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = 31 * epoch + publicKey.contentHashCode()
    }

    data class ConfirmAck(val epoch: Int, val ok: Boolean) : ControlFrame {
        override val type get() = TYPE_CONFIRM_ACK
    }

    /** The watch decrypted the sealed push with this seq. Optional. */
    data class PushAck(val epoch: Int, val seq: Long) : ControlFrame {
        override val type get() = TYPE_PUSH_ACK
    }

    /** A reflash or persisted desync; not recoverable in place, WatchLink re-pairs. */
    data class ErrEpoch(val watchEpoch: Int) : ControlFrame {
        override val type get() = TYPE_ERR_EPOCH
    }

    /** The watch's AEAD open failed; treat the keys as compromised or desynced. */
    data class ErrAuth(val epoch: Int) : ControlFrame {
        override val type get() = TYPE_ERR_AUTH
    }

    companion object {
        const val TYPE_HELLO_ACK = 0x02
        const val TYPE_CONFIRM_ACK = 0x04
        const val TYPE_PUSH_ACK = 0x20
        const val TYPE_ERR_EPOCH = 0x10
        const val TYPE_ERR_AUTH = 0x11

        /** Null on a short, unknown, or wrong-proto frame (fail-closed). */
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
