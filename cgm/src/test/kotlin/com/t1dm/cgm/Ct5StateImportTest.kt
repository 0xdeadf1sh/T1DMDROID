package com.t1dm.cgm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every case is a refusal: a repaired/rounded/guessed field strands a sensor. No real BSN here. */
class Ct5StateImportTest {

    @Test
    fun `a complete document yields the sensor and its state`() {
        val import = Ct5StateImport.parse(document())
        requireNotNull(import)
        assertEquals(BSN, import.bsn)
        val s = import.state
        assertEquals(0xAB, s.cipherId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), s.a)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), s.b)
        assertEquals("4271", s.randomId)
        assertEquals(SSN, s.ssn)
    }

    @Test
    fun `K and R arrive as exact hundredths`() {
        val s = requireNotNull(Ct5StateImport.parse(document())).state
        assertEquals(125, s.kX100)
        assertEquals(100, s.rX100)
    }

    /** Origin of every reading timestamp: `bindTimeMs + glucoseId * SAMPLE_INTERVAL_MS`. */
    @Test
    fun `the bind instant is taken from the document, to the millisecond`() {
        val s = requireNotNull(Ct5StateImport.parse(document())).state
        assertEquals(1_786_967_173_000L, s.bindTimeMs)
    }

    /** Recorded unfinished, the next reconnect resumes and rewrites 0x38 over the only password. */
    @Test
    fun `an imported bind is recorded as finished`() {
        assertTrue(requireNotNull(Ct5StateImport.parse(document())).state.initialised)
    }

    /** A dry run writes a full-looking document for a bind that never reached the sensor. */
    @Test
    fun `a dry run is refused`() {
        assertNull(Ct5StateImport.parse(document(dryRun = "true")))
    }

    /** The field is the only way to tell a dry run from a real bind. */
    @Test
    fun `a document with no dry-run field is refused`() {
        assertNull(Ct5StateImport.parse(document(dryRun = null)))
    }

    /** The bind client's own file carries no BSN; the key is added by hand. */
    @Test
    fun `a document with no BSN is refused`() {
        assertNull(Ct5StateImport.parse(document(bsn = null)))
    }

    @Test
    fun `a BSN that is not ten digits is refused`() {
        assertNull(Ct5StateImport.parse(document(bsn = "\"012345678\"")))
        assertNull(Ct5StateImport.parse(document(bsn = "\"01234567890\"")))
        assertNull(Ct5StateImport.parse(document(bsn = "\"01234S6789\"")))
    }

    /** Null until the `0x30` reply is read. */
    @Test
    fun `a document whose CIPHER_ID is still null is refused`() {
        assertNull(Ct5StateImport.parse(document(cipherId = "null")))
    }

    @Test
    fun `a CIPHER_ID outside one byte is refused`() {
        assertNull(Ct5StateImport.parse(document(cipherId = "256")))
        assertNull(Ct5StateImport.parse(document(cipherId = "-1")))
    }

    @Test
    fun `a nonce of the wrong length or range is refused`() {
        assertNull(Ct5StateImport.parse(document(a = "[1, 2, 3]")))
        assertNull(Ct5StateImport.parse(document(a = "[1, 2, 3, 4, 5]")))
        assertNull(Ct5StateImport.parse(document(a = "[1, 2, 3, 300]")))
        assertNull(Ct5StateImport.parse(document(b = "[]")))
    }

    /** The only unbind password there will ever be. */
    @Test
    fun `a RANDOM_ID that is not four digits is refused`() {
        assertNull(Ct5StateImport.parse(document(randomId = "\"427\"")))
        assertNull(Ct5StateImport.parse(document(randomId = "\"42710\"")))
        assertNull(Ct5StateImport.parse(document(randomId = "\"42A1\"")))
    }

    /** Glucose is exactly inversely proportional to `K`: refused, never rounded. */
    @Test
    fun `a K that is not an exact hundredth is refused`() {
        assertNull(Ct5StateImport.parse(document(k = "1.253")))
        assertNull(Ct5StateImport.parse(document(k = "0")))
        assertNull(Ct5StateImport.parse(document(k = "-1.25")))
    }

    @Test
    fun `an SSN that is empty or not ASCII is refused`() {
        assertNull(Ct5StateImport.parse(document(ssn = "\"\"")))
        assertNull(Ct5StateImport.parse(document(ssn = "\"00552228000801251µ\"")))
    }

    @Test
    fun `a bind instant that is absent or unparseable is refused`() {
        assertNull(Ct5StateImport.parse(document(bindStarted = null)))
        assertNull(Ct5StateImport.parse(document(bindStarted = "\"2026-08-17 11:46:13\"")))
        assertNull(Ct5StateImport.parse(document(bindStarted = "\"yesterday\"")))
    }

    @Test
    fun `text that is not a JSON object is refused`() {
        assertNull(Ct5StateImport.parse(""))
        assertNull(Ct5StateImport.parse("[1, 2, 3]"))
        assertNull(Ct5StateImport.parse("{\"BSN\": "))
    }

    /** A rotating resolvable-random address: device-identifying content this repo does not keep. */
    @Test
    fun `the bind client's address is ignored, not stored`() {
        val withAddress = requireNotNull(Ct5StateImport.parse(document()))
        val without = requireNotNull(Ct5StateImport.parse(document(address = null)))
        assertEquals(withAddress.state, without.state)
        assertArrayEquals(without.state.encode(), withAddress.state.encode())
    }

    /** The bind client's file carries keys this parser does not name: log, note, written-at stamp. */
    @Test
    fun `unknown keys are ignored`() {
        val text = document().dropLast(1) + ""","log": [{"at_utc": "x"}], "note": "n", "written_utc": "x"}"""
        assertEquals(BSN, requireNotNull(Ct5StateImport.parse(text)).bsn)
    }

    private fun document(
        bsn: String? = "\"$BSN\"",
        a: String = "[1, 2, 3, 4]",
        b: String = "[5, 6, 7, 8]",
        randomId: String = "\"4271\"",
        cipherId: String = "171",
        k: String = "1.25",
        r: String = "1.0",
        ssn: String = "\"$SSN\"",
        bindStarted: String? = "\"2026-08-17T11:46:13Z\"",
        dryRun: String? = "false",
        address: String? = "\"AA:BB:CC:DD:EE:FF\"",
    ): String = buildString {
        append("{")
        address?.let { append(""""address": $it,""") }
        bsn?.let { append(""""BSN": $it,""") }
        append(""""A": $a, "B": $b, "RANDOM_ID": $randomId, "CIPHER_ID": $cipherId,""")
        append(""""K": $k, "R": $r, "SSN": $ssn""")
        bindStarted?.let { append(""","bind_started_utc": $it""") }
        dryRun?.let { append(""","dry_run": $it""") }
        append("}")
    }

    private companion object {
        /** Not a real serial — see [Ct5Constants.NAME_PREFIX]. */
        const val BSN = "0123456789"

        /** Shaped like the 21-character identity form, and not any sensor's. */
        const val SSN = "000000000000000000000"
    }
}
