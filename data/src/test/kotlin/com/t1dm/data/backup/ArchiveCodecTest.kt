package com.t1dm.data.backup

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
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

class ArchiveCodecTest {

    private fun render(write: (Archive.RecordWriter) -> Unit): String {
        val sw = StringWriter()
        write(Archive.RecordWriter(sw))
        return sw.toString()
    }

    private fun parse(line: String) = Archive.json.parseToJsonElement(line.trim()).jsonObject

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

    @Test
    fun `a note carrying newlines quotes and control characters survives intact`() {
        val nasty = "line one\nline\ttwo \"quoted\" \\ backslash  \r\n end"
        val d = dose(note = nasty)
        val line = render { Archive.write(it, d) }
        // A raw newline would split the record and garble every record after it.
        assertEquals("the record spans more than one line", 1, line.trimEnd('\n').lines().size)
        assertEquals(nasty, Archive.readDose(parse(line)).note)
    }

    @Test
    fun `a unicode note survives intact`() {
        val text = "café · 早餐 · 🥐 · Ω"
        assertEquals(text, Archive.readDose(parse(render { Archive.write(it, dose(note = text)) })).note)
    }

    @Test
    fun `a non-finite double round-trips as itself rather than breaking the line`() {
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val line = render { Archive.write(it, dose(units = v)) }
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

    @Test
    fun `a custom curve blob survives base64 byte for byte`() {
        val curve = doubleArrayOf(0.1, 0.25, 0.5, 0.15).toBlob()
        val back = Archive.readMeal(parse(render { Archive.write(it, meal(customCurve = curve)) }))
        assertArrayEquals(curve, back.customCurve)
    }

    @Test
    fun `a stroke's time bounds are recomputed from its points, not trusted from the file`() {
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
        // Same blob, a shape it cannot hold.
        val bad = render { Archive.write(it, c.copy(steps = 11)) }
        assertTrue(runCatching { Archive.readConformal(parse(bad)) }.isFailure)
    }

    @Test
    fun `a restored food is always custom and a restored insulin type is never builtin`() {
        // A file that could set these would smuggle rows past a reset.
        val foodLine = render { Archive.write(it, food()) }.replace("\"cat\"", "\"custom\":true,\"cat\"")
        assertTrue(Archive.readFood(parse(foodLine)).custom)

        val typeLine = render { Archive.write(it, insulinType()) }.replace("\"dm\"", "\"builtin\":true,\"dm\"")
        assertFalse(Archive.readInsulinType(parse(typeLine)).builtin)
    }

    @Test
    fun `a source and a profile take their authority flag from the caller, never from the file`() {
        val srcLine = render { Archive.write(it, source(authoritative = true)) }
        assertFalse("the archive dictated the authority flag", Archive.readSource(parse(srcLine), authoritative = false).authoritative)
        assertTrue(Archive.readSource(parse(srcLine), authoritative = true).authoritative)
    }

    @Test
    fun `the archive records WHICH source was authoritative, as a preference the reader may consult`() {
        // Recorded, not applied: the exactly-one-authoritative invariant is the local table's to keep.
        assertEquals(true, parse(render { Archive.write(it, source(authoritative = true)) }).bool("ac"))
        assertEquals(false, parse(render { Archive.write(it, source(authoritative = false)) }).bool("ac"))
    }

    @Test
    fun `a source record written before the authority flag existed still decodes`() {
        // No `ac`: the reader falls back to the most recently seen source.
        val old = """{"t":"source","sid":"s","vid":"v","dn":"d","wm":60,"aa":1,"ls":2}"""
        assertEquals("s", Archive.readSource(parse(old), authoritative = false).sourceId)
        assertNull(parse(old).bool("ac"))
    }

    @Test
    fun `a source's sensor model survives the round trip`() {
        val line = render { Archive.write(it, source(authoritative = true)) }
        assertEquals("v:model", parse(line).str("mid"))
        assertEquals("v:model", Archive.readSource(parse(line), authoritative = true).sensorModelId)
    }

    /** Must land the sensor in the same class `MIGRATION_10_11` gives it. */
    @Test
    fun `a source record written before the sensor model existed is classified as the migration would`() {
        val real = """{"t":"source","sid":"aidexx:ABC","vid":"aidexx","dn":"d","wm":60,"aa":1,"ls":2}"""
        assertEquals(CgmSensorModelId.AIDEX_X, Archive.readSource(parse(real), authoritative = false).sensorModelId)

        val debug = """{"t":"source","sid":"${CgmSourceId.DEBUG.value}","vid":"aidexx","dn":"d","wm":60,"aa":1,"ls":2}"""
        assertEquals(CgmSensorModelId.AIDEX_DEBUG, Archive.readSource(parse(debug), authoritative = false).sensorModelId)
    }

    /** Unlike `ac`, this IS read back from the file: it records what the user did. */
    @Test
    fun `a removed source survives the round trip as removed`() {
        val line = render { Archive.write(it, source(authoritative = false, hidden = true)) }
        assertEquals(true, parse(line).bool("hd"))
        assertEquals(true, Archive.readSource(parse(line), authoritative = false).hidden)
        assertEquals(false, Archive.readSource(parse(render { Archive.write(it, source(authoritative = false)) }), authoritative = false).hidden)
    }

    @Test
    fun `a source record written before removal existed decodes as listed`() {
        val old = """{"t":"source","sid":"s","vid":"v","dn":"d","wm":60,"aa":1,"ls":2}"""
        assertEquals(false, Archive.readSource(parse(old), authoritative = false).hidden)
    }

    private fun source(authoritative: Boolean, hidden: Boolean = false) = com.t1dm.data.db.CgmSourceEntity(
        sourceId = "s", vendorId = "v", sensorModelId = "v:model", advertName = null, displayName = "d", serialSuffix = null,
        authoritative = authoritative, active = authoritative,
        warmupWindowMin = 60, addedAtMs = 1L, lastSeenMs = 2L, hidden = hidden, ordinal = 0,
    )

    @Test
    fun `a sample round-trips including its nullable series`() {
        val s = SampleEntity(
            ts = 600_000L, tzOffsetMin = -120, bgMgdl = 98, bgSource = null,
            bgProvenance = ReadingProvenance.MEASURED, bgFlag = ReadingFlag.NORMAL,
            steps = 4210, mood = 4, hr = null, sleep = null, exercise = null, updatedAt = 9L,
        )
        assertEquals(s, Archive.readSample(parse(render { Archive.write(it, s) })))
        val empty = s.copy(bgMgdl = null, bgProvenance = null, bgFlag = null, steps = null, mood = null)
        assertEquals(empty, Archive.readSample(parse(render { Archive.write(it, empty) })))
    }

    @Test
    fun `the exercise scalar survives as fractional grams, and pre-v17 seconds do not`() {
        // Grams of carbohydrate equivalent, so fractional.
        val s = SampleEntity(
            ts = 600_000L, tzOffsetMin = 0, bgMgdl = null, bgSource = null,
            bgProvenance = null, bgFlag = null, steps = null, mood = null, hr = null, sleep = null,
            exercise = 2.5, updatedAt = 9L,
        )
        assertEquals(s, Archive.readSample(parse(render { Archive.write(it, s) })))

        // Pre-v17 archives carry active SECONDS under `ex`; reading those as grams would be a
        // hundredfold error, so the key moved to `exg` and `ex` is dropped.
        val legacy = parse("""{"t":"sample","ts":600000,"tz":0,"ex":300,"ua":9}""")
        assertNull(Archive.readSample(legacy).exercise)
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
        val back = Archive.readMeal(parse(render { Archive.write(it, meal().copy(id = 4242L)) }))
        assertEquals(0L, back.id)
        assertNull(back.customCurve)
    }

    @Test
    fun `a bout round-trips whole`() {
        val s = exerciseSession()
        assertEquals(s, Archive.readExercise(parse(render { Archive.write(it, s) })))
    }

    @Test
    fun `a bout still open restores still open`() {
        // Null `endMs`: a bout caught mid-recording, or one the process died inside.
        val open = exerciseSession().copy(endMs = null, interrupted = false)
        val line = render { Archive.write(it, open) }
        assertFalse("an absent end was written out as null", line.contains("null"))
        assertNull(Archive.readExercise(parse(line)).endMs)
    }

    @Test
    fun `a bout kind a later build introduced still restores`() {
        // `kind` is raw TEXT on purpose: an older build must read a newer build's bout.
        val line = """{"t":"exercise","cid":"c","st":1,"tz":0,"kd":"SWIM","as":60,"it":false,"ua":1}"""
        assertEquals("SWIM", Archive.readExercise(parse(line)).kind)
    }

    @Test
    fun `a bout carries no energy figure it could not justify`() {
        val s = exerciseSession().copy(distanceM = null, kcal = null)
        val back = Archive.readExercise(parse(render { Archive.write(it, s) }))
        assertNull(back.distanceM)
        assertNull(back.kcal)
    }

    @Test
    fun `a fix names its bout by clientId, never by the rowid`() {
        val f = exerciseFix()
        val line = render { Archive.write(it, f.copy(sessionId = 987_654_321L), "bout-1") }
        assertFalse("the local rowid travelled", line.contains("987654321"))
        val (cid, back) = Archive.readExerciseFix(parse(line))
        assertEquals("bout-1", cid)
        assertEquals(0L, back.sessionId)
        assertEquals(f.tsMs, back.tsMs)
        assertEquals(f.lat, back.lat, 0.0)
        assertEquals(f.lon, back.lon, 0.0)
        assertEquals(f.accuracyM, back.accuracyM, 0f)
        assertEquals(f.speedMps!!, back.speedMps!!, 0f)
    }

    @Test
    fun `a fix keeps enough precision to place it on a street`() {
        // Six decimals is ~0.1 m at the equator.
        val f = exerciseFix().copy(lat = 41.015137, lon = 28.979530)
        val back = Archive.readExerciseFix(parse(render { Archive.write(it, f, "bout-1") })).second
        assertEquals(41.015137, back.lat, 0.0)
        assertEquals(28.979530, back.lon, 0.0)
    }

    @Test
    fun `a fix with no speed omits it and decodes back as absent`() {
        val line = render { Archive.write(it, exerciseFix().copy(speedMps = null), "bout-1") }
        assertFalse("null was written out", line.contains("null"))
        assertNull(Archive.readExerciseFix(parse(line)).second.speedMps)
    }

    @Test
    fun `a fix missing its parent is refused rather than orphaned`() {
        val line = """{"t":"exerciseFix","ts":1,"la":1.0,"lo":2.0,"acc":5.0}"""
        assertTrue(runCatching { Archive.readExerciseFix(parse(line)) }.isFailure)
    }

    private fun exerciseSession() = ExerciseSessionEntity(
        clientId = "cccccccc-dddd-eeee-ffff-000000000000",
        startMs = 1_700_000_123_000L,
        endMs = 1_700_003_456_000L,
        tzOffsetMin = 180,
        kind = "RUN",
        activeSec = 3_300,
        distanceM = 8_412.5,
        kcal = 611,
        interrupted = false,
        note = null,
        updatedAt = 1_700_003_456_000L,
    )

    private fun exerciseFix() = ExerciseFixEntity(
        sessionId = 7L,
        tsMs = 1_700_000_127_000L,
        lat = 41.015137,
        lon = 28.979530,
        accuracyM = 6.5f,
        speedMps = 3.1f,
    )

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
        windowDays = 14, fittedAtMs = 5L, sourceId = "aidexx:TESTSERIAL",
    )
}
