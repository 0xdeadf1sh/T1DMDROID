package com.t1dm.cgm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4/§12.4: the pairing session driven over a scripted machine + recording transport. The fake
 * native never leaves the JVM; the fake transport plays the sensor (§12.5 fixtures).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Libre3SessionTest {

    // MARK: - Script builders (the real machine's §5.3/§5.4 action sequences)

    private fun fill(count: Int, seed: Int) = ByteArray(count) { (it + seed).toByte() }

    /** §5.2 phone→sensor: offset_LE2-prefixed ≤18-byte chunks, as the machine pre-frames them. */
    private fun fragments(message: ByteArray, payloadPerChunk: Int = WRITE_CHUNK_PAYLOAD): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < message.size) {
            val end = minOf(offset + payloadPerChunk, message.size)
            out += byteArrayOf(
                (offset and 0xFF).toByte(),
                ((offset shr 8) and 0xFF).toByte(),
            ) + message.copyOfRange(offset, end)
            offset = end
        }
        return out
    }

    /** §5.2 sensor→phone: seq-prefixed ≤19-byte chunks, seq starting at 0 per message. */
    private fun seqChunks(payload: ByteArray, payloadPerChunk: Int = NOTIFY_CHUNK_PAYLOAD): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var seq = 0
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + payloadPerChunk, payload.size)
            out += byteArrayOf(seq.toByte()) + payload.copyOfRange(offset, end)
            seq++
            offset = end
        }
        return out
    }

    private val cert162 = fill(162, 1)
    private val phase3Wire = fill(72, 40) // 65 B point padded to 72
    private val sensorCert = fill(140, 60)
    private val sensorEph = fill(65, 80)
    private val sensorEphWire = fill(72, 40)
    private val r1Challenge = fill(23, 100)
    private val phase5Wire = fill(54, 120)
    private val phase6Wire = fill(67, 140)
    private val kEnc = fill(16, 160)
    private val ivEnc = fill(8, 180)
    private val r2 = fill(16, 200)
    private val kAuthBlob = fill(24, 220)

    /** PLAN §5.3 steps 1–8, the machine's own order (tests/pairing.rs assert table). */
    private fun firstPairScript(): List<Libre3PairingAction> = listOf(
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x01))),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x02))),
        Libre3PairingAction.WriteChar(SecCertData, fragments(cert162)),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x03))),
        Libre3PairingAction.AwaitNotify(SecCommandResponse, 1, byteArrayOf(0x04), true, "CertificateAccepted"),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x09))),
        Libre3PairingAction.AwaitNotify(SecCommandResponse, 1, byteArrayOf(0x0a), true, "CertificateReady"),
        Libre3PairingAction.AwaitNotify(SecCertData, sensorCert.size, null, false, "sensorCertNotify"),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x0d))),
        Libre3PairingAction.WriteChar(SecCertData, fragments(phase3Wire)),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x0e))),
        Libre3PairingAction.AwaitNotify(SecCommandResponse, 1, byteArrayOf(0x0f), true, "EphemeralReady"),
        Libre3PairingAction.AwaitNotify(SecCertData, sensorEph.size, null, false, "sensorEphemeralNotify"),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x11))),
        Libre3PairingAction.AwaitNotify(SecCommandResponse, 1, byteArrayOf(0x08), true, "ChallengeLoadDone"),
        Libre3PairingAction.AwaitNotify(SecChallengeData, r1Challenge.size, null, false, "sensorR1Notify"),
        Libre3PairingAction.WriteChar(SecChallengeData, fragments(phase5Wire)),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x08))),
        Libre3PairingAction.AwaitNotify(SecCommandResponse, 1, byteArrayOf(0x08), true, "PatchChallengeLoadDone"),
        Libre3PairingAction.AwaitNotify(SecChallengeData, phase6Wire.size, null, false, "phase6Notify"),
        Libre3PairingAction.Established(kEnc, ivEnc, kAuth = null),
    )

    /** PLAN §5.4: StartAuthorization straight into the challenge, no cert, no ephemeral. */
    private fun cachedScript(): List<Libre3PairingAction> = listOf(        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x11))),
        Libre3PairingAction.AwaitNotify(SecCommandResponse, 1, byteArrayOf(0x08), true, "ChallengeLoadDone"),
        Libre3PairingAction.AwaitNotify(SecChallengeData, r1Challenge.size, null, false, "sensorR1Notify"),
        Libre3PairingAction.WriteChar(SecChallengeData, fragments(phase5Wire)),
        Libre3PairingAction.WriteChar(SecCommandResponse, listOf(byteArrayOf(0x08))),
        Libre3PairingAction.AwaitNotify(SecCommandResponse, 1, byteArrayOf(0x08), true, "PatchChallengeLoadDone"),
        Libre3PairingAction.AwaitNotify(SecChallengeData, phase6Wire.size, null, false, "phase6Notify"),
        Libre3PairingAction.Established(kEnc, ivEnc, kAuth = kAuthBlob),
    )

    /** One sensor message and the command byte it answers (null = deliver immediately). */
    private class SensorMsg(val char: Libre3PairingChar, val payload: ByteArray, val afterWrite: Byte?)

    private val firstPairSensorScript = listOf(
        SensorMsg(SecCommandResponse, byteArrayOf(0x04), 0x03),
        SensorMsg(SecCommandResponse, byteArrayOf(0x0a), 0x09),
        SensorMsg(SecCertData, sensorCert, 0x09),
        SensorMsg(SecCommandResponse, byteArrayOf(0x0f), 0x0e),
        SensorMsg(SecCertData, sensorEph, 0x0e),
        SensorMsg(SecCommandResponse, byteArrayOf(0x08), 0x11),
        SensorMsg(SecChallengeData, r1Challenge, 0x11),
        SensorMsg(SecCommandResponse, byteArrayOf(0x08), 0x08),
        SensorMsg(SecChallengeData, phase6Wire, 0x08),
    )

    private val cachedSensorScript = listOf(
        SensorMsg(SecCommandResponse, byteArrayOf(0x08), 0x11),
        SensorMsg(SecChallengeData, r1Challenge, 0x11),
        SensorMsg(SecCommandResponse, byteArrayOf(0x08), 0x08),
        SensorMsg(SecChallengeData, phase6Wire, 0x08),
    )

    /** Delivers each scripted sensor message once the command it answers has been written. */
    private fun TestScope.sensorPump(
        fake: FakeLibre3GattTransport,
        script: List<SensorMsg>,
    ): Job = launch {
        for (msg in script) {
            val trigger = msg.afterWrite
            if (trigger != null) {
                while (
                    fake.writes.none {
                        it.first == SecCommandResponse.uuid && it.second[0] == trigger
                    }
                ) {
                    yield()
                }
            }
            for (chunk in seqChunks(msg.payload)) {
                fake.notify(msg.char, chunk)
                yield()
            }
        }
    }

    private fun expectedWrites(script: List<Libre3PairingAction>): List<Pair<String, ByteArray>> =
        script.flatMap { a ->
            if (a is Libre3PairingAction.WriteChar) a.chunks.map { a.char.uuid to it } else emptyList()
        }

    private fun assertWrites(expected: List<Pair<String, ByteArray>>, actual: List<Pair<String, ByteArray>>) {
        assertEquals("write count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("write $i uuid", expected[i].first, actual[i].first)
            assertTrue(
                "write $i bytes",
                expected[i].second.contentEquals(actual[i].second),
            )
        }
    }

    private fun assertDelivered(script: List<SensorMsg>, fed: List<Pair<Libre3PairingChar, ByteArray>>) {
        val expected = script.flatMap { msg ->
            seqChunks(msg.payload).map { msg.char to it }
        }
        assertEquals("fed chunk count", expected.size, fed.size)
        for (i in expected.indices) {
            assertEquals("fed $i char", expected[i].first, fed[i].first)
            assertTrue("fed $i bytes", expected[i].second.contentEquals(fed[i].second))
        }
    }

    private fun sensorState(kAuth: ByteArray? = null) = Libre3SensorState(
        receiverId = 0x684FC53Fu,
        serial = "0T1DM0000",
        bleAddress = "C0:FF:EE:C0:FF:EE",
        blePin = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        activationTimeS = 1_790_000_000L,
        wearDurationMin = 20_160,
        region = Libre3Region.Eu,
        provisionedAtMs = 0L,
        kAuth = kAuth,
    )

    private fun nativeFor(machine: Libre3PairingMachineHandle): FakeLibre3Native = FakeLibre3Native().apply {
        tablesVerify = Libre3Call.Ok(Unit)
        ephemeral = Libre3Call.Ok(
            Libre3FirstPairEphemeral(
                privateBe32 = fill(32, 1),
                publicKey65 = byteArrayOf(0x04) + fill(64, 2),
                nullEntropy11a = fill(0x11A, 3),
                nullScalarWindow = fill(70, 4),
                attempts = 3,
            ),
        )
        firstPairMachine = machine
    }

    private companion object {
        val SecCertData = Libre3PairingChar.SecCertData
        val SecChallengeData = Libre3PairingChar.SecChallengeData
        val SecCommandResponse = Libre3PairingChar.SecCommandResponse

        /** §5.2 chunk sizes, fixed by the protocol (no MTU negotiation, §5.10). */
        const val WRITE_CHUNK_PAYLOAD = 18
        const val NOTIFY_CHUNK_PAYLOAD = 19

        const val TABLES_DIR = "/data/user/0/com.t1dm.app/files/libre3/tables"

        // Timeout budgets, re-declared here because the session's are private (values asserted
        // against, not coupled to: a changed budget only shifts these advanceTimeBy calls).
        const val LIBRE3_COMMAND_AWAIT_MS = 2_500L
        const val LIBRE3_WRITE_CHUNK_MS = 2_500L
        const val LIBRE3_CONNECT_MS = 31_000L
    }

    // MARK: - Tests

    @Test
    fun `the first pair drives the machine to Established over the right bytes`() = runTest {
        val machine = ScriptedPairingMachine(*firstPairScript().toTypedArray())
        val native = nativeFor(machine)
        val fake = FakeLibre3GattTransport()
        val session = Libre3Session(native, fake, TABLES_DIR, sensorState(), r2 = r2)

        val outcome = async { session.runFirstPair() }
        sensorPump(fake, firstPairSensorScript)
        val result = outcome.await()

        assertEquals(Libre3Session.Outcome.Established(kEnc, ivEnc, null), result)
        // Every chunk, to the right UUID, in machine order (§5.3 step table).
        assertWrites(expectedWrites(firstPairScript()), fake.writes)
        // Every notify chunk reached the machine, in arrival order.
        assertDelivered(firstPairSensorScript, machine.fedChunks)
        // The session wired the machine with the sensor's PIN (tail4) and its own R2.
        assertEquals(1, native.firstPairRequests.size)
        assertTrue(native.firstPairRequests[0].first.contentEquals(sensorState().blePin))
        assertTrue(native.firstPairRequests[0].second.contentEquals(r2))
        // §4 ownership handover: on Established the TRANSPORT STAYS OPEN (the data plane
        // streams on the same GATT link); the machine handle is released.
        assertFalse(fake.closed)
        assertTrue(machine.closed)
        assertTrue(fake.readyFired)
    }

    @Test
    fun `a command-clock timeout fails with the step label and closes the link`() = runTest {
        val machine = ScriptedPairingMachine(*firstPairScript().take(5).toTypedArray())
        val fake = FakeLibre3GattTransport()
        val session = Libre3Session(nativeFor(machine), fake, TABLES_DIR, sensorState(), r2 = r2)

        val outcome = async { session.runFirstPair() }
        // No sensor script: the 0x04 CertificateAccepted clock never answers.
        advanceTimeBy(LIBRE3_COMMAND_AWAIT_MS)
        runCurrent()
        val result = outcome.await()

        assertEquals("CertificateAccepted", (result as Libre3Session.Outcome.Failed).step)
        assertTrue(result.reason.contains("timed out"))
        assertTrue(fake.closed)
        assertTrue(machine.closed)
        assertEquals("the writes stop at the gate command", 12, fake.writes.size)
    }

    @Test
    fun `a machine rejection fails closed with the machine's verdict`() = runTest {
        val machine = ScriptedPairingMachine(*firstPairScript().take(5).toTypedArray())
        val fake = FakeLibre3GattTransport()
        val session = Libre3Session(nativeFor(machine), fake, TABLES_DIR, sensorState(), r2 = r2)

        val outcome = async { session.runFirstPair() }
        sensorPump(fake, listOf(SensorMsg(SecCommandResponse, byteArrayOf(0x05), 0x03)))
        val result = outcome.await()

        assertTrue(result is Libre3Session.Outcome.Failed)
        assertTrue((result as Libre3Session.Outcome.Failed).reason.contains("rejected"))
        assertEquals("CertificateAccepted", result.step)
        assertTrue(fake.closed)
        assertTrue(machine.closed)
    }

    @Test
    fun `a link failure during an await fails closed`() = runTest {
        val machine = ScriptedPairingMachine(*firstPairScript().take(5).toTypedArray())
        val fake = FakeLibre3GattTransport(autoReady = false)
        val session = Libre3Session(nativeFor(machine), fake, TABLES_DIR, sensorState(), r2 = r2)

        val outcome = async { session.runFirstPair() }
        runCurrent()
        fake.fireReady()
        runCurrent()
        fake.fail("disconnected (status=8)")
        val result = outcome.await()

        assertTrue(result is Libre3Session.Outcome.Failed)
        assertTrue((result as Libre3Session.Outcome.Failed).reason.contains("disconnected"))
        assertTrue(fake.closed)
        assertTrue(machine.closed)
    }

    @Test
    fun `a refused tables set fails before the transport is touched`() = runTest {
        val machine = ScriptedPairingMachine(*firstPairScript().toTypedArray())
        val native = nativeFor(machine).apply {
            tablesVerify = Libre3Call.Failed("tables: phone_cert_162b.bin sha256 mismatch")
        }
        val fake = FakeLibre3GattTransport()
        val session = Libre3Session(native, fake, TABLES_DIR, sensorState(), r2 = r2)

        val result = session.runFirstPair()

        assertTrue(result is Libre3Session.Outcome.Failed)
        assertEquals("tables", (result as Libre3Session.Outcome.Failed).step)
        assertTrue(result.reason.contains("phone_cert_162b.bin"))
        assertFalse("no transport use on a refused table set", fake.connectCalled)
        assertTrue(native.ephemeralAttempts.isEmpty())
        assertTrue(native.firstPairRequests.isEmpty())
    }

    @Test
    fun `the cached reconnect gate refuses a sensor with no kAuth blob`() = runTest {
        val fake = FakeLibre3GattTransport()
        val native = FakeLibre3Native()
        val session = Libre3Session(native, fake, TABLES_DIR, sensorState(kAuth = null), r2 = r2)

        val result = session.runCachedReconnect(kAuthBlob)

        assertTrue(result is Libre3Session.Outcome.Failed)
        assertEquals("cached", (result as Libre3Session.Outcome.Failed).step)
        assertFalse(fake.connectCalled)
        assertTrue(native.cachedRequests.isEmpty())
    }

    /** §5.4 (the two-step cached machine, comment here because § is not a JVM name char). */
    @Test
    fun `the cached reconnect drives the two-step machine`() = runTest {
        val machine = ScriptedPairingMachine(*cachedScript().toTypedArray())
        val fake = FakeLibre3GattTransport()
        val native = FakeLibre3Native().apply {
            tablesVerify = Libre3Call.Ok(Unit)
            cachedMachine = machine
        }
        val session = Libre3Session(native, fake, TABLES_DIR, sensorState(kAuth = kAuthBlob), r2 = r2)

        val outcome = async { session.runCachedReconnect(kAuthBlob) }
        sensorPump(fake, cachedSensorScript)
        val result = outcome.await()

        assertEquals(Libre3Session.Outcome.Established(kEnc, ivEnc, kAuthBlob), result)
        assertWrites(expectedWrites(cachedScript()), fake.writes)
        assertDelivered(cachedSensorScript, machine.fedChunks)
        assertEquals(1, native.cachedRequests.size)
        assertTrue(native.cachedRequests[0][2]!!.contentEquals(kAuthBlob))
        assertTrue(native.cachedRequests[0][3]!!.contentEquals(kAuthBlob))
        // §4 ownership handover: success leaves the link open for the data plane.
        assertFalse(fake.closed)
        assertTrue(machine.closed)
    }

    @Test
    fun `a recorded PDU fixture replays to the same Established and the same writes`() = runTest {
        // First run: capture what the wire saw (§12.5).
        val machineA = ScriptedPairingMachine(*firstPairScript().toTypedArray())
        val fakeA = FakeLibre3GattTransport()
        val sessionA = Libre3Session(nativeFor(machineA), fakeA, TABLES_DIR, sensorState(), r2 = r2)
        val outcomeA = async { sessionA.runFirstPair() }
        sensorPump(fakeA, firstPairSensorScript)
        val establishedA = outcomeA.await() as Libre3Session.Outcome.Established
        val recorded = fakeA.pdus
        assertTrue(recorded.isNotEmpty())

        // Replay: the recorded NOTIFY side, eager — early arrival is the machine's job (§5.2).
        val machineB = ScriptedPairingMachine(*firstPairScript().toTypedArray())
        val fakeB = FakeLibre3GattTransport(recorded = recorded)
        val sessionB = Libre3Session(nativeFor(machineB), fakeB, TABLES_DIR, sensorState(), r2 = r2)
        val outcomeB = async { sessionB.runFirstPair() }
        runCurrent()
        fakeB.replay()
        val establishedB = outcomeB.await()

        assertEquals(establishedA, establishedB)
        assertWrites(
            recorded.filter { it.direction == Libre3Pdu.Direction.WRITE }
                .map { it.charUuid to it.bytes },
            fakeB.writes,
        )
        // Success leaves the transport open (§4 ownership handover).
        assertFalse(fakeB.closed)
        assertTrue(machineB.closed)
    }

    @Test
    fun `an unconfirmed write chunk fails the session closed`() = runTest {
        val machine = ScriptedPairingMachine(*firstPairScript().take(3).toTypedArray())
        val fake = FakeLibre3GattTransport().apply { silentWrites = true }
        val session = Libre3Session(nativeFor(machine), fake, TABLES_DIR, sensorState(), r2 = r2)

        val outcome = async { session.runFirstPair() }
        advanceTimeBy(LIBRE3_WRITE_CHUNK_MS)
        runCurrent()
        val result = outcome.await()

        assertTrue(result is Libre3Session.Outcome.Failed)
        assertEquals("SecCommandResponse write", (result as Libre3Session.Outcome.Failed).step)
        assertTrue(fake.closed)
        assertTrue(machine.closed)
    }

    @Test
    fun `an ephemeral the native refuses fails before the machine`() = runTest {
        val native = FakeLibre3Native().apply {
            tablesVerify = Libre3Call.Ok(Unit)
            ephemeral = Libre3Call.Failed("entropy source failed")
        }
        val fake = FakeLibre3GattTransport()
        val session = Libre3Session(native, fake, TABLES_DIR, sensorState(), r2 = r2)

        val result = session.runFirstPair()

        assertTrue(result is Libre3Session.Outcome.Failed)
        assertEquals("ephemeral", (result as Libre3Session.Outcome.Failed).step)
        assertFalse(fake.connectCalled)
    }

    @Test
    fun `a parked session without onReady times out at connect`() = runTest {
        val machine = ScriptedPairingMachine(*firstPairScript().toTypedArray())
        val fake = FakeLibre3GattTransport(autoReady = false)
        val session = Libre3Session(nativeFor(machine), fake, TABLES_DIR, sensorState(), r2 = r2)

        val outcome = async { session.runFirstPair() }
        advanceTimeBy(LIBRE3_CONNECT_MS)
        runCurrent()
        val result = outcome.await()

        assertTrue(result is Libre3Session.Outcome.Failed)
        assertEquals("connect", (result as Libre3Session.Outcome.Failed).step)
        assertTrue(fake.closed)
    }
}
