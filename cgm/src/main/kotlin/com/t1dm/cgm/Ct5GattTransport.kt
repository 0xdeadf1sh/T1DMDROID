package com.t1dm.cgm

import com.t1dm.core.model.CgmLogTopic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Ct5Char { WRITE, NOTIFY }

/** One GATT event, serial on one coroutine; no Write since 0x1002 is WRITE-WITHOUT-RESPONSE. */
sealed interface Ct5GattEvent {
    data class Connection(val connected: Boolean, val statusOk: Boolean, val statusCode: Int) : Ct5GattEvent

    /** Default MTU 23 caps a notification at 20B; the 0x3F reply needs 22, hence this. */
    data class MtuChanged(val mtu: Int, val ok: Boolean) : Ct5GattEvent

    /** [hasCt5Service] is true iff the service and BOTH characteristics were found. */
    data class ServicesDiscovered(val ok: Boolean, val hasCt5Service: Boolean) : Ct5GattEvent

    /** The notify CCCD write completed. A real acknowledgement — descriptor writes are acked. */
    data class NotifyEnabled(val ok: Boolean) : Ct5GattEvent

    /** Pushed on `0x1001`. The only event that may advance the protocol. [rx]: as on AiDEX. */
    data class Notify(val value: ByteArray, val rx: Long? = null) : Ct5GattEvent

    data class Rssi(val dbm: Int, val ok: Boolean) : Ct5GattEvent

    /** Pacer wrote [opcode]'s whole ladder, no reply; a dead link yields no error or disconnect. */
    data class LadderExhausted(val opcode: Int) : Ct5GattEvent

    data class Failure(val reason: String) : Ct5GattEvent
}

/** Calls return false only if not INITIATED (adapter down, char absent); outcome via [events]. */
interface Ct5GattTransport {
    /** Small replay: a subscriber right after [connect] never misses the first Connection. */
    val events: Flow<Ct5GattEvent>

    fun connect()

    /** Required before a bind — see [Ct5GattEvent.MtuChanged]. */
    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Boolean

    /** Notifications on `0x1001` (notify, not indicate). */
    fun setNotify(enable: Boolean): Boolean

    /** Only way to write: keeps Android's one-outstanding-op rule; rewrites [frame] on [ladder]. */
    fun sendPaced(frame: ByteArray, ladder: LongArray, expectReply: Boolean)

    /** Stop rewriting the frame in flight. */
    fun acknowledge()

    fun readRemoteRssi(): Boolean

    fun close()
}

/** A push and its ack are one exchange, as are a pull and its batch. */
internal fun ct5LogTopic(opcode: Int): CgmLogTopic = when (opcode) {
    Ct5Constants.Opcode.PUSH -> CgmLogTopic.GLUCOSE
    Ct5Constants.Opcode.PULL_HISTORY -> CgmLogTopic.HISTORY
    else -> CgmLogTopic.NONE
}

/** Frames carrying the stored secret: the nonces, B, the unbind password. */
internal fun ct5Withheld(opcode: Int): Boolean =
    opcode == Ct5Constants.Opcode.SET_ID ||
        opcode == Ct5Constants.Opcode.CHECK_ID ||
        opcode == Ct5Constants.Opcode.SET_PARAMETERS

internal fun ct5OpName(opcode: Int): String = "op 0x%02x".format(opcode)

/** One coroutine, one queue: exactly one frame in flight; unsolicited push must not cancel it. */
class Ct5WritePacer(
    private val scope: CoroutineScope,
    /** `false` means the write could not be initiated. */
    private val write: (ByteArray) -> Boolean,
    /** By opcode: written its whole ladder through with no reply. */
    private val onExhausted: (Int) -> Unit,
    /** By opcode: the platform refused the write outright. */
    private val onWriteRejected: (Int) -> Unit,
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) {
    private class Step(val frame: ByteArray, val ladder: LongArray, val expectReply: Boolean) {
        val opcode: Int get() = frame[0].toInt() and 0xFF
    }

    private val queue = Channel<Step>(Channel.UNLIMITED)
    private var pump: Job? = null

    /** Touched from the pump and the event coroutine; a stale completion is harmless. */
    @Volatile
    private var inFlight: CompletableDeferred<Unit>? = null

    /** Idempotent. */
    fun start() {
        if (pump != null) return
        pump = scope.launch {
            for (step in queue) run(step)
        }
    }

    fun enqueue(frame: ByteArray, ladder: LongArray, expectReply: Boolean) {
        if (frame.isEmpty()) return
        // UNLIMITED: never suspends, never fails for a running pacer.
        queue.trySend(Step(frame, ladder, expectReply))
    }

    fun acknowledge() {
        inFlight?.complete(Unit)
    }

    fun close() {
        queue.close()
        pump?.cancel()
        pump = null
        inFlight?.complete(Unit)
        inFlight = null
    }

    private suspend fun run(step: Step) {
        val ack = CompletableDeferred<Unit>()
        inFlight = ack
        try {
            var elapsed = 0L
            val topic = ct5LogTopic(step.opcode)
            val secret = ct5Withheld(step.opcode)
            for ((rung, offset) in step.ladder.withIndex()) {
                if (waitedFor(ack, offset - elapsed)) return
                elapsed = offset
                log.tx(
                    channel = "0x1002",
                    bytes = step.frame,
                    topic = topic,
                    text = "${ct5OpName(step.opcode)} rung ${rung + 1}/${step.ladder.size}",
                    secret = secret,
                )
                if (!write(step.frame)) {
                    // No ladder rung fixes a stack that refused to queue the bytes.
                    log.w(TAG, "write of 0x${step.opcode.toString(16)} rejected by the stack")
                    onWriteRejected(step.opcode)
                    return
                }
            }
            if (waitedFor(ack, Ct5Constants.LADDER_GRACE_MS)) return
            if (step.expectReply) {
                log.w(TAG, "0x${step.opcode.toString(16)} written ${step.ladder.size}x with no reply")
                onExhausted(step.opcode)
            }
        } finally {
            if (inFlight === ack) inFlight = null
        }
    }

    private suspend fun waitedFor(ack: CompletableDeferred<Unit>, ms: Long): Boolean {
        if (ack.isCompleted) return true
        if (ms <= 0) return false
        return withTimeoutOrNull(ms) { ack.await() } != null
    }

    private companion object {
        const val TAG = "Ct5Connect"
    }
}
