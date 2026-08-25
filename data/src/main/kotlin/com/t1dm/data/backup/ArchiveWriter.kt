package com.t1dm.data.backup

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.t1dm.data.db.AppDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.GZIPOutputStream

data class ArchiveCounts(
    val readings: Int = 0,
    val samples: Int = 0,
    val doses: Int = 0,
    val meals: Int = 0,
    val basal: Int = 0,
    val foods: Int = 0,
    val savedMeals: Int = 0,
    val savedItems: Int = 0,
    val insulinTypes: Int = 0,
    val strokes: Int = 0,
    val sources: Int = 0,
    val profiles: Int = 0,
    val conformal: Int = 0,
    val loras: Int = 0,
    val exerciseSessions: Int = 0,
    val exerciseFixes: Int = 0,
    val tombstones: Int = 0,
    val infills: Int = 0,
) {
    val total: Int
        get() = readings + samples + doses + meals + basal + foods + savedMeals +
            savedItems + insulinTypes + strokes + sources + profiles + conformal +
            loras + exerciseSessions + exerciseFixes + tombstones + infills
}

/**
 * Excludes the outbox, raw adverts, `prediction`, `hw_telemetry`, legacy `dose_event`, and
 * `cgm_sample_raw` — that last is retention-bounded, and a merging restore would re-add rows the
 * phone has already dropped. UNPROMOTED `bg_infill` is out; PROMOTED is in, as the only place a
 * promoted sample's band exists. Carries `exercise_fix`, so the file holds the user's GPS tracks:
 * never relax it into an automatic upload.
 */
class ArchiveWriter(private val db: AppDatabase) {

    /**
     * [out] is NOT closed here — the caller owns it; the gzip trailer is written, so the document
     * is complete on return. One deferred read transaction, so the archive is a consistent
     * snapshot; under WAL that defers checkpointing but does not block writers.
     */
    suspend fun write(
        out: OutputStream,
        configJson: String?,
        appVersion: String,
        nowMs: Long,
    ): ArchiveCounts {
        val gz = GZIPOutputStream(BufferedOutputStream(out, BUF), BUF)
        val writer = BufferedWriter(OutputStreamWriter(gz, Charsets.UTF_8), BUF)
        val counts = db.useReaderConnection { transactor ->
            transactor.deferredTransaction {
                writeHeader(writer, configJson, appVersion, nowMs)
                val c = writeBody(writer)
                writeEnd(writer, c)
                c
            }
        }
        writer.flush()
        gz.finish()
        gz.flush()
        return counts
    }

    private fun writeHeader(w: Writer, configJson: String?, appVersion: String, nowMs: Long) {
        val rw = Archive.RecordWriter(w)
        w.write("{\"format\":\"")
        w.write(Archive.FORMAT)
        w.write("\",\"version\":")
        w.write(Archive.VERSION.toString())
        rw.put("createdAtMs", nowMs)
        rw.put("schema", AppDatabase.SCHEMA_VERSION)
        rw.put("app", appVersion)
        // Re-encoded compact: a pretty-printed config puts newlines inside the header record.
        if (configJson != null) {
            val compact = runCatching {
                Archive.json.encodeToString(
                    JsonObject.serializer(),
                    Archive.json.parseToJsonElement(configJson).jsonObject,
                )
            }.getOrNull()
            // Dropped rather than embedded: an unparseable header would cost the user every row.
            if (compact != null) rw.putRaw("config", compact)
        }
        w.write("}\n")
    }

    private suspend fun writeBody(w: Writer): ArchiveCounts {
        val rw = Archive.RecordWriter(w)
        var counts = ArchiveCounts()

        var readings = 0
        for (sourceId in db.cgmReadingDao().sourceIds()) {
            var cursor = Long.MIN_VALUE
            while (true) {
                val page = db.cgmReadingDao().pageFrom(sourceId, cursor, Archive.BATCH)
                if (page.isEmpty()) break
                for (r in page) Archive.write(rw, r)
                readings += page.size
                cursor = page.last().tsMs
                if (page.size < Archive.BATCH) break
            }
        }
        counts = counts.copy(readings = readings)

        var samples = 0
        var sampleCursor = Long.MIN_VALUE
        while (true) {
            val page = db.sampleDao().page(sampleCursor, Archive.BATCH)
            if (page.isEmpty()) break
            for (r in page) Archive.write(rw, r)
            samples += page.size
            sampleCursor = page.last().ts
            if (page.size < Archive.BATCH) break
        }
        counts = counts.copy(samples = samples)

        var doses = 0
        var doseTs = Long.MIN_VALUE
        var doseId = Long.MIN_VALUE
        while (true) {
            val page = db.loggedDoseDao().pageFrom(doseTs, doseId, Archive.BATCH)
            if (page.isEmpty()) break
            for (r in page) Archive.write(rw, r)
            doses += page.size
            doseTs = page.last().tsMs
            doseId = page.last().id
            if (page.size < Archive.BATCH) break
        }
        counts = counts.copy(doses = doses)

        var meals = 0
        var mealTs = Long.MIN_VALUE
        var mealId = Long.MIN_VALUE
        while (true) {
            val page = db.loggedMealDao().pageFrom(mealTs, mealId, Archive.BATCH)
            if (page.isEmpty()) break
            for (r in page) Archive.write(rw, r)
            meals += page.size
            mealTs = page.last().tsMs
            mealId = page.last().id
            if (page.size < Archive.BATCH) break
        }
        counts = counts.copy(meals = meals)

        var strokes = 0
        var strokeCursor = Long.MIN_VALUE
        while (true) {
            val page = db.paintStrokeDao().pageFrom(strokeCursor, Archive.BATCH)
            if (page.isEmpty()) break
            for (r in page) Archive.write(rw, r)
            strokes += page.size
            strokeCursor = page.last().id
            if (page.size < Archive.BATCH) break
        }
        counts = counts.copy(strokes = strokes)

        // Parent before its fixes: a fix names its bout by `clientId`, so a streaming restore can
        // resolve the link without buffering.
        var exerciseSessions = 0
        var exerciseFixes = 0
        var sessionStart = Long.MIN_VALUE
        var sessionId = Long.MIN_VALUE
        while (true) {
            val page = db.exerciseSessionDao().pageFrom(sessionStart, sessionId, Archive.BATCH)
            if (page.isEmpty()) break
            for (s in page) {
                Archive.write(rw, s)
                var fixTs = Long.MIN_VALUE
                var fixId = Long.MIN_VALUE
                while (true) {
                    val fixes = db.exerciseFixDao().pageFrom(s.id, fixTs, fixId, Archive.BATCH)
                    if (fixes.isEmpty()) break
                    for (f in fixes) Archive.write(rw, f, s.clientId)
                    exerciseFixes += fixes.size
                    fixTs = fixes.last().tsMs
                    fixId = fixes.last().id
                    if (fixes.size < Archive.BATCH) break
                }
            }
            exerciseSessions += page.size
            sessionStart = page.last().startMs
            sessionId = page.last().id
            if (page.size < Archive.BATCH) break
        }
        counts = counts.copy(exerciseSessions = exerciseSessions, exerciseFixes = exerciseFixes)

        val basal = db.basalScheduleDao().all()
        for (r in basal) Archive.write(rw, r)

        val foods = db.foodDao().allCustom()
        for (r in foods) Archive.write(rw, r)

        // Positional index, file-scoped: the stored `mealId` is per-device and cannot travel.
        val savedMeals = db.savedMealDao().allMeals()
        val itemsByMeal = db.savedMealDao().allItems().groupBy { it.mealId }
        var savedItems = 0
        savedMeals.forEachIndexed { index, meal ->
            Archive.write(rw, meal, index)
            itemsByMeal[meal.id]?.forEach { item ->
                Archive.write(rw, item, index)
                savedItems++
            }
        }

        val insulinTypes = db.insulinTypeDao().allCustom()
        for (r in insulinTypes) Archive.write(rw, r)

        val sources = db.cgmSourceDao().all()
        for (r in sources) Archive.write(rw, r)

        val profiles = db.serverProfileDao().all()
        for (r in profiles) Archive.write(rw, r)

        val conformal = db.conformalDeltaDao().all()
        for (r in conformal) Archive.write(rw, r)

        val loras = db.loraDao().all()
        for (r in loras) Archive.write(rw, r)

        val tombstones = db.eventTombstoneDao().all()
        for (r in tombstones) Archive.write(rw, r)

        val infills = db.bgInfillDao().allPromoted()
        for (r in infills) Archive.write(rw, r)

        return counts.copy(
            tombstones = tombstones.size,
            infills = infills.size,
            basal = basal.size,
            foods = foods.size,
            savedMeals = savedMeals.size,
            savedItems = savedItems,
            insulinTypes = insulinTypes.size,
            sources = sources.size,
            profiles = profiles.size,
            conformal = conformal.size,
            loras = loras.size,
        )
    }

    /** Its absence is the only way a reader tells a truncated archive from a short one. */
    private fun writeEnd(w: Writer, c: ArchiveCounts) {
        val rw = Archive.RecordWriter(w)
        rw.open(Archive.T_END)
        rw.put(Archive.T_READING, c.readings)
        rw.put(Archive.T_SAMPLE, c.samples)
        rw.put(Archive.T_DOSE, c.doses)
        rw.put(Archive.T_MEAL, c.meals)
        rw.put(Archive.T_BASAL, c.basal)
        rw.put(Archive.T_FOOD, c.foods)
        rw.put(Archive.T_SAVED_MEAL, c.savedMeals)
        rw.put(Archive.T_SAVED_ITEM, c.savedItems)
        rw.put(Archive.T_INSULIN, c.insulinTypes)
        rw.put(Archive.T_STROKE, c.strokes)
        rw.put(Archive.T_SOURCE, c.sources)
        rw.put(Archive.T_PROFILE, c.profiles)
        rw.put(Archive.T_CONFORMAL, c.conformal)
        rw.put(Archive.T_LORA, c.loras)
        rw.put(Archive.T_EXERCISE, c.exerciseSessions)
        rw.put(Archive.T_EXERCISE_FIX, c.exerciseFixes)
        rw.put(Archive.T_TOMBSTONE, c.tombstones)
        rw.put(Archive.T_INFILL, c.infills)
        rw.close()
    }

    private companion object {
        /** 64 KiB: a page of readings is a couple of `write` syscalls. */
        const val BUF = 1 shl 16
    }
}
