package com.t1dm.watch.proto

/** SPEC/watch.md §3, §6. Bytes come and go through [WatchCodec] only. */
sealed interface KexFrame {
    /** [publicKey] is 32 bytes. */
    data class Hello(val epoch: Int, val publicKey: ByteArray) : KexFrame {
        override fun equals(other: Any?) =
            other is Hello && epoch == other.epoch && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = 31 * epoch + publicKey.contentHashCode()
    }

    /** The user confirmed the SAS matches on both screens; promote the pending keys. */
    data class Confirm(val epoch: Int, val ok: Boolean) : KexFrame
}

/** Peripheral → phone. Unauthenticated: none of these may touch stored keys. */
sealed interface ControlFrame {
    data class HelloAck(val epoch: Int, val publicKey: ByteArray) : ControlFrame {
        override fun equals(other: Any?) =
            other is HelloAck && epoch == other.epoch && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = 31 * epoch + publicKey.contentHashCode()
    }

    data class ConfirmAck(val epoch: Int, val ok: Boolean) : ControlFrame

    /** The peripheral opened the sealed push with this seq. Optional. */
    data class PushAck(val epoch: Int, val seq: Long) : ControlFrame

    data class ErrEpoch(val watchEpoch: Int) : ControlFrame

    data class ErrAuth(val epoch: Int) : ControlFrame
}
