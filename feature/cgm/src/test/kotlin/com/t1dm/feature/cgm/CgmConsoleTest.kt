package com.t1dm.feature.cgm

import com.t1dm.core.model.CgmLogEntry
import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class CgmConsoleTest {

    private class FakeHost : CgmConsoleHost {
        val calls = mutableListOf<String>()
        val echoes = mutableListOf<String>()
        override fun echo(line: String) { echoes += line }
        override suspend fun readingStats() = CgmReadingStats(0, 0, null, null)
        override suspend fun lastReadings(n: Int) = emptyList<CgmReading>()
        override fun connect() { calls += "connect" }
        override fun disconnect() { calls += "disconnect" }
        override fun reconnect() { calls += "reconnect" }
        override fun fetch() { calls += "fetch" }
        override fun scan() { calls += "scan" }
        override fun makeMain() { calls += "main" }
        override fun hide() { calls += "hide" }
        override fun setWarmupMin(min: Int) { calls += "warmup $min" }
        override fun activate() { calls += "activate" }
        override fun bind() { calls += "bind" }
        override fun repair() { calls += "repair" }
        override fun recoverKey() { calls += "recoverkey" }
        override fun provision() { calls += "provision" }
        override fun save() { calls += "save" }

        var opened: CgmFrameResult<List<CgmOpenedFrame>> = CgmFrameResult.NotLinked
        var sealed: CgmFrameResult<ByteArray> = CgmFrameResult.NotLinked
        val openedChunks = mutableListOf<CgmCapturedChunk>()
        override fun openFrames(chunks: List<CgmCapturedChunk>) = opened.also { openedChunks += chunks }
        override fun sealFrame(kind: Int, sequence: Int, plaintext: ByteArray) =
            sealed.also { calls += "seal $kind $sequence ${plaintext.size} B" }
    }

    private val live = CgmSensorRow(
        id = "s1", name = "S1", ordinalLabel = "CGM #0", active = true, authoritative = false,
        status = CgmSourceStatus.Live, warmupWindowMin = 60,
    )

    private val libre = live.copy(hasFrameCrypto = true)

    private fun view(row: CgmSensorRow? = live, nowMs: Long = 1_000_000L, entries: List<CgmLogEntry> = emptyList()) =
        CgmConsoleView(row, CgmPanelState(sensors = listOfNotNull(row), maxSessions = 4), entries, ZoneOffset.UTC, nowMs)

    private fun entry(
        kind: CgmLogKind,
        channel: String?,
        size: Int?,
        monoNs: Long = 0L,
        text: String = "",
        rxNs: Long? = null,
    ) = CgmLogEntry(
        wallMs = 0L, monoNs = monoNs, kind = kind, level = CgmLogLevel.D, topic = CgmLogTopic.NONE,
        channel = channel, text = text, bytes = size?.let(::ByteArray), value = null, opens = false, rxNs = rxNs,
    )

    private val host = FakeHost()
    private val console = CgmConsole(host)

    private fun run(input: String, v: CgmConsoleView = view()) = runBlocking { console.run(input, v) }

    @Test
    fun `every typed line is echoed to the log, blanks are not`() {
        run("  info ")
        run("   ")
        run("yes")
        assertEquals(listOf("> info", "> yes"), host.echoes)
    }

    @Test
    fun `a destructive command waits for yes`() {
        val prompt = run("hide")
        assertTrue(host.calls.isEmpty())
        assertEquals(CgmReplyTone.WARN, prompt.lines.single().tone)
        run("yes", view(nowMs = 1_000_000L + CONFIRM_WINDOW_MS))
        assertEquals(listOf("hide"), host.calls)
    }

    @Test
    fun `yes after the window runs nothing`() {
        run("hide")
        val late = run("yes", view(nowMs = 1_000_001L + CONFIRM_WINDOW_MS))
        assertTrue(host.calls.isEmpty())
        assertEquals(CgmReplyTone.ERR, late.lines.single().tone)
    }

    @Test
    fun `any other input cancels the pending command and runs itself`() {
        run("hide")
        run("scan")
        run("yes")
        assertTrue("hide" !in host.calls)
    }

    @Test
    fun `the refusal is asked again on yes`() {
        run("hide")
        run("yes", view(row = live.copy(authoritative = true)))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `yes with nothing pending runs nothing`() {
        run("yes")
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `the main sensor cannot be stopped or hidden`() {
        val main = live.copy(authoritative = true)
        assertEquals(CgmReplyTone.ERR, run("disconnect", view(main)).lines.single().tone)
        assertEquals(CgmReplyTone.ERR, run("hide", view(main)).lines.single().tone)
        run("yes", view(main))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `reconnect is throttled`() {
        run("reconnect", view(nowMs = 0L))
        run("reconnect", view(nowMs = RECONNECT_GAP_MS - 1))
        run("reconnect", view(nowMs = RECONNECT_GAP_MS))
        assertEquals(listOf("reconnect", "reconnect"), host.calls)
    }

    @Test
    fun `warmup takes only the descriptor's range`() {
        run("warmup 361")
        run("warmup -1")
        run("warmup x")
        run("warmup 45")
        assertEquals(listOf("warmup 45"), host.calls)
    }

    @Test
    fun `help lists only what this sensor offers`() {
        val names = run("help").lines.map { it.text.substringBefore(' ') }
        assertTrue("hide" in names)
        assertTrue("activate" !in names)
        assertTrue("bind" !in names)
        assertTrue("fetch" !in names)
    }

    @Test
    fun `unknown commands and a vanished sensor are refused`() {
        assertEquals(CgmReplyTone.ERR, run("frobnicate").lines.single().tone)
        assertEquals(CgmReplyTone.ERR, run("info", view(row = null)).lines.single().tone)
    }

    @Test
    fun `clear and grep act on the screen only`() {
        assertEquals(CgmConsoleEffect.Clear, run("clear").effect)
        assertEquals("ble tx", (run("grep ble tx").effect as CgmConsoleEffect.Grep).text)
        assertEquals(null, (run("grep").effect as CgmConsoleEffect.Grep).text)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `exit and quit leave the console and cancel a pending command`() {
        assertEquals(CgmConsoleEffect.Exit, run("exit").effect)
        run("hide")
        val quit = run("quit")
        assertEquals(CgmConsoleEffect.Exit, quit.effect)
        assertEquals("hide cancelled", quit.lines.single().text)
        run("yes")
        assertTrue(host.calls.isEmpty())
        assertTrue(run("help quit").lines.single().text.startsWith("exit|quit "))
    }

    @Test
    fun `counts outside their range are refused`() {
        assertEquals(CgmReplyTone.ERR, run("last 51").lines.single().tone)
        assertEquals(CgmReplyTone.ERR, run("raw 0").lines.single().tone)
    }

    @Test
    fun `decode walks the chunks raw lists, each with the lines decoded from it`() {
        val entries = listOf(
            entry(CgmLogKind.RX, "GlucoseData", 15, monoNs = 1),
            entry(CgmLogKind.RX, "GlucoseData", 20, monoNs = 2),
            entry(CgmLogKind.DEC, null, 2, monoNs = 3, text = "realtime 5: 110 mg/dL", rxNs = 2),
            entry(CgmLogKind.DEC, null, null, monoNs = 4, text = "patchControl ← top-up"),
            entry(CgmLogKind.RX, "F001", null, monoNs = 5),
            entry(CgmLogKind.DEC, null, null, monoNs = 6, text = "realtime 5 stored", rxNs = 2),
            entry(CgmLogKind.RX, "adv", 31, monoNs = 7),
        )
        val v = view(entries = entries)
        assertEquals(
            listOf(
                "00:00:00.000 GlucoseData · no decode" to CgmReplyTone.DIM,
                "00:00:00.000 GlucoseData" to CgmReplyTone.DIM,
                "realtime 5: 110 mg/dL" to CgmReplyTone.OUT,
                "00 00" to CgmReplyTone.DIM,
                "realtime 5 stored" to CgmReplyTone.OUT,
                "00:00:00.000 adv · no decode" to CgmReplyTone.DIM,
            ),
            run("decode 3", v).lines.map { it.text to it.tone },
        )
        assertEquals(3, run("raw 3", v).lines.count { it.tone == CgmReplyTone.DIM })
    }

    @Test
    fun `encrypt and decrypt are offered and run only where the family has them`() {
        val names = { row: CgmSensorRow -> run("help", view(row)).lines.map { it.text.substringBefore(' ') } }
        assertTrue("decrypt" !in names(live) && "encrypt" !in names(live))
        assertTrue("decrypt" in names(libre) && "encrypt" in names(libre))
        assertEquals(CgmReplyTone.ERR, run("decrypt", view(live)).lines.single().tone)
        assertEquals(CgmReplyTone.ERR, run("encrypt 3 1 00", view(live)).lines.single().tone)
        assertTrue(host.openedChunks.isEmpty() && host.calls.isEmpty())
    }

    @Test
    fun `completion is the rest of the first offered name, aliases included`() {
        assertEquals("it", completionOf("qu", live))
        assertEquals("it", completionOf("QU", live))
        assertEquals("connect", completionOf("re", live))
        assertEquals("xit", completionOf("e", live))
        assertEquals("ncrypt", completionOf("e", libre))
        assertEquals("", completionOf("exit", live))
        assertEquals("", completionOf("last 3", live))
        assertEquals("", completionOf("frob", live))
        assertEquals("", completionOf("", live))
    }

    @Test
    fun `an overlong usage keeps a space before its help`() {
        assertEquals("encrypt kind seq hex seal a frame; not sent", run("help encrypt", view(libre)).lines.single().text)
    }

    @Test
    fun `decrypt n opens the newest received chunks after the ones before them`() {
        val entries = listOf(
            entry(CgmLogKind.RX, "GlucoseData", 15),
            entry(CgmLogKind.DEC, "GlucoseData", 29),
            entry(CgmLogKind.RX, "GlucoseData", 20),
            entry(CgmLogKind.TX, "PatchControl", 13),
            entry(CgmLogKind.RX, "PatchStatus", 18),
            entry(CgmLogKind.RX, "GlucoseData", 15),
        )
        host.opened = CgmFrameResult.Done(
            listOf(
                CgmOpenedFrame(chunk = 1, sequence = 1, kind = 3, plaintext = byteArrayOf(0xDA.toByte(), 0x38)),
                CgmOpenedFrame(chunk = 2, sequence = 2, kind = null, plaintext = null),
            ),
        )
        val lines = run("decrypt 3", view(libre, entries = entries)).lines

        assertEquals(listOf("GlucoseData", "GlucoseData", "PatchStatus", "GlucoseData"), host.openedChunks.map { it.channel })
        assertEquals(listOf(15, 20, 18, 15), host.openedChunks.map { it.bytes.size })
        assertEquals(
            listOf(
                "00:00:00.000 GlucoseData · seq 1 · kind 3" to CgmReplyTone.DIM,
                "DA 38" to CgmReplyTone.OUT,
                "00:00:00.000 PatchStatus · seq 2 · no tag verifies" to CgmReplyTone.WARN,
                "00:00:00.000 GlucoseData · partial" to CgmReplyTone.DIM,
            ),
            lines.map { it.text to it.tone },
        )
    }

    @Test
    fun `decrypt hex opens one typed frame under every kind`() {
        host.opened = CgmFrameResult.Done(listOf(CgmOpenedFrame(0, 0x1234, 7, byteArrayOf(1, 2))))
        val lines = run("decrypt 3c 93 77ff", view(libre)).lines
        val chunk = host.openedChunks.single()
        assertEquals(null, chunk.channel)
        assertEquals(listOf(0x3C, 0x93, 0x77, 0xFF), chunk.bytes.map { it.toInt() and 0xFF })
        assertEquals(listOf("seq 4660 · kind 7", "01 02"), lines.map { it.text })
    }

    @Test
    fun `decrypt refuses bad hex, bad counts and a missing link`() {
        assertEquals(CgmReplyTone.ERR, run("decrypt 3c9377f", view(libre)).lines.single().tone)
        assertEquals(CgmReplyTone.ERR, run("decrypt 3c 93 zz", view(libre)).lines.single().tone)
        assertEquals(CgmReplyTone.ERR, run("decrypt 11", view(libre)).lines.single().tone)
        assertTrue(host.openedChunks.isEmpty())
        assertEquals("not linked", run("decrypt 3c9377", view(libre)).lines.single().text)
    }

    @Test
    fun `encrypt prints the sealed frame and passes refusals through`() {
        host.sealed = CgmFrameResult.Done(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        assertEquals("AB CD", run("encrypt 3 1 da 38 67", view(libre)).lines.single().text)
        assertEquals(listOf("seal 3 1 3 B"), host.calls)

        host.sealed = CgmFrameResult.Refused("kind 9: 0–7")
        assertEquals("kind 9: 0–7", run("encrypt 9 1 00", view(libre)).lines.single().text)

        assertEquals(CgmReplyTone.ERR, run("encrypt 3 x 00", view(libre)).lines.single().tone)
        assertEquals(CgmReplyTone.ERR, run("encrypt 3 1", view(libre)).lines.single().tone)
        assertEquals(2, host.calls.size)
    }
}
