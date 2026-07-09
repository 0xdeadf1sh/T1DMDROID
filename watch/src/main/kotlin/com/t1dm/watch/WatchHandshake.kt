package com.t1dm.watch

import com.t1dm.watch.crypto.SasCode
import com.t1dm.watch.crypto.WatchSession
import com.t1dm.watch.proto.ControlFrame
import com.t1dm.watch.proto.KexFrame

/**
 * The pure protocol transitions of the pairing handshake (task deliverable 2), factored out of the
 * radio so they are unit-testable against a loopback [WatchSession]:
 *
 * ```
 *   phone                         watch
 *   ─────                         ─────
 *   HELLO   (KEX, our pubkey)  ─▶
 *                              ◀─  HELLO_ACK  (CONTROL, watch pubkey)
 *   [acceptPeer → derive keys, both sides compute the SAS]
 *   ── SAS shown on BOTH screens; user compares and confirms ──
 *   CONFIRM (KEX, ok)          ─▶
 *                              ◀─  CONFIRM_ACK (CONTROL)
 *   [session LIVE, epoch-0 keys usable]
 * ```
 *
 * The SAS check is what authenticates the X25519 exchange (an SAS-authenticated key agreement), so
 * CONFIRM is gated on the human comparison — the promotion to LIVE happens only on CONFIRM_ACK.
 */
object WatchHandshake {

    /** Step 1: begin the handshake and build the HELLO to write to KEX. */
    fun hello(session: WatchSession): KexFrame.Hello =
        KexFrame.Hello(epoch = session.epoch, publicKey = session.startHandshake())

    /** Step 2: on HELLO_ACK, ingest the peer key + derive keys, returning the SAS to display. Throws
     *  on an epoch mismatch (a reflash/desync mid-handshake → the caller forces a re-pair). */
    fun onHelloAck(session: WatchSession, ack: ControlFrame.HelloAck): SasCode {
        require(ack.epoch == session.epoch) { "HELLO_ACK epoch ${ack.epoch} != ${session.epoch}" }
        session.acceptPeer(ack.publicKey)
        return session.sas()
    }

    /** Step 3: the user confirmed the SAS — build the CONFIRM to write to KEX. Does NOT yet promote
     *  the keys; that waits for [onConfirmAck]. */
    fun confirm(session: WatchSession): KexFrame.Confirm =
        KexFrame.Confirm(epoch = session.epoch, ok = true)

    /** Step 4: on CONFIRM_ACK, promote the pending keys to LIVE. Returns false on a rejected/failed
     *  confirmation (SAS mismatch reported by the watch → re-pair). */
    fun onConfirmAck(session: WatchSession, ack: ControlFrame.ConfirmAck): Boolean {
        if (!ack.ok || ack.epoch != session.epoch) return false
        session.confirm()
        return true
    }
}
