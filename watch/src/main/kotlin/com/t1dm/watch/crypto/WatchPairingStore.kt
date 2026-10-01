package com.t1dm.watch.crypto

/** Durable state, `:app` binds to `kv`, material Keystore-wrapped; [material] null on loopback. */
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
