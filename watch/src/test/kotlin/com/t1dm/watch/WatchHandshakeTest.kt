package com.t1dm.watch

import com.t1dm.watch.crypto.LoopbackWatchSession
import com.t1dm.watch.crypto.LoopbackWatchSessionFactory
import com.t1dm.watch.crypto.WatchSessionState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loopback handshake + AEAD contract that the desktop-emulator scaffolding relies on: both
 * sides independently derive the SAME Short Authentication String, and a sealed phone→watch push
 * round-trips through the watch counterpart while tamper/replay fail closed.
 */
class WatchHandshakeTest {

    private fun paired(): Pair<LoopbackWatchSession, LoopbackWatchSession> {
        val phone = LoopbackWatchSessionFactory().fresh() as LoopbackWatchSession
        val watch = LoopbackWatchSessionFactory().fresh() as LoopbackWatchSession
        val pubP = phone.startHandshake()
        val pubW = watch.startHandshake()
        phone.acceptPeer(pubW)
        watch.acceptPeer(pubP)
        return phone to watch
    }

    @Test fun `both sides derive the same SAS`() {
        val (phone, watch) = paired()
        assertEquals(WatchSessionState.AWAIT_SAS, phone.state)
        assertEquals(phone.sas().digits, watch.sas().digits)
        assertEquals(6, phone.sas().digits.length)
    }

    @Test fun `a different peer yields a different SAS`() {
        val (phone, _) = paired()
        val (phone2, _) = paired()
        assertNotEquals(phone.sas().digits, phone2.sas().digits)
    }

    @Test fun `sealed push round-trips through the watch and the seq advances`() {
        val (phone, watch) = paired()
        phone.confirm(); watch.confirm()
        val pt = "falling to ~96".encodeToByteArray()
        val f1 = phone.seal(pt)
        val f2 = phone.seal(pt)
        assertTrue("nonce counter must advance", f2.seq > f1.seq)
        assertArrayEquals(pt, watch.emulatorOpenPush(f1.frame))
        assertArrayEquals(pt, watch.emulatorOpenPush(f2.frame))
    }

    @Test fun `a tampered push fails authentication`() {
        val (phone, watch) = paired()
        phone.confirm(); watch.confirm()
        val f = phone.seal("hello".encodeToByteArray())
        // Flip the last (GCM tag) byte of the authoritative record → tag verification must fail.
        val tampered = f.frame.clone().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { watch.emulatorOpenPush(tampered) }
    }

    @Test fun `seal before confirm is refused`() {
        val (phone, _) = paired()
        assertThrows(IllegalStateException::class.java) { phone.seal(ByteArray(4)) }
    }

    @Test fun `rotate bumps the epoch and forces a new handshake`() {
        val (phone, _) = paired()
        phone.confirm()
        val e0 = phone.epoch
        phone.rotate()
        assertEquals(e0 + 1, phone.epoch)
        assertEquals(WatchSessionState.AWAIT_PEER, phone.state)
    }
}
