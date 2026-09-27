package com.t1dm.sync.nightscout

import com.t1dm.sync.TokenStore
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NightscoutConfigStoreTest {

    /** A restored blob whose Keystore key stayed on the old phone: unwrap fails until re-saved. */
    private class RestoredTokenStore : TokenStore {
        private val map = HashMap<String, String>()
        private val unreadable = hashSetOf(NightscoutKeys.SECRET_ID)
        override suspend fun get(profileId: String): String? {
            if (profileId in unreadable) throw AEADBadTagException("Tag mismatch")
            return map[profileId]
        }
        override suspend fun put(profileId: String, token: String) {
            unreadable.remove(profileId)
            map[profileId] = token
        }
        override suspend fun remove(profileId: String) {
            unreadable.remove(profileId)
            map.remove(profileId)
        }
        override suspend fun clearAll() {
            unreadable.clear()
            map.clear()
        }
    }

    private fun store(tokens: TokenStore): NightscoutConfigStore {
        val kv = HashMap<String, String>()
        kv[NightscoutKeys.URL] = "https://ns.example"
        kv[NightscoutKeys.ENABLED] = "1"
        return NightscoutConfigStore(
            getKv = { kv[it] },
            putKv = { k, v, _ -> kv[k] = v },
            tokens = tokens,
        )
    }

    @Test
    fun `an undecryptable secret reads as absent, not a crash`() = runTest {
        val s = store(RestoredTokenStore())
        assertNull(s.current())
        assertFalse(s.hasSecret())
    }

    @Test
    fun `a new secret overwrites the undecryptable one`() = runTest {
        val s = store(RestoredTokenStore())
        s.save("https://ns.example", "hunter2hunter2", enabled = true, nowMs = 0L)
        assertEquals(sha1Hex("hunter2hunter2"), s.current()?.secretSha1)
    }
}
