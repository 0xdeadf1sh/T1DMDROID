package com.t1dm.cgm

import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PLAN_T1DMDROID.md §4: drives ONE [Libre3PairingMachineHandle] over the [Libre3GattTransport].
 * Rust owns the byte-machine (§5.3 first pair / §5.4 cached reconnect); this class owns the
 * GATT pacing, the §5.10 timeout map and fail-closed teardown. It never retries — the
 * reconnect policy is the driver's — and persists nothing: the outcome goes to the caller,
 * kEnc/ivEnc stay session-scoped (phase 5 consumes them).
 *
 * Link ownership (§4): on Failed the transport closes (fail-closed, as before). On
 * Established the transport STAYS OPEN and the CALLER owns it — the data plane streams on the
 * same GATT link, and a fresh reconnect would re-run the whole VM handshake (§16 fallback,
 * not a design goal).
 */
class Libre3Session(
    private val native: Libre3Native,
    private val transport: Libre3GattTransport,
    private val tablesDir: String,
    private val sensor: Libre3SensorState,
    /** §5.5 R2, injected for tests; otherwise 16 fresh bytes from [random]/SecureRandom. */
    r2: ByteArray? = null,
    private val random: SecureRandom? = null,
    private val log: CgmSensorLog = CgmSensorLog.NONE,
) {

    init {
        require(r2 == null || r2.size == R2_SIZE) { "R2 must be $R2_SIZE bytes" }
    }

    private val r2Bytes: ByteArray = r2 ?: ByteArray(R2_SIZE).also { (random ?: SecureRandom()).nextBytes(it) }

    sealed interface Outcome {
        /** §5.6: R1/R2 echo verified; kAuth (when non-null) is the driver's to persist. */
        data class Established(val kEnc: ByteArray, val ivEnc: ByteArray, val kAuth: ByteArray?) : Outcome {
            override fun equals(other: Any?) = this === other || (other is Established &&
                kEnc.contentEquals(other.kEnc) &&
                ivEnc.contentEquals(other.ivEnc) &&
                (kAuth == null) == (other.kAuth == null) &&
                (kAuth == null || kAuth.contentEquals(other.kAuth)))

            override fun hashCode(): Int {
                var h = kEnc.contentHashCode()
                h = 31 * h + ivEnc.contentHashCode()
                h = 31 * h + (kAuth?.contentHashCode() ?: 0)
                return h
            }

            override fun toString() = "Established(kEnc=${kEnc.size} B, ivEnc=${ivEnc.size} B, " +
                "kAuth=${kAuth != null})"
        }

        /** [step] names where it died (a step label, "tables", "connect", …); never a retry. */
        data class Failed(val reason: String, val step: String? = null) : Outcome
    }

    /**
     * §5.3 first pair: tables gate → native ephemeral → machine → drive. Every failure is
     * fail-closed: the transport closes, the machine is released, [Outcome.Failed] comes back.
     */
    suspend fun runFirstPair(): Outcome {
        val gate = gate()
        if (gate != null) return gate
        val ephemeral = when (val e = native.makeFirstPairEphemeral(tablesDir, EPHEMERAL_MAX_ATTEMPTS)) {
            is Libre3Call.Ok -> e.value
            is Libre3Call.AccountMismatch -> return Outcome.Failed("ephemeral refused (account mismatch)", "ephemeral")
            is Libre3Call.Failed -> return Outcome.Failed("ephemeral: ${e.reason}", "ephemeral")
        }
        val machine = when (
            val m = native.startFirstPairMachine(tablesDir, ephemeral, sensor.blePin, r2Bytes)
        ) {
            is Libre3Call.Ok -> m.value
            is Libre3Call.AccountMismatch -> return Outcome.Failed("machine refused (account mismatch)", "machine")
            is Libre3Call.Failed -> return Outcome.Failed("machine: ${m.reason}", "machine")
        }
        log.i(TAG, "first pair for ${sensor.serial}: machine built after ${ephemeral.attempts} entropy attempt(s)")
        return drive(machine)
    }

    /**
     * §5.4 cached reconnect. GATED: [Libre3SensorState.kAuth] must be persisted, and the
     * Child23KAuthImport port (blob → phase5 raw key) is pending — until it lands no caller can
     * produce [phase5RawKey], so the driver takes [runFirstPair] (§5.4 fallback = full
     * handshake; §16 risk). No session state is written here either.
     */
    suspend fun runCachedReconnect(phase5RawKey: ByteArray): Outcome {
        if (sensor.kAuth == null) {
            return Outcome.Failed("cached reconnect needs the persisted kAuth blob; first pair is the fallback", "cached")
        }
        val gate = gate()
        if (gate != null) return gate
        val machine = when (
            val m = native.startCachedReconnectMachine(tablesDir, sensor.blePin, r2Bytes, phase5RawKey, sensor.kAuth)
        ) {
            is Libre3Call.Ok -> m.value
            is Libre3Call.AccountMismatch -> return Outcome.Failed("machine refused (account mismatch)", "machine")
            is Libre3Call.Failed -> return Outcome.Failed("machine: ${m.reason}", "machine")
        }
        log.i(TAG, "cached reconnect for ${sensor.serial}")
        return drive(machine)
    }

    /** §9 step 4 before anything else: a refused table set never touches the transport. */
    private fun gate(): Outcome.Failed? = when (val v = native.verifyTables(tablesDir)) {
        is Libre3Call.Ok -> null
        is Libre3Call.AccountMismatch -> Outcome.Failed("tables refused (account mismatch)", "tables")
        is Libre3Call.Failed -> Outcome.Failed(v.reason, "tables")
    }

    // MARK: - Driver loop

    private sealed interface DriverEvent {
        data class Next(val action: Libre3PairingAction) : DriverEvent

        /** The machine refused; fail-closed (never retried — the driver owns reconnect policy). */
        data class MachineFailed(val reason: String) : DriverEvent

        /** Link gone or the CCCD/write path failed; the step label is appended at the site. */
        data class LinkFailed(val reason: String) : DriverEvent
    }

    private class NotifyChunk(val char: Libre3PairingChar, val bytes: ByteArray)

    private suspend fun drive(machine: Libre3PairingMachineHandle): Outcome {
        var established = false
        try {
            val result = coroutineScope {
                val raw = Channel<NotifyChunk>(Channel.UNLIMITED)
                val driver = Channel<DriverEvent>(Channel.UNLIMITED)
                val pump = launch(CoroutineName("Libre3PairingPump")) {
                    // One coroutine feeds the machine in arrival order, whatever thread the
                    // transport's notify landed on; early-arrival chunks buffer inside it.
                    for (chunk in raw) {
                        val next = machine.onNotify(chunk.char, chunk.bytes)
                        machine.error?.let { driver.send(DriverEvent.MachineFailed(it)) }
                        if (machine.error != null) return@launch
                        if (next != null) driver.send(DriverEvent.Next(next))
                    }
                }
                try {
                    outcome(machine, raw, driver)
                } finally {
                    raw.close() // the pump drains what arrived and exits; scope waits for it
                }
            }
            established = result is Outcome.Established
            return result
        } finally {
            // §4: on Established the transport STAYS OPEN — the data plane streams on the same
            // GATT link and the caller owns it from here (a fresh reconnect would re-run the
            // whole VM handshake; §16 fallback, not a design goal). Failed, or a cancelled
            // drive: fail-closed, the link dies with the session. The machine handle closes on
            // both paths.
            if (!established) transport.close()
            machine.close()
        }
    }

    private suspend fun outcome(
        machine: Libre3PairingMachineHandle,
        raw: Channel<NotifyChunk>,
        driver: Channel<DriverEvent>,
    ): Outcome {
        // Listeners BEFORE connect: the first notify may follow the last CCCD write closely.
        for (c in Libre3PairingChar.entries) {
            transport.setNotifyListener(c.uuid) { chunk, _ -> raw.trySend(NotifyChunk(c, chunk)) }
        }
        val ready = CompletableDeferred<Unit>()
        transport.connect(
            onReady = { ready.complete(Unit) },
            onGattError = { reason -> driver.trySend(DriverEvent.LinkFailed(reason)) },
        )
        if (withTimeoutOrNull(CONNECT_MS) { ready.await() } == null) {
            return Outcome.Failed("the handshake characteristics did not arm in $CONNECT_MS ms", "connect")
        }
        var step: String? = null
        var action: Libre3PairingAction = when (val first = nextStep(machine, driver)) {
            is Next.Step -> first.action
            is Next.Rejected -> return Outcome.Failed(first.reason, step)
        }
        while (true) {
            when (val a = action) {
                is Libre3PairingAction.Established -> {
                    log.i(TAG, "handshake established; kAuth ${if (a.kAuth != null) "carried" else "absent"}")
                    return Outcome.Established(a.kEnc, a.ivEnc, a.kAuth)
                }

                is Libre3PairingAction.WriteChar -> {
                    step = "${a.char.name} write"
                    log.d(TAG, "step: ${a.char.name} write, ${a.chunks.size} chunk(s)")
                    for (chunk in a.chunks) {
                        val done = CompletableDeferred<Boolean>()
                        transport.writeChunk(a.char.uuid, chunk) { ok -> done.complete(ok) }
                        val confirmed = withTimeoutOrNull(WRITE_CHUNK_MS) { done.await() }
                        if (confirmed != true) {
                            return Outcome.Failed("chunk not confirmed in $WRITE_CHUNK_MS ms", step)
                        }
                    }
                    action = when (val next = nextStep(machine, driver)) {
                        is Next.Step -> next.action
                        is Next.Rejected -> return Outcome.Failed(next.reason, step)
                    }
                }

                is Libre3PairingAction.AwaitNotify -> {
                    step = a.label
                    val timeoutMs = if (a.drain) COMMAND_AWAIT_MS else DATA_AWAIT_MS
                    log.d(TAG, "step: await ${a.label} (${timeoutMs} ms)")
                    when (val event = withTimeoutOrNull(timeoutMs) { driver.receive() }) {
                        null -> return Outcome.Failed("timed out waiting for ${a.label} ($timeoutMs ms)", a.label)
                        is DriverEvent.Next -> action = event.action
                        is DriverEvent.MachineFailed ->
                            return Outcome.Failed("machine rejected: ${event.reason}", a.label)
                        is DriverEvent.LinkFailed ->
                            return Outcome.Failed("link failed: ${event.reason}", a.label)
                    }
                }
            }
        }
    }

    /**
     * The machine's next frontier step. The pump may already hold it — an early-arrival
     * cascade returns actions through the channel — and the machine latches one step per ask,
     * so a queued event is consumed before any direct `next_action` (they would interleave the
     * frontier otherwise). A queued failure surfaces with its own reason.
     */
    private sealed interface Next {
        data class Step(val action: Libre3PairingAction) : Next
        data class Rejected(val reason: String) : Next
    }

    private fun nextStep(
        machine: Libre3PairingMachineHandle,
        driver: Channel<DriverEvent>,
    ): Next = when (val queued = driver.tryReceive().getOrNull()) {
        null -> when (val asked = machine.nextAction()) {
            null -> Next.Rejected("machine refused: ${machine.error ?: "no reason given"}")
            else -> Next.Step(asked)
        }
        is DriverEvent.Next -> Next.Step(queued.action)
        is DriverEvent.MachineFailed -> Next.Rejected("machine rejected: ${queued.reason}")
        is DriverEvent.LinkFailed -> Next.Rejected("link failed: ${queued.reason}")
    }

    private fun nextAction(machine: Libre3PairingMachineHandle): Libre3PairingAction? {
        val action = machine.nextAction()
        if (action == null) log.w(TAG, "nextAction refused: ${machine.error}")
        return action
    }

    private companion object {
        const val TAG = "Libre3Pair"

        /** §5.3 command clock (drain awaits): the Swift PairingFlow default. */
        const val COMMAND_AWAIT_MS = 2_000L

        /** §5.3 data awaits: cert 140 B, eph 65 B, R1 23 B, phase6 67 B. */
        const val DATA_AWAIT_MS = 10_000L

        /** §5.10: each chunk is awaited via onCharacteristicWrite before the next. */
        const val WRITE_CHUNK_MS = 2_000L

        /**
         * Patience for the whole link setup: connectGatt callback (HyperOS stalls past 10 s
         * live, 2026-09-24) + discovery + three acked CCCD writes. The transport arms in ~1 s
         * once connected, so the budget is dominated by the connect itself.
         */
        const val CONNECT_MS = 30_000L

        /** §5.3: accepted null-branch entropy sampling budget per pairing attempt. */
        const val EPHEMERAL_MAX_ATTEMPTS = 64

        const val R2_SIZE = 16
    }
}
