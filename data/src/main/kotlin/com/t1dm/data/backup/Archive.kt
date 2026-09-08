package com.t1dm.data.backup

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.BgInfillEntity
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.EventTombstoneEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.LoraEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
import com.t1dm.data.db.LoggedExerciseEntity
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
import com.t1dm.data.legacySensorModelIdFor
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

/** t1dm.archive: gzipped JSON Lines, terminated by end; bad lines skipped/counted, not fatal. */
object Archive {

    const val FORMAT = "t1dm.archive"
    const val VERSION = 1

    /** The bytes are gzip, so not `.json`. */
    const val EXTENSION = "t1dmbak"

    /** As InputStream.read() (0-255); reader sniffs bytes not extension, so renamed restore. */
    const val GZIP_MAGIC_0 = 0x1f
    const val GZIP_MAGIC_1 = 0x8b


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
    const val T_LORA = "lora"
    const val T_EXERCISE = "exercise"

    /** The replay rows. Their grams ride in the samples, so a restore must NOT re-lay the curve. */
    const val T_LOGGED_EXERCISE = "loggedExercise"
    const val T_EXERCISE_FIX = "exerciseFix"

    /** Without it a restore resurrects everything the patient deleted. */
    const val T_TOMBSTONE = "tombstone"

    /** Only copy of a promoted reconstruction's 90% band; wire carries a boolean, no fan. */
    const val T_INFILL = "infill"
    const val T_END = "end"

    /** Rows per statement: a page of readings is a few hundred KB rather than a few tens of MB. */
    const val BATCH = 500

    internal val json = Json { ignoreUnknownKeys = true }


    /** Appends into buffered [out], no per-record String; holds no state across records. */
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

        /** From FLOAT not double (widening yields 4.199999809265137); toString reloads exactly. */
        fun put(k: String, v: Float) {
            key(k)
            if (v.isFinite()) out.write(v.toString()) else writeString(v.toString())
        }

        fun put(k: String, v: String) { key(k); writeString(v) }

        fun putOrSkip(k: String, v: Long?) { if (v != null) put(k, v) }

        fun putOrSkip(k: String, v: Int?) { if (v != null) put(k, v) }

        fun putOrSkip(k: String, v: Double?) { if (v != null) put(k, v) }

        fun putOrSkip(k: String, v: Float?) { if (v != null) put(k, v) }

        fun putOrSkip(k: String, v: String?) { if (v != null) put(k, v) }

        fun putBlobOrSkip(k: String, v: ByteArray?) {
            if (v != null) { key(k); writeString(B64_ENC.encodeToString(v)) }
        }

        /** Written verbatim; the caller has already serialised it. */
        fun putRaw(k: String, jsonText: String) { key(k); out.write(jsonText) }

        /** No JSON NaN/Infinity; non-finite doubles ride as STRING, else no parser accepts line. */
        private fun writeDouble(v: Double) {
            if (v.isFinite()) out.write(v.toString()) else writeString(v.toString())
        }

        /** Bulk-copies unescaped runs; a raw newline unescaped would corrupt every record after. */
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

    // Decoders throw on missing REQUIRED field (record dropped whole); ids never ride, rows NEW.

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
        // exg not ex (pre-schema-17); ex held whole SECONDS/bucket, reading as grams is 100x off.
        w.putOrSkip("exg", r.exercise)
        w.putOrSkip("bs", r.bgSource)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readSample(o: JsonObject) = SampleEntity(
        ts = o.long("ts") ?: err("sample", "ts"),
        tzOffsetMin = o.int("tz") ?: err("sample", "tz"),
        bgMgdl = o.int("bg"),
        // Absent pre-column is the honest answer: that archive has no record of the sensor.
        bgSource = o.str("bs"),
        bgProvenance = o.str("pv")?.let(ReadingProvenance::valueOf),
        bgFlag = o.str("fl")?.let(ReadingFlag::valueOf),
        steps = o.int("st"),
        mood = o.int("md"),
        hr = o.int("hr"),
        sleep = o.int("sl"),
        exercise = o.dbl("exg"),
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
        w.put("lat", r.loggedAtMs)
        w.putOrSkip("mut", r.mutatedAtMs)
        w.putOrSkip("mau", r.mutatedActingUntilMs)
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
        // Pre-column files have no lat; ua is the migration's own backfill rule.
        loggedAtMs = o.long("lat") ?: o.long("ua") ?: 0L,
        mutatedAtMs = o.long("mut"),
        mutatedActingUntilMs = o.long("mau"),
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
        w.put("lat", r.loggedAtMs)
        w.putOrSkip("mut", r.mutatedAtMs)
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
        /** See [readDose]. */
        loggedAtMs = o.long("lat") ?: o.long("ua") ?: 0L,
        mutatedAtMs = o.long("mut"),
    )

    fun write(w: RecordWriter, r: BgInfillEntity) {
        w.open(T_INFILL)
        w.put("ts", r.ts)
        w.put("mid", r.mgdl)
        w.put("lo", r.lo90)
        w.put("hi", r.hi90)
        w.put("m", r.modelId)
        w.put("ca", r.createdAtMs)
        w.put("sp", r.spanStartMs)
        w.putOrSkip("pa", r.promotedAtMs)
        w.close()
    }

    fun readInfill(o: JsonObject) = BgInfillEntity(
        ts = o.long("ts") ?: err("infill", "ts"),
        mgdl = o.dbl("mid") ?: err("infill", "mid"),
        lo90 = o.dbl("lo") ?: err("infill", "lo"),
        hi90 = o.dbl("hi") ?: err("infill", "hi"),
        modelId = o.str("m") ?: err("infill", "m"),
        createdAtMs = o.long("ca") ?: err("infill", "ca"),
        spanStartMs = o.long("sp") ?: o.long("ts") ?: 0L,
        // Restored PROMOTED as written; sample rows carry bgProvenance=RECONSTRUCTED, must agree.
        promotedAtMs = o.long("pa") ?: o.long("ca"),
    )

    fun write(w: RecordWriter, r: EventTombstoneEntity) {
        w.open(T_TOMBSTONE)
        w.put("cid", r.clientId)
        w.put("kd", r.kind)
        w.put("ts", r.tsMs)
        w.put("tz", r.tzOffsetMin)
        w.put("ua", r.updatedAt)
        w.put("ca", r.createdAtMs)
        w.putOrSkip("pe", r.pushEnqueuedAtMs)
        w.putOrSkip("au", r.actingUntilMs)
        w.close()
    }

    fun readTombstone(o: JsonObject) = EventTombstoneEntity(
        clientId = o.str("cid") ?: err("tombstone", "cid"),
        kind = o.str("kd") ?: err("tombstone", "kd"),
        tsMs = o.long("ts") ?: err("tombstone", "ts"),
        tzOffsetMin = o.int("tz") ?: err("tombstone", "tz"),
        updatedAt = o.long("ua") ?: err("tombstone", "ua"),
        createdAtMs = o.long("ca") ?: err("tombstone", "ca"),
        // Restored deletion already accounted for server-side; null would re-file every deletion.
        pushEnqueuedAtMs = o.long("pe") ?: o.long("ca"),
        actingUntilMs = o.long("au"),
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
        // Only user foods archived; trusting flag lets a hand-edited file bypass deleteAllCustom.
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
        // As with [readFood]: an archived `builtin = 1` would survive the reset meant to clear it.
        builtin = false,
        updatedAt = o.long("ua") ?: err("insulinType", "ua"),
    )

    /** minTsMs/maxTsMs derived from polyline, deliberately NOT written; readStroke recomputes. */
    fun write(w: RecordWriter, r: PaintStrokeEntity) {
        w.open(T_STROKE)
        w.put("ca", r.createdAtMs)
        w.put("tl", r.tool)
        w.put("col", r.colorArgb)
        w.put("wd", r.widthDp)
        w.putBlobOrSkip("pts", r.points)
        w.close()
    }

    /** Decode IS validation (corrupt blob skips draw); bounds recomputed not read, key viewport. */
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

    /** ac records WHICH source was live, read back as preference only; else restore picks oldest */
    fun write(w: RecordWriter, r: CgmSourceEntity) {
        w.open(T_SOURCE)
        w.put("sid", r.sourceId)
        w.put("vid", r.vendorId)
        w.put("mid", r.sensorModelId)
        w.putOrSkip("an", r.advertName)
        w.put("dn", r.displayName)
        w.putOrSkip("ss", r.serialSuffix)
        w.put("wm", r.warmupWindowMin)
        w.put("aa", r.addedAtMs)
        w.putOrSkip("ls", r.lastSeenMs)
        // ac keeps its original meaning (the believed sensor); av is weaker: app was reading it.
        w.put("ac", r.authoritative)
        w.put("av", r.active)
        w.put("hd", r.hidden)
        // Stable per-sensor number; pre-column files have none, restoring phone assigns one.
        w.put("or", r.ordinal)
        w.close()
    }

    /** [authoritative] decided by CALLER, never file (§3.1 exactly-one); no second claimant. */
    fun readSource(o: JsonObject, authoritative: Boolean): CgmSourceEntity {
        // Bound first: the fallback below needs it; a named arg isn't in scope for args after it.
        val sourceId = o.str("sid") ?: err("source", "sid")
        return CgmSourceEntity(
            sourceId = sourceId,
            vendorId = o.str("vid") ?: err("source", "vid"),
        // Pre-column files have no mid; fallback must match MIGRATION_10_11, else history splits.
            sensorModelId = o.str("mid") ?: legacySensorModelIdFor(sourceId),
        // Null, not invented: pre-column sources genuinely have no record of what they advertised.
            advertName = o.str("an"),
            displayName = o.str("dn") ?: err("source", "dn"),
            serialSuffix = o.str("ss"),
            authoritative = authoritative,
        // Read from file, unlike authoritative (user's own call); pre-v14 falls back to ac.
            active = o.bool("av") ?: o.bool("ac") ?: false,
            warmupWindowMin = o.int("wm") ?: err("source", "wm"),
            addedAtMs = o.long("aa") ?: err("source", "aa"),
            lastSeenMs = o.long("ls"),
        // Absent pre-column = false (accurate); read from file, else re-lists removed sensors.
            hidden = o.bool("hd") ?: false,
        // Read but NOT trusted (may collide); ArchiveReader.renumbered resolves it. -1=pre-column.
            ordinal = o.int("or") ?: -1,
        )
    }

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
        // Scoped sensor rides with fit; dropped ⇒ restores UNKNOWN, apply refuses (raw fallback).
        w.putOrSkip("src", r.sourceId)
        w.close()
    }

    fun readConformal(o: JsonObject): ConformalDeltaEntity {
        val steps = o.int("st") ?: err("conformal", "st")
        val nq = o.int("nq") ?: err("conformal", "nq")
        val blob = o.blob("d") ?: err("conformal", "d")
        // Shape-length mismatch can't apply; refused at door, not stored to fail on later reads.
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
            // Absent means UNKNOWN: the correction restores, and the apply refuses to draw it.
            sourceId = o.str("src"),
        )
    }

    /** Weight blob rides verbatim; attached is phone-local, not archived; guard verdict rides. */
    fun write(w: RecordWriter, r: LoraEntity) {
        w.open(T_LORA)
        w.put("mid", r.modelId)
        w.put("nm", r.name)
        w.putBlobOrSkip("b", r.blob)
        w.put("rk", r.rank)
        w.put("al", r.alpha)
        w.put("tg", r.targets)
        w.put("np", r.nParams)
        w.put("ntr", r.nTrain)
        w.put("nho", r.nHoldout)
        w.put("ep", r.epochs)
        w.put("hb", r.holdoutBefore)
        w.put("ha", r.holdoutAfter)
        w.put("imp", r.improved)
        w.put("ca", r.createdAtMs)
        w.put("ua", r.updatedAtMs)
        w.put("gv", r.guardVerdict)
        w.put("gw", r.guardWindows)
        w.put("gf", r.guardFrozenMgdl)
        w.put("gaj", r.guardAdaptedMgdl)
        w.put("gr", r.guardRetention)
        w.put("gs", r.guardSignAgreement)
        w.put("gy", r.guardWhy)
        w.put("npr", r.nPaired)
        w.put("ds", r.distillScale)
        w.put("fa", r.fittedAtMs)
        w.close()
    }

    fun readLora(o: JsonObject): LoraEntity {
        val blob = o.blob("b") ?: err("lora", "b")
        val nParams = o.int("np") ?: err("lora", "np")
        // A blob too short to hold its own header and digest can never load.
        if (nParams <= 0 || blob.size < 32) {
            throw IllegalArgumentException("adapter blob cannot hold $nParams parameters")
        }
        return LoraEntity(
            modelId = o.str("mid") ?: err("lora", "mid"),
            name = o.str("nm") ?: err("lora", "nm"),
            blob = blob,
            rank = o.int("rk") ?: err("lora", "rk"),
            alpha = o.dbl("al") ?: err("lora", "al"),
            targets = o.int("tg") ?: err("lora", "tg"),
            nParams = nParams,
            nTrain = o.int("ntr") ?: err("lora", "ntr"),
            nHoldout = o.int("nho") ?: err("lora", "nho"),
            epochs = o.int("ep") ?: err("lora", "ep"),
            holdoutBefore = o.dbl("hb") ?: err("lora", "hb"),
            holdoutAfter = o.dbl("ha") ?: err("lora", "ha"),
            improved = o.bool("imp") ?: false,
            attached = false,
            createdAtMs = o.long("ca") ?: err("lora", "ca"),
            updatedAtMs = o.long("ua") ?: err("lora", "ua"),
            // Absent pre-guard reads ABSENT — honest for unmeasured, and refuses attach.
            guardVerdict = o.str("gv") ?: "ABSENT",
            guardWindows = o.int("gw") ?: 0,
            guardFrozenMgdl = o.dbl("gf") ?: 0.0,
            guardAdaptedMgdl = o.dbl("gaj") ?: 0.0,
            guardRetention = o.dbl("gr") ?: 0.0,
            guardSignAgreement = o.dbl("gs") ?: 0.0,
            guardWhy = o.str("gy").orEmpty(),
            nPaired = o.int("npr") ?: 0,
            distillScale = o.dbl("ds") ?: 0.0,
            // Zero = "not fitted on THIS phone", not an instant every edit would invalidate.
            fittedAtMs = o.long("fa") ?: 0L,
        )
    }

    /** kd rides as raw stored TEXT, not via ExerciseKind; unconverted so newer builds stay ok. */
    fun write(w: RecordWriter, r: ExerciseSessionEntity) {
        w.open(T_EXERCISE)
        w.put("cid", r.clientId)
        w.put("st", r.startMs)
        w.putOrSkip("en", r.endMs)
        w.put("tz", r.tzOffsetMin)
        w.put("kd", r.kind)
        w.put("as", r.activeSec)
        w.putOrSkip("dm", r.distanceM)
        w.putOrSkip("kc", r.kcal)
        w.put("it", r.interrupted)
        w.putOrSkip("n", r.note)
        w.put("ua", r.updatedAt)
        w.close()
    }

    fun readExercise(o: JsonObject) = ExerciseSessionEntity(
        clientId = o.str("cid") ?: err("exercise", "cid"),
        startMs = o.long("st") ?: err("exercise", "st"),
        // Absent = bout was open when archived, restores open; inventing an end claims false stop.
        endMs = o.long("en"),
        tzOffsetMin = o.int("tz") ?: err("exercise", "tz"),
        kind = o.str("kd") ?: err("exercise", "kd"),
        activeSec = o.int("as") ?: err("exercise", "as"),
        distanceM = o.dbl("dm"),
        kcal = o.int("kc"),
        interrupted = o.bool("it") ?: false,
        note = o.str("n"),
        updatedAt = o.long("ua") ?: err("exercise", "ua"),
    )

    /** sr dropped: sourceSessionId is a per-device rowid; provenance only, nothing joins on it. */
    fun write(w: RecordWriter, r: LoggedExerciseEntity) {
        w.open(T_LOGGED_EXERCISE)
        w.put("cid", r.clientId)
        w.put("ts", r.tsMs)
        w.put("tz", r.tzOffsetMin)
        w.put("kd", r.kind)
        w.put("dm", r.durationMin)
        w.put("g", r.grams)
        w.put("k", r.k)
        w.put("th", r.theta)
        w.put("cd", r.curveDurationMin)
        w.put("ua", r.updatedAt)
        w.put("la", r.loggedAtMs)
        w.putOrSkip("ma", r.mutatedAtMs)
        w.close()
    }

    fun readLoggedExercise(o: JsonObject) = LoggedExerciseEntity(
        clientId = o.str("cid") ?: err("loggedExercise", "cid"),
        tsMs = o.long("ts") ?: err("loggedExercise", "ts"),
        tzOffsetMin = o.int("tz") ?: err("loggedExercise", "tz"),
        kind = o.str("kd") ?: err("loggedExercise", "kd"),
        durationMin = o.dbl("dm") ?: err("loggedExercise", "dm"),
        // The curve's own parameters, so an unwind after a restore removes exactly what was laid.
        grams = o.dbl("g") ?: err("loggedExercise", "g"),
        k = o.dbl("k") ?: err("loggedExercise", "k"),
        theta = o.dbl("th") ?: err("loggedExercise", "th"),
        curveDurationMin = o.dbl("cd") ?: err("loggedExercise", "cd"),
        sourceSessionId = null,
        updatedAt = o.long("ua") ?: err("loggedExercise", "ua"),
        loggedAtMs = o.long("la") ?: 0L,
        mutatedAtMs = o.long("ma"),
    )

    /** Fix carries bout's clientId, not stored sessionId (rowid is per-device, names differ). */
    fun write(w: RecordWriter, r: ExerciseFixEntity, sessionClientId: String) {
        w.open(T_EXERCISE_FIX)
        w.put("cid", sessionClientId)
        w.put("ts", r.tsMs)
        w.put("la", r.lat)
        w.put("lo", r.lon)
        w.put("acc", r.accuracyM)
        w.putOrSkip("sp", r.speedMps)
        w.close()
    }

    /** sessionId is a placeholder; reader resolves it once it knows the local bout it became. */
    fun readExerciseFix(o: JsonObject): Pair<String, ExerciseFixEntity> {
        val cid = o.str("cid") ?: err("exerciseFix", "cid")
        return cid to ExerciseFixEntity(
            sessionId = 0L,
            tsMs = o.long("ts") ?: err("exerciseFix", "ts"),
            lat = o.dbl("la") ?: err("exerciseFix", "la"),
            lon = o.dbl("lo") ?: err("exerciseFix", "lo"),
            accuracyM = (o.dbl("acc") ?: err("exerciseFix", "acc")).toFloat(),
            speedMps = o.dbl("sp")?.toFloat(),
        )
    }

    private fun err(record: String, field: String): Nothing =
        throw IllegalArgumentException("$record record has no $field")

    internal fun decodeB64(s: String): ByteArray = B64_DEC.decode(s)
}

// File-level, not in [Archive], so callers write o.str("nm") directly; absent key decodes null.

private fun JsonObject.prim(k: String): JsonPrimitive? = this[k] as? JsonPrimitive

internal fun JsonObject.long(k: String): Long? = prim(k)?.longOrNull

internal fun JsonObject.int(k: String): Int? = prim(k)?.intOrNull

internal fun JsonObject.bool(k: String): Boolean? = prim(k)?.booleanOrNull

internal fun JsonObject.str(k: String): String? = prim(k)?.contentOrNull

/** Finite double arrives as number, non-finite as string NaN/Infinity/-Infinity; both decode. */
internal fun JsonObject.dbl(k: String): Double? {
    val p = prim(k) ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}

internal fun JsonObject.blob(k: String): ByteArray? = str(k)?.let(Archive::decodeB64)

internal fun JsonObject.tag(): String? = str("t")
