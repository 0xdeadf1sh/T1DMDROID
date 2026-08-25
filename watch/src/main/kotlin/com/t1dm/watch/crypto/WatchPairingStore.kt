package com.t1dm.watch.crypto

/**
 * Durable pairing state; :app binds it to the Room `kv` store with the key material wrapped by the
 * Keystore. [Pairing.material] is null on the loopback, where only the paired/epoch bits mean anything.
 */
interface WatchPairingStore {
    suspend fun load(): Pairing?
    suspend fun save(pairing: Pairing)
    suspend fun clear()

    data class Pairing(val epoch: Int, val bonded: Boolean, val material: WatchKeyMaterial?)
}

class InMemoryWatchPairingStore : WatchPairingStore {
    private var pairing: WatchPairingStore.Pairing? = null
    override suspend fun load() = pairing
    override suspend fun save(pairing: WatchPairingStore.Pairing) { this.pairing = pairing }
    override suspend fun clear() { pairing = null }
}
