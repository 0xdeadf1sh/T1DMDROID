package com.t1dm.cgm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Recording AidexGattTransport double; tests pump events directly, satisfying the interface. */
class FakeAidexGattTransport : AidexGattTransport {

    override val events: Flow<AidexGattEvent> = MutableSharedFlow(replay = 16, extraBufferCapacity = 64)

    var connectCalled = false
        private set
    var discoverCalled = false
        private set
    var closeCalled = false
        private set

    /** The enable=true calls only, in order. */
    val notifyEnables = mutableListOf<AidexChar>()

    val reads = mutableListOf<AidexChar>()

    val writes = mutableListOf<Pair<AidexChar, ByteArray>>()

    /** Every imperative call reports "could not initiate". */
    var rejectAll = false

    override fun connect() {
        connectCalled = true
    }

    override fun discoverServices(): Boolean {
        discoverCalled = true
        return !rejectAll
    }

    override fun setNotify(char: AidexChar, enable: Boolean): Boolean {
        if (enable) notifyEnables += char
        return !rejectAll
    }

    override fun write(char: AidexChar, value: ByteArray, withResponse: Boolean): Boolean {
        writes += char to value.copyOf()
        return !rejectAll
    }

    /** Refuse this many reads before letting one through; each attempt is still recorded. */
    var rejectNextReads = 0

    override fun read(char: AidexChar): Boolean {
        reads += char
        if (rejectNextReads > 0) {
            rejectNextReads--
            return false
        }
        return !rejectAll
    }

    var rssiReads = 0
        private set

    override fun readRemoteRssi(): Boolean {
        rssiReads++
        return !rejectAll
    }

    override fun close() {
        closeCalled = true
    }

    fun writesTo(char: AidexChar): List<ByteArray> = writes.filter { it.first == char }.map { it.second }

    fun lastWrite(char: AidexChar): ByteArray? = writesTo(char).lastOrNull()
}
