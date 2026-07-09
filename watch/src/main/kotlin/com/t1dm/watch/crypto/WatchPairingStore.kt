package com.t1dm.watch.crypto

/**
 * Durable pairing state: whether a session exists, its epoch, and (for the real uniffi session) the
 * sealed-at-rest key material. On process start [com.t1dm.watch.WatchLink] reads this to decide
 * whether to RESUME an existing pairing (reconnect + push) or sit UNPAIRED awaiting a manual pair.
 * :app binds it to the Room `kv` store (keys wrapped by the Keystore, per progress.md Q6); the
 * in-memory default keeps host tests total. Loopback carries no real keys, so [material] is null
 * there and only the paired/epoch bits are meaningful.
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
