package com.t1dm.data.backup

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.PaintStrokeBlob
import com.t1dm.data.db.PaintStrokeEntity
import com.t1dm.data.db.SampleEntity
import com.t1dm.data.db.toBlob
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * The archive's RECORD codec, host-side. Pure in both directions — no Room, no Android — which is
 * the whole reason it was factored out of the reader and writer that drive the database.
 *
 * What these pin is the property the format lives or dies by: a row written and read back is the
 * same row. Everything else in a backup is plumbing around that.
 */
class ArchiveCodecTest {

    private fun render(write: (Archive.RecordWriter) -> Unit): String {
        val sw = StringWriter()
        write(Archive.RecordWriter(sw))
        return sw.toString()
    }

    private fun parse(line: String) = Archive.json.parseToJsonElement(line.trim()).jsonObject

    // ── readings: the row that repeats six figures of times ───────────────────────────────────

    @Test
    fun `a reading round-trips whole`() {
        val r = CgmReadingEntity(
            sourceId = "aidex-1234",
            tsMs = 1_712_345_400_000L,
            bgMgdl = 132,
            trendTenthsPerMin = -14,
            minFromStart = 4321,
            quality = 3,
            provenance = ReadingProvenance.MEASURED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 180,
            rxWallMs = 1_712_345_401_234L,
            rssi = -71,
        )
        assertEquals(r, Archive.readReading(parse(render { Archive.write(it, r) })))
    }

    @Test
    fun `a reading's null columns are omitted from the line and decode back as null`() {
        val r = CgmReadingEntity(
            sourceId = "s", tsMs = 300_000L, bgMgdl = null, trendTenthsPerMin = null,
            minFromStart = null, quality = null, provenance = ReadingProvenance.INTERPOLATED,
            flag = ReadingFlag.WARMUP, tzOffsetMin = 0, rxWallMs = 1L, rssi = null,
        )
        val line = render { Archive.write(it, r) }
        // The point of the omission: five absent columns cost five absent keys, not five "null"s.
        assertFalse("null was written out", line.contains("null"))
        assertEquals(r, Archive.readReading(parse(line)))
    }

    @Test
    fun `an unknown enum name fails only its own record`() {
        val line = """{"t":"reading","s":"s","ts":300000,"pv":"TELEPATHY","fl":"NORMAL","tz":0,"rx":1}"""
        val thrown = runCatching { Archive.readReading(parse(line)) }.exceptionOrNull()
        assertTrue("a name a later build introduced must be refused", thrown is IllegalArgumentException)
    }

    @Test
    fun `a missing required field is refused rather than defaulted`() {
        val line = """{"t":"reading","s":"s","pv":"MEASURED","fl":"NORMAL","tz":0,"rx":1}"""
        assertTrue(runCatching { Archive.readReading(parse(line)) }.isFailure)
    }

    // ── strings: the one hazard a line-delimited format has ───────────────────────────────────

    @Test
    fun `a note carrying newlines quotes and control characters survives intact`() {
        val nasty = "line one\nline\ttwo \"quoted\" \\ backslash  \r\n end"
        val d = dose(note = nasty)
        val line = render { Archive.write(it, d) }
        // If the escaper let a raw newline through, this record would be two lines and every record
        // after it would be garbage. One line is the assertion that matters most in this file.
        assertEquals("the record spans more than one line", 1, line.trimEnd('\n').lines().size)
        assertEquals(nasty, Archive.readDose(parse(line)).note)
    }

    @Test
    fun `a unicode note survives intact`() {
        val text = "café · 早餐 · 🥐 · Ω"
        assertEquals(text, Archive.readDose(parse(render { Archive.write(it, dose(note = text)) })).note)
    }

    // ── numbers ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a non-finite double round-trips as itself rather than breaking the line`() {
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val line = render { Archive.write(it, dose(units = v)) }
            // The line must still be valid JSON — a bare NaN token would make the whole record
            // unparseable and silently drop a logged dose on restore.
            val back = Archive.readDose(parse(line)).units
            if (v.isNaN()) assertTrue("NaN did not survive", back.isNaN()) else assertEquals(v, back, 0.0)
        }
    }

    @Test
    fun `a stroke width keeps float precision without widening noise`() {
        val s = stroke(widthDp = 4.2f)
        val line = render { Archive.write(it, s) }
        assertFalse("the float was widened to a double before printing", line.contains("4.19999"))
        assertEquals(4.2f, Archive.readStroke(parse(line)).widthDp, 0f)
    }

    // ── blobs and derived columns ─────────────────────────────────────────────────────────────

    @Test
    fun `a custom curve blob survives base64 byte for byte`() {
        val curve = doubleArrayOf(0.1, 0.25, 0.5, 0.15).toBlob()
        val back = Archive.readMeal(parse(render { Archive.write(it, meal(customCurve = curve)) }))
        assertArrayEquals(curve, back.customCurve)
    }

    @Test
    fun `a stroke's time bounds are recomputed from its points, not trusted from the file`() {
        // Deliberately out of order and doubling back, so first-and-last would give the wrong answer.
        val ts = longArrayOf(5_000L, 1_000L, 9_000L, 3_000L)
        val s = stroke(points = PaintStrokeBlob.encode(ts, FloatArray(4) { 0.5f }))
        val back = Archive.readStroke(parse(render { Archive.write(it, s) }))
        assertEquals(1_000L, back.minTsMs)
        assertEquals(9_000L, back.maxTsMs)
    }

    @Test
    fun `a corrupt stroke polyline is refused`() {
        val line = """{"t":"stroke","ca":1,"tl":"marker","col":-1,"wd":4.0,"pts":"bm90IGEgc3Ryb2tl"}"""
        assertTrue(runCatching { Archive.readStroke(parse(line)) }.isFailure)
    }

    @Test
    fun `a conformal delta whose blob disagrees with its declared shape is refused`() {
        val c = conformal(steps = 12, nQuantiles = 7)
        val good = Archive.readConformal(parse(render { Archive.write(it, c) }))
        assertEquals(12, good.steps)
        // Same blob, a shape it cannot possibly hold: applying it to a fan would misread every band.
        val bad = render { Archive.write(it, c.copy(steps = 11)) }
        assertTrue(runCatching { Archive.readConformal(parse(bad)) }.isFailure)
    }

    // ── flags the file must not be allowed to set ─────────────────────────────────────────────

    @Test
    fun `a restored food is always custom and a restored insulin type is never builtin`() {
        // Both flags gate the reset's "keep what a fresh install ships" sweep. If a file could set
        // them, a hand-edited archive would smuggle rows past an erase the user asked for.
        val foodLine = render { Archive.write(it, food()) }.replace("\"cat\"", "\"custom\":true,\"cat\"")
        assertTrue(Archive.readFood(parse(foodLine)).custom)

        val typeLine = render { Archive.write(it, insulinType()) }.replace("\"dm\"", "\"builtin\":true,\"dm\"")
        assertFalse(Archive.readInsulinType(parse(typeLine)).builtin)
    }

    @Test
    fun `a source and a profile take their active flag from the caller, never from the file`() {
        val srcLine = render {
            Archive.write(
                it,
                com.t1dm.data.db.CgmSourceEntity(
                    sourceId = "s", vendorId = "v", displayName = "d", serialSuffix = null,
                    active = true, warmupWindowMin = 60, addedAtMs = 1L, lastSeenMs = 2L,
                ),
            )
        }
        assertFalse("the archive dictated the active flag", Archive.readSource(parse(srcLine), active = false).active)
        assertTrue(Archive.readSource(parse(srcLine), active = true).active)
    }

    // ── the wide projection ───────────────────────────────────────────────────────────────────

    @Test
    fun `a sample round-trips including its nullable series`() {
        val s = SampleEntity(
            ts = 600_000L, tzOffsetMin = -120, bgMgdl = 98,
            bgProvenance = ReadingProvenance.MEASURED, bgFlag = ReadingFlag.NORMAL,
            steps = 4210, mood = 4, hr = null, sleep = null, exercise = null, updatedAt = 9L,
        )
        assertEquals(s, Archive.readSample(parse(render { Archive.write(it, s) })))
        val empty = s.copy(bgMgdl = null, bgProvenance = null, bgFlag = null, steps = null, mood = null)
        assertEquals(empty, Archive.readSample(parse(render { Archive.write(it, empty) })))
    }

    @Test
    fun `a meal and a dose round-trip whole`() {
        val m = meal()
        assertEquals(m, Archive.readMeal(parse(render { Archive.write(it, m) })))
        val d = dose()
        assertEquals(d.clientId, Archive.readDose(parse(render { Archive.write(it, d) })).clientId)
        assertEquals(d.units, Archive.readDose(parse(render { Archive.write(it, d) })).units, 0.0)
    }

    @Test
    fun `the autogenerated row id is never carried across`() {
        // An archived row becomes a NEW row on the restoring device; carrying its id would be a claim
        // on one that device may already have given to something else.
        val back = Archive.readMeal(parse(render { Archive.write(it, meal().copy(id = 4242L)) }))
        assertEquals(0L, back.id)
        assertNull(back.customCurve)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private fun dose(note: String? = null, units: Double = 3.5) = LoggedDoseEntity(
        clientId = "11111111-2222-3333-4444-555555555555",
        tsMs = 300_000L, kind = DoseKind.BOLUS, units = units, durationMin = 300.0,
        k = 2.0, theta = 25.0, kaPerHour = null, kePerHour = null, customCurve = null,
        tzOffsetMin = 0, note = note, updatedAt = 1L,
    )

    private fun meal(customCurve: ByteArray? = null) = LoggedMealEntity(
        clientId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        tsMs = 600_000L, grams = 45.0, gi = 62.0, k = 1.8, theta = 18.0, durationMin = 240.0,
        customCurve = customCurve, tzOffsetMin = 60, note = null, updatedAt = 2L,
    )

    private fun stroke(widthDp: Float = 4f, points: ByteArray? = null) = PaintStrokeEntity(
        createdAtMs = 1_700_000_000_000L,
        tool = "marker",
        colorArgb = -0x10000,
        widthDp = widthDp,
        minTsMs = 0L,
        maxTsMs = 0L,
        points = points ?: PaintStrokeBlob.encode(longArrayOf(1L, 2L), floatArrayOf(0.1f, 0.9f)),
    )

    private fun food() = FoodEntity(
        name = "Sourdough", brand = null, carbsPer100g = 48.0, gi = 54.0,
        category = "bread", source = "user", custom = true, customCurve = null, updatedAt = 3L,
    )

    private fun insulinType() = InsulinTypeEntity(
        name = "Fiasp", kind = DoseKind.BOLUS, durationMin = 300.0, k = 2.0, theta = 22.0,
        kaPerHour = null, kePerHour = null, customCurve = null, builtin = false, updatedAt = 4L,
    )

    private fun conformal(steps: Int, nQuantiles: Int) = ConformalDeltaEntity(
        modelId = "t1dmai-best",
        steps = steps,
        nQuantiles = nQuantiles,
        deltaBlob = DoubleArray(steps * nQuantiles) { it * 0.5 }.toBlob(),
        nCal = 400, nEval = 120, maxAbsDeltaMgdl = 18.0,
        cov90Raw = 0.81, cov90Cal = 0.90, meanWidth90Raw = 60.0, meanWidth90Cal = 72.0,
        windowDays = 14, fittedAtMs = 5L,
    )
}
