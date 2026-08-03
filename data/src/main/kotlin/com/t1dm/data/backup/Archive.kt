package com.t1dm.data.backup

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.PaintStrokeBlob
import com.t1dm.data.db.PaintStrokeEntity
import com.t1dm.data.db.SampleEntity
import com.t1dm.data.db.SavedMealEntity
import com.t1dm.data.db.SavedMealItemEntity
import com.t1dm.data.db.ServerProfileEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.Writer
import java.util.Base64

/**
 * The `t1dm.archive` format: the FULL local record — every reading, every logged event, the
 * user-authored catalogues, the drawings, and the settings document — as **gzipped JSON Lines**.
 *
 * ```
 * {"format":"t1dm.archive","version":1,"createdAtMs":…,"schema":10,"app":"0.22.0","config":{…}}
 * {"t":"reading","s":"…","ts":1712345400000,"bg":132,…}
 * {"t":"meal","cid":"…","ts":…,"g":45.0,…}
 * …
 * {"t":"end","reading":105120,"sample":105120,"meal":812,…}
 * ```
 *
 * **Why lines rather than one JSON document.** A year of five-minute readings is ~105 000 rows, and
 * every whole-document shape — a `JsonObject` tree, a `List<Entity>`, a rendered `String` — costs
 * tens of megabytes of heap on a phone that is also running inference. Records on their own lines
 * mean the writer appends one at a time and the reader parses one at a time, so peak memory is a
 * single row plus the gzip window regardless of how long the user has been running the app. It also
 * buys two things a monolithic document cannot:
 *
 * - **Per-record fault isolation.** One unparseable line is skipped and counted; the restore
 *   continues. This extends the philosophy the drawings import already had — one bad blob must not
 *   cost the user everything else in the file — to the whole schema.
 * - **Detectable truncation.** A backup cut short by a full disk or a killed process is missing its
 *   `end` record, so it is *recognisably* incomplete rather than a plausible-looking partial that
 *   restores quietly short. Without a terminator there is no way to tell one from the other.
 *
 * The price is that the file is not a single valid JSON document. It is gzipped and not meant for
 * hand-editing, so that costs nothing real.
 *
 * **Keys are short** because the common ones repeat six figures of times, and **null fields are
 * omitted** rather than emitted as `null` — `cgm_reading` alone carries five nullable columns, so
 * writing them out would spend a meaningful fraction of the file on the word "null". An absent key
 * decodes as null, which is what makes the omission lossless.
 *
 * Enums ride **by name**, matching `Converters`: reordering an enum can then never silently
 * reinterpret an archived row, and a name a later build introduced fails only its own record.
 * BLOB columns ride as base64 of the SAME little-endian f64 encoding the columns already use
 * ([com.t1dm.data.db.Blobs], [com.t1dm.data.db.PaintStrokeBlob]) — re-expanding a polyline into
 * JSON numbers would triple the file and give the archive a second geometry format to keep in step.
 */
object Archive {

    const val FORMAT = "t1dm.archive"
    const val VERSION = 1

    /** The file extension the panel writes. Distinct from `.json` on purpose: the bytes are gzip,
     *  and a `.json` that no text editor can open is a worse lie than an opaque extension. */
    const val EXTENSION = "t1dmbak"

    /** GZIP's two magic bytes, as `InputStream.read()` returns them (0–255). The reader sniffs
     *  these rather than trusting the extension, so a file renamed by a mail client or a cloud sync
     *  still restores — and a legacy uncompressed backup is recognised by their absence. */
    const val GZIP_MAGIC_0 = 0x1f
    const val GZIP_MAGIC_1 = 0x8b

    // ── record tags ───────────────────────────────────────────────────────────────────────────

    const val T_READING = "reading"
    const val T_SAMPLE = "sample"
    const val T_DOSE = "dose"
    const val T_MEAL = "meal"
    const val T_BASAL = "basal"
    const val T_FOOD = "food"
    const val T_SAVED_MEAL = "savedMeal"
    const val T_SAVED_ITEM = "savedItem"
    const val T_INSULIN = "insulinType"
    const val T_STROKE = "stroke"
    const val T_SOURCE = "source"
    const val T_PROFILE = "profile"
    const val T_CONFORMAL = "conformal"
    const val T_END = "end"

    /** Rows per statement on both paths. Large enough that the per-statement overhead disappears,
     *  small enough that a page of readings is a few hundred KB rather than a few tens of MB. */
    const val BATCH = 500

    internal val json = Json { ignoreUnknownKeys = true }

    // ── writing ───────────────────────────────────────────────────────────────────────────────

    /**
     * A field-at-a-time JSON object writer over a [Writer].
     *
     * It appends straight into the underlying (buffered) writer instead of rendering each record to
     * a `String` first: at 105 000 rows that intermediate would be 105 000 short-lived allocations
     * on the export path, which runs unattended and daily. Nothing here is reusable across records
     * by design — there is no state to reset and therefore no way to leak a field from one row to
     * the next.
     */
    class RecordWriter(private val out: Writer) {

        fun open(tag: String) {
            out.write("{\"t\":\"")
            out.write(tag)
            out.write("\"")
        }

        fun close() = out.write("}\n")

        private fun key(k: String) {
            out.write(",\"")
            out.write(k)
            out.write("\":")
        }

        fun put(k: String, v: Long) { key(k); out.write(v.toString()) }

        fun put(k: String, v: Int) { key(k); out.write(v.toString()) }

        fun put(k: String, v: Boolean) { key(k); out.write(if (v) "true" else "false") }

        fun put(k: String, v: Double) { key(k); writeDouble(v) }

        /** Written from the FLOAT, not widened to a double first: `4.2f.toDouble().toString()` is
         *  `4.199999809265137`, which round-trips to the same bits but bloats the file and reads as
         *  though precision were lost. `Float.toString` gives the shortest form that reloads exactly. */
        fun put(k: String, v: Float) {
            key(k)
            if (v.isFinite()) out.write(v.toString()) else writeString(v.toString())
        }

        fun put(k: String, v: String) { key(k); writeString(v) }

        fun putOrSkip(k: String, v: Long?) { if (v != null) put(k, v) }

        fun putOrSkip(k: String, v: Int?) { if (v != null) put(k, v) }

        fun putOrSkip(k: String, v: Double?) { if (v != null) put(k, v) }

        fun putOrSkip(k: String, v: String?) { if (v != null) put(k, v) }

        fun putBlobOrSkip(k: String, v: ByteArray?) {
            if (v != null) { key(k); writeString(B64_ENC.encodeToString(v)) }
        }

        /** Raw pre-rendered JSON (the settings document, which arrives already serialised). */
        fun putRaw(k: String, jsonText: String) { key(k); out.write(jsonText) }

        /**
         * JSON has no literal for NaN or either infinity, so a non-finite double is written as a
         * STRING and read back as one. Emitting the bare `NaN` token would produce a line no parser
         * accepts — the record would be dropped on restore — and coercing it to null or to zero
         * would silently invent a value the user never had. These should not occur in a physiologic
         * column, but "should not" is exactly the case a backup has to survive.
         */
        private fun writeDouble(v: Double) {
            if (v.isFinite()) out.write(v.toString()) else writeString(v.toString())
        }

        /**
         * Bulk-copies the runs that need no escaping and only breaks out per character for the ones
         * that do. Every string in this format — a source id, a UUID, a base64 blob, an enum name —
         * is clean in the overwhelming majority of cases, so the common path is a single
         * [Writer.write] of the whole run rather than one call per character.
         *
         * Escaping control characters is not cosmetic here: a raw newline inside a free-text note
         * would split one record across two lines and corrupt every record after it. That is the
         * one hazard a line-delimited format genuinely has, and this is where it is closed.
         */
        private fun writeString(s: String) {
            out.write("\"")
            val n = s.length
            var run = 0
            var i = 0
            while (i < n) {
                val c = s[i]
                val esc = when {
                    c == '"' -> "\\\""
                    c == '\\' -> "\\\\"
                    c == '\n' -> "\\n"
                    c == '\r' -> "\\r"
                    c == '\t' -> "\\t"
                    c < ' ' -> CTRL[c.code]
                    else -> null
                }
                if (esc != null) {
                    if (i > run) out.write(s, run, i - run)
                    out.write(esc)
                    run = i + 1
                }
                i++
            }
            if (n > run) out.write(s, run, n - run)
            out.write("\"")
        }
    }

    private val B64_ENC: Base64.Encoder = Base64.getEncoder()
    private val B64_DEC: Base64.Decoder = Base64.getDecoder()

    private val CTRL: Array<String> = Array(0x20) { "\\u%04x".format(it) }

    // ── per-entity codecs ─────────────────────────────────────────────────────────────────────
    //
    // Every decoder throws on a missing REQUIRED field. That is deliberate and is what makes the
    // reader's skip-and-count honest: a record that cannot yield a well-formed row is dropped by
    // itself rather than inserted half-built with defaults standing in for data that was never
    // there. Autogenerated ids are never carried — an archived row becomes a NEW row on the
    // restoring device, never a claim on an id that device may already have given to something else.

    fun write(w: RecordWriter, r: CgmReadingEntity) {
        w.open(T_READING)
        w.put("s", r.sourceId)
        w.put("ts", r.tsMs)
        w.putOrSkip("bg", r.bgMgdl)
        w.putOrSkip("tr", r.trendTenthsPerMin)
        w.putOrSkip("mfs", r.minFromStart)
        w.putOrSkip("q", r.quality)
        w.put("pv", r.provenance.name)
        w.put("fl", r.flag.name)
        w.put("tz", r.tzOffsetMin)
        w.put("rx", r.rxWallMs)
        w.putOrSkip("rs", r.rssi)
        w.close()
    }

    fun readReading(o: JsonObject) = CgmReadingEntity(
        sourceId = o.str("s") ?: err("reading", "s"),
        tsMs = o.long("ts") ?: err("reading", "ts"),
        bgMgdl = o.int("bg"),
        trendTenthsPerMin = o.int("tr"),
        minFromStart = o.int("mfs"),
        quality = o.int("q"),
        provenance = ReadingProvenance.valueOf(o.str("pv") ?: err("reading", "pv")),
        flag = ReadingFlag.valueOf(o.str("fl") ?: err("reading", "fl")),
        tzOffsetMin = o.int("tz") ?: err("reading", "tz"),
        rxWallMs = o.long("rx") ?: err("reading", "rx"),
        rssi = o.int("rs"),
    )

    fun write(w: RecordWriter, r: SampleEntity) {
        w.open(T_SAMPLE)
        w.put("ts", r.ts)
        w.put("tz", r.tzOffsetMin)
        w.putOrSkip("bg", r.bgMgdl)
        w.putOrSkip("pv", r.bgProvenance?.name)
        w.putOrSkip("fl", r.bgFlag?.name)
        w.putOrSkip("st", r.steps)
        w.putOrSkip("md", r.mood)
        w.putOrSkip("hr", r.hr)
        w.putOrSkip("sl", r.sleep)
        w.putOrSkip("ex", r.exercise)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readSample(o: JsonObject) = SampleEntity(
        ts = o.long("ts") ?: err("sample", "ts"),
        tzOffsetMin = o.int("tz") ?: err("sample", "tz"),
        bgMgdl = o.int("bg"),
        bgProvenance = o.str("pv")?.let(ReadingProvenance::valueOf),
        bgFlag = o.str("fl")?.let(ReadingFlag::valueOf),
        steps = o.int("st"),
        mood = o.int("md"),
        hr = o.int("hr"),
        sleep = o.int("sl"),
        exercise = o.int("ex"),
        updatedAt = o.long("ua") ?: err("sample", "ua"),
    )

    fun write(w: RecordWriter, r: LoggedDoseEntity) {
        w.open(T_DOSE)
        w.put("cid", r.clientId)
        w.put("ts", r.tsMs)
        w.put("kd", r.kind.name)
        w.put("u", r.units)
        w.put("dm", r.durationMin)
        w.putOrSkip("k", r.k)
        w.putOrSkip("th", r.theta)
        w.putOrSkip("ka", r.kaPerHour)
        w.putOrSkip("ke", r.kePerHour)
        w.putBlobOrSkip("cc", r.customCurve)
        w.put("tz", r.tzOffsetMin)
        w.putOrSkip("n", r.note)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readDose(o: JsonObject) = LoggedDoseEntity(
        clientId = o.str("cid") ?: err("dose", "cid"),
        tsMs = o.long("ts") ?: err("dose", "ts"),
        kind = DoseKind.valueOf(o.str("kd") ?: err("dose", "kd")),
        units = o.dbl("u") ?: err("dose", "u"),
        durationMin = o.dbl("dm") ?: err("dose", "dm"),
        k = o.dbl("k"),
        theta = o.dbl("th"),
        kaPerHour = o.dbl("ka"),
        kePerHour = o.dbl("ke"),
        customCurve = o.blob("cc"),
        tzOffsetMin = o.int("tz") ?: err("dose", "tz"),
        note = o.str("n"),
        updatedAt = o.long("ua") ?: err("dose", "ua"),
    )

    fun write(w: RecordWriter, r: LoggedMealEntity) {
        w.open(T_MEAL)
        w.put("cid", r.clientId)
        w.put("ts", r.tsMs)
        w.put("g", r.grams)
        w.putOrSkip("gi", r.gi)
        w.putOrSkip("k", r.k)
        w.putOrSkip("th", r.theta)
        w.put("dm", r.durationMin)
        w.putBlobOrSkip("cc", r.customCurve)
        w.put("tz", r.tzOffsetMin)
        w.putOrSkip("n", r.note)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readMeal(o: JsonObject) = LoggedMealEntity(
        clientId = o.str("cid") ?: err("meal", "cid"),
        tsMs = o.long("ts") ?: err("meal", "ts"),
        grams = o.dbl("g") ?: err("meal", "g"),
        gi = o.dbl("gi"),
        k = o.dbl("k"),
        theta = o.dbl("th"),
        durationMin = o.dbl("dm") ?: err("meal", "dm"),
        customCurve = o.blob("cc"),
        tzOffsetMin = o.int("tz") ?: err("meal", "tz"),
        note = o.str("n"),
        updatedAt = o.long("ua") ?: err("meal", "ua"),
    )

    fun write(w: RecordWriter, r: BasalScheduleEntity) {
        w.open(T_BASAL)
        w.put("sid", r.scheduleId)
        w.put("lb", r.label)
        w.put("tod", r.timeOfDayMin)
        w.put("d", r.doseU)
        w.put("dm", r.durationMin)
        w.put("ka", r.kaPerHour)
        w.put("ke", r.kePerHour)
        w.put("tz", r.tzOffsetMin)
        w.put("ac", r.active)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readBasal(o: JsonObject) = BasalScheduleEntity(
        scheduleId = o.str("sid") ?: err("basal", "sid"),
        label = o.str("lb") ?: err("basal", "lb"),
        timeOfDayMin = o.int("tod") ?: err("basal", "tod"),
        doseU = o.dbl("d") ?: err("basal", "d"),
        durationMin = o.dbl("dm") ?: err("basal", "dm"),
        kaPerHour = o.dbl("ka") ?: err("basal", "ka"),
        kePerHour = o.dbl("ke") ?: err("basal", "ke"),
        tzOffsetMin = o.int("tz") ?: err("basal", "tz"),
        active = o.bool("ac") ?: false,
        updatedAt = o.long("ua") ?: err("basal", "ua"),
    )

    fun write(w: RecordWriter, r: FoodEntity) {
        w.open(T_FOOD)
        w.put("nm", r.name)
        w.putOrSkip("br", r.brand)
        w.put("c100", r.carbsPer100g)
        w.putOrSkip("gi", r.gi)
        w.put("cat", r.category)
        w.put("src", r.source)
        w.putBlobOrSkip("cc", r.customCurve)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readFood(o: JsonObject) = FoodEntity(
        name = o.str("nm") ?: err("food", "nm"),
        brand = o.str("br"),
        carbsPer100g = o.dbl("c100") ?: err("food", "c100"),
        gi = o.dbl("gi"),
        category = o.str("cat") ?: err("food", "cat"),
        source = o.str("src") ?: err("food", "src"),
        // Always true on restore: only user-added foods are archived, and a restored row is a
        // user-added row on the new device too. Trusting an archived flag here would let a
        // hand-edited file smuggle a row past `deleteAllCustom`, which the reset relies on.
        custom = true,
        customCurve = o.blob("cc"),
        updatedAt = o.long("ua") ?: err("food", "ua"),
    )

    fun write(w: RecordWriter, r: SavedMealEntity, index: Int) {
        w.open(T_SAVED_MEAL)
        w.put("ix", index)
        w.put("nm", r.name)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun write(w: RecordWriter, r: SavedMealItemEntity, mealIndex: Int) {
        w.open(T_SAVED_ITEM)
        w.put("mix", mealIndex)
        w.putOrSkip("fid", r.foodId)
        w.put("nm", r.name)
        w.put("g", r.grams)
        w.put("c100", r.carbsPer100g)
        w.putOrSkip("gi", r.gi)
        w.putBlobOrSkip("cc", r.customCurve)
        w.close()
    }

    fun write(w: RecordWriter, r: InsulinTypeEntity) {
        w.open(T_INSULIN)
        w.put("nm", r.name)
        w.put("kd", r.kind.name)
        w.put("dm", r.durationMin)
        w.putOrSkip("k", r.k)
        w.putOrSkip("th", r.theta)
        w.putOrSkip("ka", r.kaPerHour)
        w.putOrSkip("ke", r.kePerHour)
        w.putBlobOrSkip("cc", r.customCurve)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readInsulinType(o: JsonObject) = InsulinTypeEntity(
        name = o.str("nm") ?: err("insulinType", "nm"),
        kind = DoseKind.valueOf(o.str("kd") ?: err("insulinType", "kd")),
        durationMin = o.dbl("dm") ?: err("insulinType", "dm"),
        k = o.dbl("k"),
        theta = o.dbl("th"),
        kaPerHour = o.dbl("ka"),
        kePerHour = o.dbl("ke"),
        customCurve = o.blob("cc"),
        // As with [readFood]: archived types are user-defined by construction, and an archived
        // `builtin = 1` would survive the reset that is supposed to clear it.
        builtin = false,
        updatedAt = o.long("ua") ?: err("insulinType", "ua"),
    )

    /** `minTsMs`/`maxTsMs` are deliberately NOT written: they are derived from the polyline, and
     *  [readStroke] recomputes them. Archiving a derived column only creates a way for the file to
     *  disagree with itself. */
    fun write(w: RecordWriter, r: PaintStrokeEntity) {
        w.open(T_STROKE)
        w.put("ca", r.createdAtMs)
        w.put("tl", r.tool)
        w.put("col", r.colorArgb)
        w.put("wd", r.widthDp)
        w.putBlobOrSkip("pts", r.points)
        w.close()
    }

    /**
     * Decoding the polyline is the validation: [PaintStrokeBlob.decode] refuses a wrong magic,
     * an unknown layout version and a truncated point array, so a corrupt blob is caught here and
     * costs one skipped drawing instead of an insert that fails mid-batch and takes the rest of the
     * page with it.
     *
     * The indexed time bounds are recomputed from the decoded points rather than read from the
     * file. They are the key the viewport query seeks on, so bounds that disagreed with the geometry
     * would make a drawing invisible at the one window it belongs to — and a hand-edited or
     * half-written file is exactly where that disagreement would come from. Scanning every point is
     * required regardless of cost: a stroke that doubles back in X has no sorted first-or-last to
     * shortcut to.
     */
    fun readStroke(o: JsonObject): PaintStrokeEntity {
        val blob = o.blob("pts") ?: err("stroke", "pts")
        val points = PaintStrokeBlob.decode(blob)
        if (points.tsMs.isEmpty()) throw IllegalArgumentException("stroke carries no points")
        var min = points.tsMs[0]
        var max = points.tsMs[0]
        for (t in points.tsMs) {
            if (t < min) min = t
            if (t > max) max = t
        }
        return PaintStrokeEntity(
            createdAtMs = o.long("ca") ?: err("stroke", "ca"),
            tool = o.str("tl") ?: err("stroke", "tl"),
            colorArgb = o.int("col") ?: err("stroke", "col"),
            widthDp = (o.dbl("wd") ?: err("stroke", "wd")).toFloat(),
            minTsMs = min,
            maxTsMs = max,
            points = blob,
        )
    }

    /**
     * `ac` records WHICH source was live, and is read back only as a preference — never applied
     * directly. Omitting it entirely (as this first did) left the restore with no way to tell the
     * worn sensor from a retired one, so it activated whichever row happened to come first, which is
     * the OLDEST the phone ever saw: the export walks `cgm_source` by `addedAtMs` ascending. On the
     * fresh install this whole feature exists for, that pointed the reading path at a sensor that
     * will never advertise again.
     */
    fun write(w: RecordWriter, r: CgmSourceEntity) {
        w.open(T_SOURCE)
        w.put("sid", r.sourceId)
        w.put("vid", r.vendorId)
        w.put("dn", r.displayName)
        w.putOrSkip("ss", r.serialSuffix)
        w.put("wm", r.warmupWindowMin)
        w.put("aa", r.addedAtMs)
        w.putOrSkip("ls", r.lastSeenMs)
        w.put("ac", r.active)
        w.close()
    }

    /**
     * [active] is decided by the CALLER, never by the file: `cgm_source` carries an
     * exactly-one-active invariant (§3.1) and a restore onto a phone that already has a live sensor
     * must not land a second claimant on it.
     */
    fun readSource(o: JsonObject, active: Boolean) = CgmSourceEntity(
        sourceId = o.str("sid") ?: err("source", "sid"),
        vendorId = o.str("vid") ?: err("source", "vid"),
        displayName = o.str("dn") ?: err("source", "dn"),
        serialSuffix = o.str("ss"),
        active = active,
        warmupWindowMin = o.int("wm") ?: err("source", "wm"),
        addedAtMs = o.long("aa") ?: err("source", "aa"),
        lastSeenMs = o.long("ls"),
    )

    /** `ac` is a preference, exactly as on [write] for a source. */
    fun write(w: RecordWriter, r: ServerProfileEntity) {
        w.open(T_PROFILE)
        w.put("id", r.id)
        w.put("lb", r.label)
        w.put("url", r.baseUrl)
        w.put("ca", r.createdAtMs)
        w.put("ua", r.updatedAtMs)
        w.put("ac", r.active)
        w.close()
    }

    /** [active] is the caller's, for the reason given on [readSource]. */
    fun readProfile(o: JsonObject, active: Boolean) = ServerProfileEntity(
        id = o.str("id") ?: err("profile", "id"),
        label = o.str("lb") ?: err("profile", "lb"),
        baseUrl = o.str("url") ?: err("profile", "url"),
        active = active,
        createdAtMs = o.long("ca") ?: err("profile", "ca"),
        updatedAtMs = o.long("ua") ?: err("profile", "ua"),
    )

    fun write(w: RecordWriter, r: ConformalDeltaEntity) {
        w.open(T_CONFORMAL)
        w.put("mid", r.modelId)
        w.put("st", r.steps)
        w.put("nq", r.nQuantiles)
        w.putBlobOrSkip("d", r.deltaBlob)
        w.put("ncal", r.nCal)
        w.put("nev", r.nEval)
        w.put("mx", r.maxAbsDeltaMgdl)
        w.putOrSkip("c9r", r.cov90Raw)
        w.putOrSkip("c9c", r.cov90Cal)
        w.putOrSkip("w9r", r.meanWidth90Raw)
        w.putOrSkip("w9c", r.meanWidth90Cal)
        w.put("wd", r.windowDays)
        w.put("fa", r.fittedAtMs)
        w.close()
    }

    fun readConformal(o: JsonObject): ConformalDeltaEntity {
        val steps = o.int("st") ?: err("conformal", "st")
        val nq = o.int("nq") ?: err("conformal", "nq")
        val blob = o.blob("d") ?: err("conformal", "d")
        // The same self-consistency the observer enforces when reading these rows back: a delta whose
        // length disagrees with its own declared shape cannot be applied to a fan, so it is refused
        // at the door rather than stored to be silently dropped on every later read.
        if (steps <= 0 || nq <= 0 || blob.size != steps * nq * Double.SIZE_BYTES) {
            throw IllegalArgumentException("conformal delta shape disagrees with its blob")
        }
        return ConformalDeltaEntity(
            modelId = o.str("mid") ?: err("conformal", "mid"),
            steps = steps,
            nQuantiles = nq,
            deltaBlob = blob,
            nCal = o.int("ncal") ?: err("conformal", "ncal"),
            nEval = o.int("nev") ?: err("conformal", "nev"),
            maxAbsDeltaMgdl = o.dbl("mx") ?: err("conformal", "mx"),
            cov90Raw = o.dbl("c9r"),
            cov90Cal = o.dbl("c9c"),
            meanWidth90Raw = o.dbl("w9r"),
            meanWidth90Cal = o.dbl("w9c"),
            windowDays = o.int("wd") ?: err("conformal", "wd"),
            fittedAtMs = o.long("fa") ?: err("conformal", "fa"),
        )
    }

    private fun err(record: String, field: String): Nothing =
        throw IllegalArgumentException("$record record has no $field")

    internal fun decodeB64(s: String): ByteArray = B64_DEC.decode(s)
}

// ── field accessors over one parsed record ────────────────────────────────────────────────────
//
// File-level rather than members of [Archive] so a reader can call `o.str("nm")` directly instead
// of through a scoping block. An absent key decodes as null, which is what makes the writer's
// omit-nulls policy lossless.

private fun JsonObject.prim(k: String): JsonPrimitive? = this[k] as? JsonPrimitive

internal fun JsonObject.long(k: String): Long? = prim(k)?.longOrNull

internal fun JsonObject.int(k: String): Int? = prim(k)?.intOrNull

internal fun JsonObject.bool(k: String): Boolean? = prim(k)?.booleanOrNull

internal fun JsonObject.str(k: String): String? = prim(k)?.contentOrNull

/** Mirrors the writer's double handling: a finite double arrives as a number, a non-finite one as
 *  the string `NaN` / `Infinity` / `-Infinity`, and both decode back to the value written. */
internal fun JsonObject.dbl(k: String): Double? {
    val p = prim(k) ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}

internal fun JsonObject.blob(k: String): ByteArray? = str(k)?.let(Archive::decodeB64)

internal fun JsonObject.tag(): String? = str("t")
