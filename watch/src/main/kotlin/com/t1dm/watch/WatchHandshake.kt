package com.t1dm.watch

import com.t1dm.watch.crypto.SasCode
import com.t1dm.watch.crypto.WatchSession
import com.t1dm.watch.proto.ControlFrame
import com.t1dm.watch.proto.KexFrame

/** SAS-authenticated X25519: CONFIRM is gated on the human comparison, LIVE only on CONFIRM_ACK. */
object WatchHandshake {

    fun hello(session: WatchSession): KexFrame.Hello =
        KexFrame.Hello(epoch = session.epoch, publicKey = session.startHandshake())

    /** Throws on an epoch mismatch — a reflash or desync mid-handshake — and the caller re-pairs. */
    fun onHelloAck(session: WatchSession, ack: ControlFrame.HelloAck): SasCode {
        require(ack.epoch == session.epoch) { "HELLO_ACK epoch ${ack.epoch} != ${session.epoch}" }
        session.acceptPeer(ack.publicKey)
        return session.sas()
    }

    /** Does NOT promote the keys; that waits for [onConfirmAck]. */
    fun confirm(session: WatchSession): KexFrame.Confirm =
        KexFrame.Confirm(epoch = session.epoch, ok = true)

    /** False on a rejected confirmation: a SAS mismatch at the watch, so re-pair. */
    fun onConfirmAck(session: WatchSession, ack: ControlFrame.ConfirmAck): Boolean {
        if (!ack.ok || ack.epoch != session.epoch) return false
        session.confirm()
        return true
    }
}
