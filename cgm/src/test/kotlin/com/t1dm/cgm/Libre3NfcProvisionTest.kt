package com.t1dm.cgm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The §8 flow over a scripted link: state rule, flags strip, account mismatch, state build. */
@OptIn(ExperimentalCoroutinesApi::class)
class Libre3NfcProvisionTest {

    private class ScriptedLink(
        private val patchReply: ByteArray,
        private val switchReply: ByteArray,
    ) : Libre3NfcProvision.NfcVLink {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        var afterSend: () -> Unit = {}

        override suspend fun connect() {}

        override fun transceive(command: ByteArray): ByteArray {
            sent += command
            afterSend()
            return if (sent.size == 1) patchReply else switchReply
        }

        override fun close() {
            closed = true
        }
    }

    private class ThrowingLink(private val message: String) : Libre3NfcProvision.NfcVLink {
        override suspend fun connect() = throw java.io.IOException(message)
        override fun transceive(command: ByteArray): ByteArray = byteArrayOf()
        override fun close() {}
    }

    /** Reader mode on and no tag ever comes. */
    private class WaitingLink : Libre3NfcProvision.NfcVLink {
        val sent = mutableListOf<ByteArray>()
        var closed = false

        override suspend fun connect() {
            awaitCancellation()
        }

        override fun transceive(command: ByteArray): ByteArray {
            sent += command
            return byteArrayOf()
        }

        override fun close() {
            closed = true
        }
    }

    private val patch = Libre3PatchInfo(
        wearDurationMin = 21_600,
        rawStatus = 0x02,
        fw = "4.1.4.12",
        sensorState = 0x04,
        serial = "0T1DM0000",
    )

    private val switchReply = Libre3SwitchResponse(
        bleAddress = "C0:FF:EE:C0:FF:EE",
        blePin = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        activationTimeS = 1_790_000_000L,
    )

    private fun FakeLibre3Native.scripted(action: Libre3ProvisionAction) {
        patchInfoReply = Libre3Call.Ok(patch)
        switchResponse = Libre3Call.Ok(switchReply)
        actionByState = mapOf(patch.sensorState to action)
    }

    private val switchWire = byteArrayOf(0x00, 0xA5.toByte(), 0x00) + ByteArray(16)

    @Test
    fun `an activated sensor takes the switch-receiver path`() = runTest {
        val native = FakeLibre3Native().apply { scripted(Libre3ProvisionAction.SwitchReceiver) }
        val link = ScriptedLink(patchReply = byteArrayOf(0x02) + PATCH_NORMALIZED, switchReply = switchWire)
        val outcome = Libre3NfcProvision(link, native, nowMs = { 1_790_000_000_000L })
            .provision(" AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE ", Libre3Region.Eu)

        assertTrue(outcome is Libre3NfcProvision.Outcome.Provisioned)
        val state = (outcome as Libre3NfcProvision.Outcome.Provisioned).state
        assertEquals("0T1DM0000", state.serial)
        assertEquals("C0:FF:EE:C0:FF:EE", state.bleAddress)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), state.blePin)
        assertEquals(0x684FC53Fu, state.receiverId)
        assertEquals(21_600, state.wearDurationMin)
        // Account folded lowercase dashed; the tap time lands in the command body.
        assertEquals("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", native.foldCalls.single().first)
        assertEquals(1_790_000_000L, native.switchTransceives.single().first)
        assertArrayEquals(native.switchCmd, link.sent[1])
        assertArrayEquals(switchWire, native.parsedSwitchResponses.single())
    }

    @Test
    fun `a fresh sensor takes the activate path`() = runTest {
        val fresh = patch.copy(sensorState = 0x01)
        val native = FakeLibre3Native()
        native.patchInfoReply = Libre3Call.Ok(fresh)
        native.switchResponse = Libre3Call.Ok(switchReply)
        native.actionByState = mapOf(0x01 to Libre3ProvisionAction.Activate)
        val link = ScriptedLink(byteArrayOf(0x02), byteArrayOf(0x00, 0xA5.toByte(), 0x00))
        val outcome = Libre3NfcProvision(link, native, nowMs = { 0L })
            .provision("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Libre3Region.Eu)

        assertTrue(outcome is Libre3NfcProvision.Outcome.Provisioned)
        assertArrayEquals(native.activateCmd, link.sent[1])
    }

    @Test
    fun `the patch-info reply is handed over raw`() = runTest {
        val native = FakeLibre3Native().apply { scripted(Libre3ProvisionAction.SwitchReceiver) }
        val link = ScriptedLink(byteArrayOf(0x00, 0xa5.toByte()) + PATCH_NORMALIZED, switchWire)
        Libre3NfcProvision(link, native, nowMs = { 0L }).provision(
            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Libre3Region.Eu,
        )
        // Rust skips the flags byte and the 0xa5 echo run itself; nothing stripped here.
        assertArrayEquals(byteArrayOf(0x00, 0xa5.toByte()) + PATCH_NORMALIZED, native.parsedPatchResponses.single())
    }

    @Test
    fun `0xB1 from the switch parse is an account mismatch`() = runTest {
        val native = FakeLibre3Native()
        native.patchInfoReply = Libre3Call.Ok(patch)
        native.switchResponse = Libre3Call.AccountMismatch
        native.actionByState = mapOf(patch.sensorState to Libre3ProvisionAction.SwitchReceiver)
        val outcome = Libre3NfcProvision(ScriptedLink(byteArrayOf(0x02), byteArrayOf(0xB1.toByte())), native, { 0L })
            .provision("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Libre3Region.Eu)
        assertEquals(Libre3NfcProvision.Outcome.AccountMismatch, outcome)
    }

    @Test
    fun `an unknown sensor state names itself in the failure`() = runTest {
        val native = FakeLibre3Native()
        native.patchInfoReply = Libre3Call.Ok(patch.copy(sensorState = 0x63))
        native.actionByState = emptyMap()
        val outcome = Libre3NfcProvision(ScriptedLink(byteArrayOf(0x02), byteArrayOf()), native, { 0L })
            .provision("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Libre3Region.Eu)
        assertTrue(outcome is Libre3NfcProvision.Outcome.Failed)
        assertTrue((outcome as Libre3NfcProvision.Outcome.Failed).reason.contains("99"))
    }

    @Test
    fun `a tag that never arrives is a failure, not a crash`() = runTest {
        val native = FakeLibre3Native()
        val outcome = Libre3NfcProvision(ThrowingLink("no tag presented within 30 s"), native, { 0L })
            .provision("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Libre3Region.Eu)
        assertTrue(outcome is Libre3NfcProvision.Outcome.Failed)
    }

    @Test
    fun `cancelling while waiting for the tag sends nothing and closes the link`() = runTest {
        val native = FakeLibre3Native().apply { scripted(Libre3ProvisionAction.Activate) }
        val link = WaitingLink()
        val job = launch {
            Libre3NfcProvision(link, native, { 0L }).provision("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Libre3Region.Eu)
        }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(link.sent.isEmpty())
        assertTrue(native.switchTransceives.isEmpty())
        assertTrue("reader mode off with the attempt", link.closed)
    }

    @Test
    fun `cancelled after patch-info, no provisioning command is sent`() = runTest {
        val native = FakeLibre3Native().apply { scripted(Libre3ProvisionAction.Activate) }
        val link = ScriptedLink(byteArrayOf(0x02) + PATCH_NORMALIZED, switchWire)
        lateinit var job: Job
        link.afterSend = { job.cancel() }
        job = launch {
            Libre3NfcProvision(link, native, { 0L }).provision("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", Libre3Region.Eu)
        }
        job.join()

        assertEquals("patch-info only", 1, link.sent.size)
        assertTrue("no activate built", native.switchTransceives.isEmpty())
        assertTrue(link.closed)
    }

    private companion object {
        /** Live EU 3 Plus payload, echo run skipped: wear@7, status@10, fw@11, state@15, serial@16. */
        val PATCH_NORMALIZED = ByteArray(27).also {
            it[7] = 0x60
            it[8] = 0x54 // 21_600 min = 15 days, little-endian
            it[10] = 0x02
            byteArrayOf(0x04, 0x01, 0x04, 0x0c).copyInto(it, 11)
            it[15] = 0x04
            "0T1DM0000".toByteArray(Charsets.US_ASCII).copyInto(it, 16)
        }
    }
}