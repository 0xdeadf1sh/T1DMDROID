package com.t1dm.watch

import com.t1dm.watch.crypto.InMemoryNonceStore
import com.t1dm.watch.crypto.LoopbackWatchSession
import com.t1dm.watch.crypto.LoopbackWatchSessionFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cold-start "burn-the-window" guarantee (risk S6): on resume, the send counter must
 * restart STRICTLY ABOVE any persisted ceiling so no `(key,nonce)` pair can ever repeat across
 * process death / a battery yank.
 */
class NonceBurnTest {

    @Test fun `resume seals strictly above the persisted ceiling`() {
        val ceiling = 1000L
        val session = LoopbackWatchSessionFactory(burnMargin = 256L).resume(material = null, burnedCeiling = ceiling)
                as LoopbackWatchSession
        session.startHandshake()
        session.acceptPeer(ByteArray(32) { 7 })
        session.confirm()
        val seq = session.seal("x".encodeToByteArray()).seq
        assertTrue("first resumed seq ($seq) must exceed the persisted ceiling + margin", seq > ceiling + 256L)
    }

    @Test fun `nonce store ceiling is monotonic`() = runTest {
        val store = InMemoryNonceStore()
        store.recordCeiling(0, 5)
        store.recordCeiling(0, 3) // stale checkpoint must not lower it
        store.recordCeiling(0, 9)
        assertEquals(9L, store.loadCeiling(0))
        store.clear()
        assertEquals(0L, store.loadCeiling(0))
    }
}
