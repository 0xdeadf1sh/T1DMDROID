package com.t1dm.cgm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Recording Ct5GattTransport double; tests pump events directly. The pacer isn't part of it. */
class FakeCt5GattTransport : Ct5GattTransport {

    override val events: Flow<Ct5GattEvent> = MutableSharedFlow(replay = 16, extraBufferCapacity = 64)

    var connectCalled = false
        private set
    var discoverCalled = false
        private set
    var closeCalled = false
        private set
    var acknowledgements = 0
        private set
    var rssiReads = 0
        private set

    val mtuRequests = mutableListOf<Int>()

    val notifyEnables = mutableListOf<Boolean>()

    val writes = mutableListOf<ByteArray>()

    /** Index-aligned with [writes]. */
    val ladders = mutableListOf<LongArray>()

    /** Index-aligned with [writes]. */
    val expectsReply = mutableListOf<Boolean>()

    /** Every imperative call reports "could not initiate". */
    var rejectAll = false

    override fun connect() {
        connectCalled = true
    }

    override fun requestMtu(mtu: Int): Boolean {
        mtuRequests += mtu
        return !rejectAll
    }

    override fun discoverServices(): Boolean {
        discoverCalled = true
        return !rejectAll
    }

    override fun setNotify(enable: Boolean): Boolean {
        notifyEnables += enable
        return !rejectAll
    }

    override fun sendPaced(frame: ByteArray, ladder: LongArray, expectReply: Boolean) {
        writes += frame.copyOf()
        ladders += ladder
        expectsReply += expectReply
    }

    override fun acknowledge() {
        acknowledgements++
    }

    override fun readRemoteRssi(): Boolean {
        rssiReads++
        return !rejectAll
    }

    override fun close() {
        closeCalled = true
    }

    val opcodes: List<Int> get() = writes.map { it[0].toInt() and 0xFF }

    fun wrote(opcode: Int): Boolean = opcode in opcodes
}
