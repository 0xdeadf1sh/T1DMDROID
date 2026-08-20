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

/** What an export actually wrote, per table. Reported to the user, and written into the archive's
 *  own `end` record so a restore can say whether it applied everything the file claimed to hold. */
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
 * Streams the whole local record out as a gzipped [Archive] document.
 *
 * Every large table is walked by keyset page (see `CgmReadingDao.pageFrom`), and each row is
 * appended and discarded — so the heap cost of exporting a year is the same as exporting a day. The
 * gzip stream is fed continuously rather than at the end, which is what keeps a 15 MB document from
 * ever existing anywhere at once: on disk it lands at roughly a tenth of that.
 *
 * **What is deliberately not here.** The outbox (a queue of pushes for a token the restoring install
 * will not have), the raw advert capture (forensics, unbounded, and meaningless off the device that
 * heard it), `prediction` and `hw_telemetry` (recomputed from the readings this archive does carry),
 * and the legacy `dose_event` table, superseded by `logged_dose`. An UNPROMOTED `bg_infill` row is out
 * for a reason of its own: a fill is a model's reconstruction of a gap, not evidence, and the
 * artifact that made it may not exist on the machine the archive is restored to — a restore would
 * carry one model's guess into another model's history. The readings the gaps sit in ARE carried,
 * so a fill can be made again. The `rw` server token is absent by construction: it lives in the
 * Keystore and has never been a column.
 *
 * A PROMOTED `bg_infill` row IS carried, and the exclusion above is exactly why. Promotion put the
 * value into `sample`, which the archive does carry, and this row is the only place its 90 % band
 * exists — the wire carries a boolean and no fan. Leaving it out would restore a reconstructed
 * sample with no uncertainty beside it and no way to demote it, which is the state
 * `BgInfillEntity`'s own documentation says must never exist.
 *
 * `cgm_sample_raw` is out too, and for a reason the others do not have: it is the one table with a
 * RETENTION BOUND (`T1dmRepository.RAW_SAMPLE_RETENTION_MS`). Carrying it would put rows in the file
 * that the phone deletes on a timer, and the restore merges rather than replaces — so an archive
 * taken today and restored next month would re-add samples the retention had already dropped, and do
 * it again on every restore. A bounded store and a keep-forever document cannot both be right about
 * the same row. Nothing is lost by the omission that the archive does not already carry: the grid
 * series every reader consumes is in here in full, and these rows derive nothing.
 *
 * **This file carries the user's LOCATION.** `exercise_fix` is in it because the archive is the local
 * full-fidelity restore path and a table left out of it is silently lost on a wipe-and-restore — but
 * the consequence is that a `t1dm.archive` holds the GPS tracks of every walk and run recorded, and
 * so of the user's home, their workplace and the routes between. It never crosses the wire and it is
 * exported only where the user sends it, which is what makes carrying it a decision rather than a
 * leak. Nothing here may be relaxed into an automatic upload.
 */
class ArchiveWriter(private val db: AppDatabase) {

    /**
     * Write the archive to [out], which is NOT closed here — the caller owns the SAF stream and
     * closes it. The gzip trailer is still written (`finish`), so the document is complete and
     * readable the moment this returns.
     *
     * [configJson] is the already-rendered settings document, passed through verbatim; this module
     * knows nothing of which keys are exportable, which stays where the allowlist is.
     *
     * The whole walk runs inside one deferred READ transaction so the archive is a consistent
     * snapshot rather than a smear across however long the export took. Under WAL a long reader
     * does not block the CGM service's writes; it only defers checkpointing for the duration.
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
        // The settings document rides inside the header rather than as a record: it is one object,
        // it is small, and having it present before the first data line means a restore can apply
        // (or refuse) the configuration without buffering the rest of the file to look for it.
        //
        // MINIFIED, never embedded as handed over. `SettingsStore.exportJson` pretty-prints — it was
        // written for a document a human might open — and pasting that in verbatim put newlines
        // inside the header, which split one record across several lines and made the whole archive
        // unreadable to its own reader. Re-encoding through the compact parser makes the invariant
        // hold whatever a caller's formatting happens to be, rather than resting on every caller
        // remembering it.
        if (configJson != null) {
            val compact = runCatching {
                Archive.json.encodeToString(
                    JsonObject.serializer(),
                    Archive.json.parseToJsonElement(configJson).jsonObject,
                )
            }.getOrNull()
            // A settings document that will not parse is dropped rather than embedded: the rows are
            // the point of the archive, and an unparseable header would cost the user all of them.
            if (compact != null) rw.putRaw("config", compact)
        }
        w.write("}\n")
    }

    private suspend fun writeBody(w: Writer): ArchiveCounts {
        val rw = Archive.RecordWriter(w)
        var counts = ArchiveCounts()

        // ── readings: the big one, walked per source down the primary key ──
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

        // ── the wide projection ──
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

        // ── logged events, on the (tsMs, id) cursor ──
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

        // ── drawings, on the rowid ──
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

        // ── exercise bouts, each followed by its own track ──
        //
        // Interleaved rather than written as two independent walks: a fix names its bout by the
        // bout's `clientId`, and emitting the parent first is what lets the restore resolve that
        // link without buffering a whole file's fixes to wait for it.
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

        // ── the bounded tables: a schedule's day of injections, a catalogue, a handful of rows ──
        val basal = db.basalScheduleDao().all()
        for (r in basal) Archive.write(rw, r)

        val foods = db.foodDao().allCustom()
        for (r in foods) Archive.write(rw, r)

        // Saved meals are emitted with a positional index and their portions reference it. The
        // stored `mealId` cannot travel: it is autogenerated per device, so on the restoring phone
        // it names a different meal — or none. The index is scoped to this file alone.
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

        // Deletions. Bounded — one row per event the patient ever deleted — so a one-shot read.
        val tombstones = db.eventTombstoneDao().all()
        for (r in tombstones) Archive.write(rw, r)

        // PROMOTED fills only. See the exclusion note at the top of this file.
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

    /** The terminator. Its absence is the only way a reader can tell a truncated archive from a
     *  short one, so it is written last and unconditionally. */
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
        /** 64 KiB on both the deflater and the character buffer: large enough that a page of
         *  readings is a couple of `write` syscalls, small enough to be free on a phone. */
        const val BUF = 1 shl 16
    }
}
