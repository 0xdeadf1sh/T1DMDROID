package com.t1dm.app.settings

import com.t1dm.core.model.PaintStroke
import com.t1dm.data.db.PaintStrokeBlob
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup envelope's compatibility contract. The point of the layer is that adding drawings to the
 * file must not have cost anyone the backups they already hold, so the load-bearing case is the FIRST
 * one below: a legacy flat settings document must still be recognised, and must reach
 * [SettingsStore.importJson] byte-for-byte unchanged (that importer validates its `format` tag at the
 * ROOT of whatever it is handed, so any rewrapping would break it).
 *
 * `SettingsStore` itself is not exercised here — it goes through `org.json`, which on the host JVM is a
 * different implementation from Android's. The seam under test is precisely the one that chooses which
 * document to hand it.
 */
class ConfigBackupTest {

    private val T0 = 1_721_000_000_000L

    /** Exactly what `SettingsStore.exportJson()` writes: flat at the root, `kv` beside `format`. */
    private val LEGACY_FLAT = """
        {
          "format": "t1dm.config",
          "version": 1,
          "exportedAtMs": 1721000000000,
          "kv": {
            "alarm.low_mgdl": "80",
            "graph.window_hours": "6",
            "ui.theme": "umbrella"
          }
        }
    """.trimIndent()

    private fun stroke(
        id: Long = 0L,
        createdAtMs: Long = T0,
        tool: String = "marker",
        colorArgb: Int = 0x80FF3311.toInt(),
        widthDp: Float = 7f,
        n: Int = 4,
    ) = PaintStroke(
        id = id, createdAtMs = createdAtMs, tool = tool, colorArgb = colorArgb, widthDp = widthDp,
        tsMs = LongArray(n) { createdAtMs + it * 1000L },
        yFrac = FloatArray(n) { 0.1f + it * 0.05f },
    )

    // ── backward compatibility: the whole reason the envelope is shaped this way ─────────────────

    @Test fun `a legacy flat settings file is still recognised`() {
        val parsed = ConfigBackup.parse(LEGACY_FLAT)
        assertNotNull("the flat document must be recognised as a settings document", parsed.configJson)
        assertTrue(parsed.paintings.isEmpty())
        assertEquals(0, parsed.skippedPaintings)
    }

    @Test fun `a legacy flat settings file is passed through verbatim`() {
        // Not merely equivalent JSON: the SAME text, so `SettingsStore.importJson` reads exactly the
        // root it has always read and no re-serialisation can perturb a value on the way.
        assertEquals(LEGACY_FLAT, ConfigBackup.parse(LEGACY_FLAT).configJson)
    }

    @Test fun `the new wrapped shape yields the inner settings document`() {
        val doc = ConfigBackup.wrap(LEGACY_FLAT, listOf(stroke()))
        val parsed = ConfigBackup.parse(doc.json)
        val inner = parsed.configJson!!
        // The inner document keeps its own root-level format tag — that is what makes it a config file.
        assertTrue("inner doc lost its format tag: $inner", inner.contains("\"t1dm.config\""))
        assertTrue(inner.contains("\"kv\""))
        assertTrue(inner.contains("alarm.low_mgdl"))
        // …and it is NOT the envelope.
        assertTrue("the envelope leaked into the config document", !inner.contains("\"paintings\""))
    }

    @Test fun `a drawings-only file imports its drawings and claims no settings`() {
        // The settings importer refuses a file with no recognised keys; a file that never claimed to
        // carry settings must not trip that guard, so it reports no config document at all.
        val doc = ConfigBackup.wrap(LEGACY_FLAT, listOf(stroke()))
        val strokesOnly = "{\"format\":\"t1dm.backup\",\"version\":1,\"paintings\":" +
            doc.json.substringAfter("\"paintings\":").substringBeforeLast("}") + "}"
        val parsed = ConfigBackup.parse(strokesOnly)
        assertNull(parsed.configJson)
        assertEquals(1, parsed.paintings.size)
    }

    @Test fun `an unrelated JSON object is handed on to be refused, not read as a drawings-only file`() {
        // The drawings-only verdict says "this file legitimately has no settings", and the caller acts
        // on it by skipping the settings importer entirely — so a foreign file reaching it would import
        // as zero keys and no error, which the caller can only render as success. Handing the text
        // through instead puts the rejection back where it has always lived: the root `format` tag.
        val parsed = ConfigBackup.parse("""{"hello":"world"}""")
        assertEquals("""{"hello":"world"}""", parsed.configJson)
        assertTrue(parsed.paintings.isEmpty())
    }

    @Test fun `a drawings-only file is recognised by its own format tag`() {
        // The positive marker, not the absence of everything else: `paintings` alone identifies one too.
        val parsed = ConfigBackup.parse("""{"format":"${ConfigBackup.FORMAT}","version":1}""")
        assertNull(parsed.configJson)
        assertTrue(parsed.paintings.isEmpty())
    }

    @Test fun `a file that is not JSON is refused in plain language`() {
        val e = runCatching { ConfigBackup.parse("not json at all") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("could not parse JSON"))
    }

    // ── the drawings round-trip ─────────────────────────────────────────────────────────────────

    @Test fun `a stroke round-trips through the envelope`() {
        val s = stroke(id = 42L, createdAtMs = T0 + 5_000L, tool = "chalk", colorArgb = -12345, widthDp = 9.5f, n = 6)
        val back = ConfigBackup.parse(ConfigBackup.wrap(LEGACY_FLAT, listOf(s)).json).paintings.single()
        assertEquals(s.createdAtMs, back.createdAtMs)
        assertEquals(s.tool, back.tool)
        assertEquals(s.colorArgb, back.colorArgb)
        assertEquals(s.widthDp, back.widthDp, 0f)
        assertEquals(s.tsMs.toList(), back.tsMs.toList())
        assertEquals(s.yFrac.toList(), back.yFrac.toList())
        // The row id is deliberately NOT carried: an imported stroke is a new row on this device.
        assertEquals(0L, back.id)
    }

    @Test fun `an unknown tool name survives the round trip`() {
        // A stroke authored by a later build must not fail to restore on this one.
        val back = ConfigBackup.parse(
            ConfigBackup.wrap(LEGACY_FLAT, listOf(stroke(tool = "airbrush"))).json,
        ).paintings.single()
        assertEquals("airbrush", back.tool)
    }

    @Test fun `a flood pen's width survives the JSON round trip intact`() {
        // Width is the one attribute that crosses the envelope as a decimal literal rather than as an
        // integer or a base64 blob, so a pen an order of magnitude wider than any before it is worth
        // pinning: a stroke that came back narrower would be silently redrawn as a different mark.
        val back = ConfigBackup.parse(
            ConfigBackup.wrap(LEGACY_FLAT, listOf(stroke(tool = "broad", widthDp = 96f))).json,
        ).paintings.single()
        assertEquals("broad", back.tool)
        assertEquals(96f, back.widthDp, 0f)
    }

    @Test fun `drawings come back in paint order`() {
        val doc = ConfigBackup.wrap(
            LEGACY_FLAT,
            listOf(stroke(createdAtMs = T0 + 300), stroke(createdAtMs = T0 + 100), stroke(createdAtMs = T0 + 200)),
        )
        assertEquals(
            listOf(T0 + 100, T0 + 200, T0 + 300),
            ConfigBackup.parse(doc.json).paintings.map { it.createdAtMs },
        )
    }

    @Test fun `a single unreadable drawing is skipped, not fatal`() {
        val doc = ConfigBackup.wrap(LEGACY_FLAT, listOf(stroke(createdAtMs = T0 + 1)))
        // Corrupt the base64 payload of the one painting.
        val broken = doc.json.replace(Regex("\"points\": \"[^\"]*\""), "\"points\": \"!!!not-base64!!!\"")
        assertTrue("the fixture did not actually corrupt anything", broken != doc.json)
        val parsed = ConfigBackup.parse(broken)
        assertNotNull(parsed.configJson) // the settings still restore
        assertTrue(parsed.paintings.isEmpty())
        assertEquals(1, parsed.skippedPaintings)
    }

    @Test fun `a drawing declaring zero points is skipped, not handed on to the store`() {
        // A well-formed v1 blob with count == 0 decodes cleanly, but an empty stroke has no time
        // bounds and `T1dmRepository.addPaintStroke` refuses it. Carrying one out of `parse` would
        // therefore abort the import AT THE INSERT — after the settings had already been committed
        // and with every later drawing in the array still unimported.
        val doc = ConfigBackup.wrap(LEGACY_FLAT, listOf(stroke(createdAtMs = T0 + 1), stroke(createdAtMs = T0 + 2)))
        val zero = Base64.getEncoder().encodeToString(PaintStrokeBlob.encode(LongArray(0), FloatArray(0)))
        val doctored = doc.json.replaceFirst(Regex("\"points\": \"[^\"]*\""), "\"points\": \"$zero\"")
        assertTrue("the fixture did not actually replace anything", doctored != doc.json)
        val parsed = ConfigBackup.parse(doctored)
        assertNotNull("the settings must still restore", parsed.configJson)
        assertEquals(1, parsed.skippedPaintings)
        assertEquals(listOf(T0 + 2), parsed.paintings.map { it.createdAtMs })
    }

    @Test fun `an empty layer produces an empty paintings array, not a missing one`() {
        val doc = ConfigBackup.wrap(LEGACY_FLAT, emptyList())
        assertTrue(doc.json.contains("\"paintings\""))
        assertNull(doc.note)
        assertTrue(ConfigBackup.parse(doc.json).paintings.isEmpty())
    }

    // ── the size guard ──────────────────────────────────────────────────────────────────────────

    @Test fun `the export caps the stroke count and keeps the newest`() {
        val many = (1..ConfigBackup.MAX_PAINTINGS + 25).map { stroke(createdAtMs = T0 + it, n = 2) }
        val kept = ConfigBackup.capped(many)
        assertEquals(ConfigBackup.MAX_PAINTINGS, kept.size)
        assertEquals(T0 + 26, kept.first().createdAtMs) // the 25 oldest are the ones dropped
        assertEquals(T0 + ConfigBackup.MAX_PAINTINGS + 25, kept.last().createdAtMs)
    }

    @Test fun `the export caps the total point count`() {
        // Ten strokes of a tenth of the budget each, plus one more that cannot fit.
        val big = ConfigBackup.MAX_POINTS / 10
        val strokes = (1..11).map { stroke(createdAtMs = T0 + it, n = big) }
        val kept = ConfigBackup.capped(strokes)
        assertEquals(10, kept.size)
        assertTrue(kept.sumOf { it.size } <= ConfigBackup.MAX_POINTS)
        assertEquals(T0 + 2, kept.first().createdAtMs) // the oldest is what was dropped
    }

    @Test fun `an over-large export says what it left out`() {
        val many = (1..ConfigBackup.MAX_PAINTINGS + 3).map { stroke(createdAtMs = T0 + it, n = 2) }
        val doc = ConfigBackup.wrap(LEGACY_FLAT, many)
        assertNotNull(doc.note)
        assertTrue(doc.note!!.contains("3 older drawings"))
    }

    @Test fun `a within-budget export is silent`() {
        assertNull(ConfigBackup.wrap(LEGACY_FLAT, listOf(stroke(), stroke(createdAtMs = T0 + 1))).note)
    }

    // ── the envelope's own shape ────────────────────────────────────────────────────────────────

    @Test fun `the envelope is tagged and nests the config under its own key`() {
        val doc = ConfigBackup.wrap(LEGACY_FLAT, emptyList())
        assertTrue(doc.json.contains("\"format\": \"${ConfigBackup.FORMAT}\""))
        assertTrue(doc.json.contains("\"config\""))
        // Detection is on STRUCTURE, not on this tag: a wrapper missing it still parses as wrapped.
        val untagged = doc.json.replace("\"format\": \"${ConfigBackup.FORMAT}\",", "")
        assertNotNull(ConfigBackup.parse(untagged).configJson)
    }
}
