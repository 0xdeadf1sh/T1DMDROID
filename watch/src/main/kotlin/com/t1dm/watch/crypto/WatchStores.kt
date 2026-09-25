package com.t1dm.watch.crypto

/** A pairing: [id] is STATUS device_id in 16 hex digits; [address] the last one connected. */
data class WatchDevice(val id: String, val name: String, val address: String?)

interface WatchDeviceStore {
    suspend fun load(): List<WatchDevice>
    suspend fun put(device: WatchDevice)
    suspend fun remove(id: String)
}

/** Per-pairing persistence: keys and nonce ceilings never cross from one device to another. */
interface WatchStores {
    val devices: WatchDeviceStore
    fun pairing(id: String): WatchPairingStore
    fun nonces(id: String): NonceStore
}

class InMemoryWatchStores : WatchStores {
    private val pairings = HashMap<String, WatchPairingStore>()
    private val nonceStores = HashMap<String, NonceStore>()

    override val devices = object : WatchDeviceStore {
        private val list = LinkedHashMap<String, WatchDevice>()
        override suspend fun load() = synchronized(list) { list.values.toList() }
        override suspend fun put(device: WatchDevice) = synchronized(list) { list[device.id] = device }
        override suspend fun remove(id: String) = synchronized(list) { list.remove(id); Unit }
    }

    override fun pairing(id: String) = synchronized(pairings) { pairings.getOrPut(id) { InMemoryWatchPairingStore() } }

    override fun nonces(id: String) = synchronized(nonceStores) { nonceStores.getOrPut(id) { InMemoryNonceStore() } }
}
