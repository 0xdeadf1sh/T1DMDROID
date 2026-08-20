package com.t1dm.data.backup

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.LoraEntity
import com.t1dm.data.db.ExerciseFixEntity
import com.t1dm.data.db.ExerciseSessionEntity
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
import com.t1dm.data.db.EventTombstoneEntity
import com.t1dm.data.db.TOMBSTONE_KIND_DOSE
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.PaintStrokeEntity
import com.t1dm.data.db.SampleEntity
import com.t1dm.data.db.SavedMealEntity
import com.t1dm.data.db.SavedMealItemEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** Thrown when the stream is not a `t1dm.archive` at all, so the caller can fall back to the older
 *  settings-and-drawings reader rather than reporting a failure on a file that is simply not this. */
class NotAnArchiveException(message: String) : IllegalArgumentException(message)

/**
 * What a restore did. [applied] counts rows actually written; [duplicates] counts records the phone
 * already held, which is the expected outcome of re-importing a file and not a fault; [skipped]
 * counts records that could not be decoded at all.
 *
 * [truncated] means the archive had no `end` record — the writer was interrupted — so what was
 * restored is whatever the file managed to contain. It is surfaced rather than thrown because the
 * partial content is still worth having; the user simply has to be told the file is incomplete.
 */
class ArchiveResult(
    val configJson: String?,
    val applied: ArchiveCounts,
    val duplicates: Int,
    val skipped: Int,
    val truncated: Boolean,
    val schemaVersion: Int?,
    val createdAtMs: Long?,
)

/**
 * Reads a `t1dm.archive` back into the store, **merging** — the local row always wins.
 *
 * Every table's merge key is either a database constraint (`INSERT OR IGNORE` on a primary key or a
 * unique index) or a small preloaded key set for the tables whose natural key is a name. Nothing is
 * ever updated or replaced, so a restore can only add what is missing: importing the same file twice
 * is a no-op, and importing an old file onto a live phone cannot roll anything back.
 *
 * The restore is **not** one transaction. Each batch commits on its own, because holding the single
 * writer connection across a six-figure row count would stall the CGM service for the duration and
 * grow the WAL without bound. The merge semantics are what make that safe: an interrupted restore
 * has applied a prefix, and re-running the same file completes it without duplicating anything.
 */
class ArchiveReader(private val db: AppDatabase) {

    suspend fun read(input: InputStream): ArchiveResult {
        val stream = maybeGunzip(input)
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8), BUF)

        val headerLine = reader.readLine()
            ?: throw NotAnArchiveException("The file is empty.")
        val header = runCatching { Archive.json.parseToJsonElement(headerLine).jsonObject }
            .getOrElse { throw NotAnArchiveException("Not a T1DM archive (the first line is not JSON).") }
        if (header.str("format") != Archive.FORMAT) {
            throw NotAnArchiveException("Not a T1DM archive (wrong format tag).")
        }

        val state = MergeState()
        var truncated = true
        // A damaged archive stops the READ, never the restore.
        //
        // This catch is the whole of what makes the `end`-record design work, because the failure it
        // handles is the ordinary one. Every archive is gzip, and a gzip member whose deflate stream
        // or 8-byte trailer was cut short raises EOFException from deep inside the inflater on
        // `readLine` — not a clean end-of-stream. Without this, the exception escaped `read()`
        // entirely: the flush below never ran, the partial restore was never committed or counted,
        // and the caller reported "Restore failed — Unexpected end of ZLIB input stream" for a file
        // most of which was perfectly good. Losing a single trailing byte was enough to do it, even
        // with every record including the terminator already read.
        //
        // `truncated` is left to say what it always said: whether the terminator was seen. A file
        // that lost only its gzip trailer still carries a complete record set and is reported whole.
        var line: String? = null
        try {
            line = reader.readLine()
            while (line != null) {
                if (line.isNotEmpty()) {
                    val record = runCatching { Archive.json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (record == null) {
                        state.skipped++
                    } else if (record.tag() == Archive.T_END) {
                        truncated = false
                    } else {
                        consume(record, state)
                    }
                }
                line = reader.readLine()
            }
        } catch (_: IOException) {
            // Swallowed deliberately, and reported through the result rather than a log: `truncated`
            // and the applied/skipped tallies are what the panel shows the user, and they are a
            // better account of what happened than a message they will never see.
        }

        flushStreamed(state)
        applyBounded(state)

        return ArchiveResult(
            configJson = (header["config"] as? JsonObject)
                ?.let { Archive.json.encodeToString(JsonObject.serializer(), it) },
            applied = state.applied,
            duplicates = state.duplicates,
            skipped = state.skipped,
            truncated = truncated,
            schemaVersion = header.int("schema"),
            createdAtMs = header.long("createdAtMs"),
        )
    }

    /**
     * Sniff the two GZIP magic bytes rather than trusting a filename. A `BufferedInputStream` with a
     * mark is what makes this possible without consuming them: a stream that turns out to be plain
     * text is rewound and read as-is, so an uncompressed archive (hand-decompressed, or produced by
     * some future writer) still restores.
     */
    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, BUF)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == Archive.GZIP_MAGIC_0 && b1 == Archive.GZIP_MAGIC_1) {
            GZIPInputStream(buffered, BUF)
        } else {
            buffered
        }
    }

    /**
     * Decode one record into its pending batch, flushing that batch when it fills.
     *
     * The decode sits inside `runCatching` and the flush deliberately does NOT. Decoding is pure and
     * non-suspending, so the only thing the catch can see is a malformed record — a missing required
     * field, a bad base64 blob, an enum name a later build introduced, a corrupt polyline — and each
     * of those costs one skip and nothing else. Had the flush been inside it too, a broad catch would
     * also swallow the `CancellationException` of a restore the user backed out of, and the read
     * would grind on invisibly to the end of the file.
     */
    private suspend fun consume(o: JsonObject, s: MergeState) {
        when (o.tag()) {
            Archive.T_READING -> {
                val r = runCatching { Archive.readReading(o) }.getOrNull() ?: return s.skip()
                s.readings.add(r)
                if (s.readings.size >= Archive.BATCH) flushReadings(s)
            }
            Archive.T_SAMPLE -> {
                val r = runCatching { Archive.readSample(o) }.getOrNull() ?: return s.skip()
                s.samples.add(r)
                if (s.samples.size >= Archive.BATCH) flushSamples(s)
            }
            Archive.T_DOSE -> {
                val r = runCatching { Archive.readDose(o) }.getOrNull() ?: return s.skip()
                s.doses.add(r)
                if (s.doses.size >= Archive.BATCH) flushDoses(s)
            }
            Archive.T_MEAL -> {
                val r = runCatching { Archive.readMeal(o) }.getOrNull() ?: return s.skip()
                s.meals.add(r)
                if (s.meals.size >= Archive.BATCH) flushMeals(s)
            }
            // Applied IMMEDIATELY, not batched, and that ordering is load-bearing: a tombstone
            // has to be on record before the meal or dose it deletes is merged, or the archived
            // event is restored and the deletion is undone by the restore. Records may appear in
            // any order in the file, so [flushEvents] re-applies the deletions afterwards too.
            Archive.T_TOMBSTONE -> {
                val r = runCatching { Archive.readTombstone(o) }.getOrNull() ?: return s.skip()
                s.tombstones.add(r)
                // Upsert only where the file's deletion is at least as new as the one on record. A
                // blind upsert walks a local tombstone's `updatedAt` BACKWARDS, and that stamp is
                // the whole ordering guard: below the deleted event's own `updatedAt`, the next
                // catch-up re-hydrates what the patient deleted and the deletion never re-applies.
                val ix = deletions(s)
                if ((ix[r.clientId] ?: Long.MIN_VALUE) < r.updatedAt) {
                    tx { db.eventTombstoneDao().upsert(r) }
                    ix[r.clientId] = r.updatedAt
                }
                s.applied = s.applied.copy(tombstones = s.applied.tombstones + 1)
            }
            // A promoted fill's band. Merge-only: a row the local table already has at that slot
            // is this install's own and is not displaced by an archived one.
            Archive.T_INFILL -> {
                val r = runCatching { Archive.readInfill(o) }.getOrNull() ?: return s.skip()
                tx { db.bgInfillDao().insertIgnoreAll(listOf(r)) }
                s.applied = s.applied.copy(infills = s.applied.infills + 1)
            }
            Archive.T_STROKE -> {
                val r = runCatching { Archive.readStroke(o) }.getOrNull() ?: return s.skip()
                s.strokes.add(r)
                if (s.strokes.size >= Archive.BATCH) flushStrokes(s)
            }
            Archive.T_EXERCISE -> {
                val r = runCatching { Archive.readExercise(o) }.getOrNull() ?: return s.skip()
                s.exerciseSessions.add(r)
                if (s.exerciseSessions.size >= Archive.BATCH) flushExerciseSessions(s)
            }
            Archive.T_EXERCISE_FIX -> {
                val r = runCatching { Archive.readExerciseFix(o) }.getOrNull() ?: return s.skip()
                s.exerciseFixes.add(r)
                if (s.exerciseFixes.size >= Archive.BATCH) flushExerciseFixes(s)
            }
            // The bounded tables are buffered whole: they are small, and two of them (saved meals,
            // basal schedules) can only be merged once the entire set is known.
            Archive.T_BASAL -> {
                val r = runCatching { Archive.readBasal(o) }.getOrNull()
                if (r == null) s.skip() else s.basal.add(r)
            }
            Archive.T_FOOD -> {
                val r = runCatching { Archive.readFood(o) }.getOrNull()
                if (r == null) s.skip() else s.foods.add(r)
            }
            Archive.T_INSULIN -> {
                val r = runCatching { Archive.readInsulinType(o) }.getOrNull()
                if (r == null) s.skip() else s.insulinTypes.add(r)
            }
            Archive.T_CONFORMAL -> {
                val r = runCatching { Archive.readConformal(o) }.getOrNull()
                if (r == null) s.skip() else s.conformal.add(r)
            }
            Archive.T_LORA -> {
                val r = runCatching { Archive.readLora(o) }.getOrNull()
                if (r == null) s.skip() else s.loras.add(r)
            }
            // Sources and profiles are held as raw records: their `active` flag is not the file's to
            // decide, and the value it must take is only known once the whole file has been read and
            // the local tables consulted. Decoding is therefore deferred to `applyBounded`.
            Archive.T_SOURCE -> s.sources.add(o)
            Archive.T_PROFILE -> s.profiles.add(o)
            Archive.T_SAVED_MEAL -> {
                val r = runCatching { readSavedMeal(o) }.getOrNull()
                if (r == null) s.skip() else s.savedMeals.add(r)
            }
            Archive.T_SAVED_ITEM -> {
                val r = runCatching { readSavedItem(o) }.getOrNull()
                if (r == null) s.skip() else s.savedItems.add(r)
            }
            else -> s.skip() // a record kind this build does not know
        }
    }

    private fun readSavedMeal(o: JsonObject): Pair<Int, SavedMealEntity> {
        val ix = o.int("ix") ?: throw IllegalArgumentException("savedMeal record has no ix")
        return ix to SavedMealEntity(
            name = o.str("nm") ?: throw IllegalArgumentException("savedMeal record has no nm"),
            updatedAt = o.long("ua") ?: throw IllegalArgumentException("savedMeal record has no ua"),
        )
    }

    private fun readSavedItem(o: JsonObject): Pair<Int, SavedMealItemEntity> {
        val mix = o.int("mix") ?: throw IllegalArgumentException("savedItem record has no mix")
        return mix to SavedMealItemEntity(
            // Both ids are placeholders. `mealId` is resolved in `applyBounded` once the parent meal
            // has been inserted and has a local id.
            mealId = 0L,
            // The archived `foodId` is deliberately dropped rather than carried. It is an
            // autogenerated per-device id and a soft link only — used to re-open the portion in the
            // builder — so on the restoring phone it names whatever unrelated food happens to hold
            // that id. The nutrition fields beside it are snapshotted at save time, which is exactly
            // why the schema can afford to lose the link.
            foodId = null,
            name = o.str("nm") ?: throw IllegalArgumentException("savedItem record has no nm"),
            grams = o.dbl("g") ?: throw IllegalArgumentException("savedItem record has no g"),
            carbsPer100g = o.dbl("c100") ?: throw IllegalArgumentException("savedItem record has no c100"),
            gi = o.dbl("gi"),
            customCurve = o.blob("cc"),
        )
    }

    // ── streamed tables ───────────────────────────────────────────────────────────────────────

    private suspend fun flushStreamed(s: MergeState) {
        flushReadings(s)
        flushSamples(s)
        flushDoses(s)
        flushMeals(s)
        // A deletion in the file may have been read AFTER the event it deletes, so re-apply every
        // one now that both are in — the file's record order is not guaranteed. Deletions already
        // on record are the flushes' own business ([deleted]); this pass is only for the file's.
        applyTombstones(s)
        flushStrokes(s)
        flushExerciseFixes(s)
    }

    /**
     * Every deletion on record, `clientId` → its stamp, loaded once and then kept current.
     *
     * A restore is the one event path that does not run through
     * [com.t1dm.data.T1dmRepository.hydrateMealEvent], which is where the deletion filter lives:
     * [flushDoses] and [flushMeals] insert into the DAO directly. Without this the archived copy of
     * a meal the patient deleted merges cleanly — deletion is a hard delete, so the unique
     * `clientId` index is free — and catch-up cannot repair it, because the event high-water mark
     * is a MAX over the live tables and never moves back over the resurrected row. Only a latched
     * full resync would take it out again.
     *
     * Loaded lazily like [MergeState.strokeKeys]: one bounded scan, and only for a file that
     * carries events at all.
     */
    private suspend fun deletions(s: MergeState): HashMap<String, Long> =
        s.deletions ?: HashMap<String, Long>().also { m ->
            for (t in db.eventTombstoneDao().all()) m[t.clientId] = t.updatedAt
            s.deletions = m
        }

    /** True when a deletion on record covers [clientId] at or after [updatedAt] — the same rule
     *  [com.t1dm.data.T1dmRepository.hydrateMealEvent] applies to a catch-up. */
    private suspend fun deleted(s: MergeState, clientId: String, updatedAt: Long): Boolean =
        (deletions(s)[clientId] ?: Long.MIN_VALUE) >= updatedAt

    /**
     * Re-apply the file's deletions once every event it carried is in.
     *
     * The guard is on the LIVE ROW, not on the tombstone table: the read branch has already stored
     * this deletion, so comparing the two compares a value against itself and can never fire. A row
     * authored strictly after the deletion is a re-creation under the same key and survives.
     */
    private suspend fun applyTombstones(s: MergeState) {
        if (s.tombstones.isEmpty()) return
        tx {
            for (t in s.tombstones) {
                if (t.kind == TOMBSTONE_KIND_DOSE) {
                    db.loggedDoseDao().byClientId(t.clientId)
                        ?.takeIf { it.updatedAt <= t.updatedAt }
                        ?.let { db.loggedDoseDao().delete(it.id) }
                } else {
                    db.loggedMealDao().byClientId(t.clientId)
                        ?.takeIf { it.updatedAt <= t.updatedAt }
                        ?.let { db.loggedMealDao().delete(it.id) }
                }
            }
        }
    }

    private suspend fun flushReadings(s: MergeState) {
        if (s.readings.isEmpty()) return
        val rows = s.readings.toList()
        s.readings.clear()
        val added = tx { db.cgmReadingDao().insertIgnoreAll(rows).count { it != -1L } }
        s.applied = s.applied.copy(readings = s.applied.readings + added)
        s.duplicates += rows.size - added
    }

    private suspend fun flushSamples(s: MergeState) {
        if (s.samples.isEmpty()) return
        val rows = s.samples.toList()
        s.samples.clear()
        val added = tx { db.sampleDao().insertIgnoreAll(rows).count { it != -1L } }
        s.applied = s.applied.copy(samples = s.applied.samples + added)
        s.duplicates += rows.size - added
    }

    private suspend fun flushDoses(s: MergeState) {
        if (s.doses.isEmpty()) return
        val all = s.doses.toList()
        s.doses.clear()
        val rows = all.filterNot { deleted(s, it.clientId, it.updatedAt) }
        s.skipped += all.size - rows.size
        if (rows.isEmpty()) return
        val added = tx { db.loggedDoseDao().insertIgnoreAll(rows).count { it != -1L } }
        s.applied = s.applied.copy(doses = s.applied.doses + added)
        s.duplicates += rows.size - added
    }

    private suspend fun flushMeals(s: MergeState) {
        if (s.meals.isEmpty()) return
        val all = s.meals.toList()
        s.meals.clear()
        val rows = all.filterNot { deleted(s, it.clientId, it.updatedAt) }
        s.skipped += all.size - rows.size
        if (rows.isEmpty()) return
        val added = tx { db.loggedMealDao().insertIgnoreAll(rows).count { it != -1L } }
        s.applied = s.applied.copy(meals = s.applied.meals + added)
        s.duplicates += rows.size - added
    }

    /**
     * Drawings have no database constraint to merge on, so the authoring instant is the key — the
     * same one the settings-and-drawings import has always used. Keys are added to the set as they
     * are accepted, which collapses duplicates WITHIN a file as well as against the store.
     */
    private suspend fun flushStrokes(s: MergeState) {
        if (s.strokes.isEmpty()) return
        val rows = s.strokes.toList()
        s.strokes.clear()
        val seen = s.strokeKeys ?: db.paintStrokeDao().allCreatedAt().toHashSet().also { s.strokeKeys = it }
        val fresh = ArrayList<PaintStrokeEntity>(rows.size)
        for (r in rows) if (seen.add(r.createdAtMs)) fresh.add(r)
        if (fresh.isNotEmpty()) tx { db.paintStrokeDao().insertAll(fresh) }
        s.applied = s.applied.copy(strokes = s.applied.strokes + fresh.size)
        s.duplicates += rows.size - fresh.size
    }

    /**
     * Bouts merge on their unique `clientId`, and the rowids the insert hands back are kept: they are
     * the only way an archived fix can find the local bout its parent became.
     */
    private suspend fun flushExerciseSessions(s: MergeState) {
        if (s.exerciseSessions.isEmpty()) return
        val rows = s.exerciseSessions.toList()
        s.exerciseSessions.clear()
        val ids = tx { db.exerciseSessionDao().insertIgnoreAll(rows) }
        var added = 0
        for ((i, row) in rows.withIndex()) {
            val id = ids.getOrElse(i) { -1L }
            if (id != -1L) {
                s.exerciseSessionIds[row.clientId] = id
                added++
            }
        }
        s.applied = s.applied.copy(exerciseSessions = s.applied.exerciseSessions + added)
        s.duplicates += rows.size - added
    }

    /**
     * A fix is applied only when its bout was inserted by THIS restore.
     *
     * The pending bouts are flushed first, so every parent the file has emitted so far is on disk and
     * in the id map — the writer emits a bout before its own track, which is what makes that
     * sufficient. A fix whose bout is missing from the map belongs to one the phone already holds,
     * and that bout already has its own track: appending an archived copy would double the polyline
     * rather than restore anything. It is a duplicate, not a fault, which is the same call
     * `applyBounded` makes for a saved meal's portions.
     */
    private suspend fun flushExerciseFixes(s: MergeState) {
        flushExerciseSessions(s)
        if (s.exerciseFixes.isEmpty()) return
        val rows = s.exerciseFixes.toList()
        s.exerciseFixes.clear()
        val fresh = rows.mapNotNull { (cid, fix) ->
            s.exerciseSessionIds[cid]?.let { fix.copy(sessionId = it) }
        }
        if (fresh.isNotEmpty()) tx { db.exerciseFixDao().insertAll(fresh) }
        s.applied = s.applied.copy(exerciseFixes = s.applied.exerciseFixes + fresh.size)
        s.duplicates += rows.size - fresh.size
    }

    // ── bounded tables, applied once the whole file has been seen ─────────────────────────────

    private suspend fun applyBounded(s: MergeState) = tx {
        // Basal schedules merge WHOLE. Their rows carry no per-row identity, so merging injection by
        // injection could interleave two schedules into a day the user never configured. An imported
        // schedule also always lands inactive: the active one is a live clinical choice on THIS
        // phone, and a file must not be able to switch it.
        if (s.basal.isNotEmpty()) {
            val present = db.basalScheduleDao().scheduleIds().toHashSet()
            val fresh = s.basal.filter { present.add(it.scheduleId) }.map { it.copy(active = false) }
            if (fresh.isNotEmpty()) db.basalScheduleDao().insertAll(fresh)
            s.applied = s.applied.copy(basal = fresh.size)
            s.duplicates += s.basal.size - fresh.size
        }

        if (s.foods.isNotEmpty()) {
            val budget = Multiset(db.foodDao().customKeys().map { it.name to it.brand })
            val fresh = s.foods.filter { budget.claim(it.name to it.brand) }
            if (fresh.isNotEmpty()) db.foodDao().insertAll(fresh)
            s.applied = s.applied.copy(foods = fresh.size)
            s.duplicates += s.foods.size - fresh.size
        }

        if (s.insulinTypes.isNotEmpty()) {
            val budget = Multiset(db.insulinTypeDao().customNames())
            val fresh = s.insulinTypes.filter { budget.claim(it.name) }
            if (fresh.isNotEmpty()) db.insulinTypeDao().insertAll(fresh)
            s.applied = s.applied.copy(insulinTypes = fresh.size)
            s.duplicates += s.insulinTypes.size - fresh.size
        }

        if (s.savedMeals.isNotEmpty()) {
            val budget = Multiset(db.savedMealDao().names())
            // Archive index → the local row id the insert minted. Items whose meal was skipped as a
            // duplicate find no entry here and are dropped with it, rather than being orphaned onto
            // a meal that already has its own portions.
            val idByIndex = HashMap<Int, Long>(s.savedMeals.size)
            var mealsAdded = 0
            for ((ix, meal) in s.savedMeals) {
                if (!budget.claim(meal.name)) continue
                idByIndex[ix] = db.savedMealDao().insertMeal(meal)
                mealsAdded++
            }
            val items = s.savedItems.mapNotNull { (mix, item) ->
                idByIndex[mix]?.let { item.copy(mealId = it) }
            }
            if (items.isNotEmpty()) db.savedMealDao().insertItems(items)
            s.applied = s.applied.copy(savedMeals = mealsAdded, savedItems = items.size)
            // Both halves count. A portion dropped because its meal was already held is a DUPLICATE,
            // not a loss — and counting only the meals left those portions in neither tally, so a
            // second restore of the same file reported fewer records seen than the first applied.
            s.duplicates += (s.savedMeals.size - mealsAdded) + (s.savedItems.size - items.size)
        }

        // A restored source or profile must never claim the authoritative flag while a local one holds
        // it: both tables carry an exactly-one invariant, and breaking it would leave the phone
        // reading from — or syncing to — something the user never selected. When the table is empty
        // the FIRST restored row may take it, which is what makes a fresh install usable at once.
        //
        // A source's `active` flag carries no such invariant and rides in from the file as stored:
        // restoring a backup restores which sensors were being read, and several of them may be.
        if (s.sources.isNotEmpty()) {
            val free = db.cgmSourceDao().authoritativeCount() == 0
            val rows = renumber(
                s.sources.mapNotNull { o ->
                    runCatching { Archive.readSource(o, authoritative = false) }.getOrNull()
                },
            )
            val added = db.cgmSourceDao().insertIgnoreAll(rows).count { it != -1L }
            if (free) {
                // Every row landed non-authoritative; exactly one is then chosen. The archive's own
                // flag first — it names the sensor that was actually being believed — and the most
                // recently HEARD source as the fallback for an archive written before the flag was
                // carried. Never the first row: `cgm_source` accumulates every sensor the phone has
                // ever seen and the export walks it oldest-first, so "first" means the one longest
                // retired.
                val claimed = s.sources.firstOrNull { it.bool("ac") == true }?.str("sid")
                val target = claimed?.takeIf { id -> rows.any { it.sourceId == id } }
                    ?: rows.maxByOrNull { it.lastSeenMs ?: Long.MIN_VALUE }?.sourceId
                if (target != null) db.cgmSourceDao().setAuthoritative(target)
            }
            s.applied = s.applied.copy(sources = added)
            s.duplicates += rows.size - added
            s.skipped += s.sources.size - rows.size
        }

        if (s.profiles.isNotEmpty()) {
            val free = db.serverProfileDao().activeCount() == 0
            val rows = s.profiles.mapNotNull { o ->
                runCatching { Archive.readProfile(o, active = false) }.getOrNull()
            }
            val added = db.serverProfileDao().insertIgnoreAll(rows).count { it != -1L }
            if (free) {
                // Same rule as the sources above; the fallback here is the most recently updated
                // profile, there being no "last seen" for a server.
                val claimed = s.profiles.firstOrNull { it.bool("ac") == true }?.str("id")
                val target = claimed?.takeIf { id -> rows.any { it.id == id } }
                    ?: rows.maxByOrNull { it.updatedAtMs }?.id
                if (target != null) db.serverProfileDao().setActive(target)
            }
            s.applied = s.applied.copy(profiles = added)
            s.duplicates += rows.size - added
            s.skipped += s.profiles.size - rows.size
        }

        if (s.conformal.isNotEmpty()) {
            val added = db.conformalDeltaDao().insertIgnoreAll(s.conformal).count { it != -1L }
            s.applied = s.applied.copy(conformal = added)
            s.duplicates += s.conformal.size - added
        }

        if (s.loras.isNotEmpty()) {
            // Restored DETACHED, whatever the file said (`Archive.write` does not carry the flag):
            // which adapter a model runs is a property of this phone, and a backup must not
            // re-attach one to a live forecast behind the user's back.
            //
            // Deduplicated on (model, weights) HERE rather than by the insert: the row's key is an
            // autoGenerated rowid, so `INSERT OR IGNORE` can never collide and would silently
            // duplicate every adapter on every restore.
            val have = db.loraDao().all()
            val fresh = s.loras.filterNot { row ->
                have.any { it.modelId == row.modelId && it.blob.contentEquals(row.blob) }
            }
            db.loraDao().insertIgnore(fresh)
            s.applied = s.applied.copy(loras = fresh.size)
            s.duplicates += s.loras.size - fresh.size
        }
    }

    private suspend fun <R> tx(body: suspend () -> R): R =
        db.useWriterConnection { transactor -> transactor.immediateTransaction { body() } }

    /**
     * Read what the phone already holds, so the incoming sources can be numbered against it.
     *
     * One query on a table with a row per sensor the phone has ever met, and only when the archive carried
     * sources at all. It runs inside [applyBounded]'s write transaction, so nothing can mint an ordinal
     * between this read and the insert that follows it.
     */
    private suspend fun renumber(rows: List<CgmSourceEntity>): List<CgmSourceEntity> =
        if (rows.isEmpty()) rows else renumbered(db.cgmSourceDao().all(), rows)

    /**
     * How many rows the store already holds under each natural key, spent down as archived rows are
     * matched against it.
     *
     * A plain "seen" set was wrong here, and quietly so. `saved_meal.name`, `food.(name, brand)` and
     * `insulin_type.name` carry no unique index and nothing on the write path enforces one — a user
     * may legitimately keep two saved meals called "Breakfast". Adding each archived row's key to a
     * set as it was accepted made the SECOND such row look like a duplicate of the first, so it was
     * dropped, its portions were dropped with it, and both were reported as rows the phone already
     * held. On an empty device, a restore lost data and said it had skipped nothing.
     *
     * Counting fixes it while keeping the idempotence that matters: N archived rows sharing a key
     * against M local ones insert exactly `max(0, N - M)`, so re-importing the same file still
     * changes nothing the second time.
     */
    private class Multiset<K>(present: Collection<K>) {
        private val counts = HashMap<K, Int>()

        init {
            for (k in present) counts[k] = (counts[k] ?: 0) + 1
        }

        /** True when this key has no local row left to account for it — i.e. insert it. */
        fun claim(key: K): Boolean {
            val remaining = counts[key] ?: 0
            if (remaining == 0) return true
            counts[key] = remaining - 1
            return false
        }
    }

    /** Everything in flight across the read: the pending batches, the lazily-loaded key sets, and
     *  the running tallies. One object so a flush needs no six-parameter signature. */
    private class MergeState {
        val readings = ArrayList<CgmReadingEntity>(Archive.BATCH)
        val samples = ArrayList<SampleEntity>(Archive.BATCH)
        val doses = ArrayList<LoggedDoseEntity>(Archive.BATCH)
        val meals = ArrayList<LoggedMealEntity>(Archive.BATCH)

        /** Every deletion the file carried, so the merge can re-apply them after the events land —
         *  the file's record order is not guaranteed. */
        val tombstones = ArrayList<EventTombstoneEntity>()
        val strokes = ArrayList<PaintStrokeEntity>(Archive.BATCH)
        val exerciseSessions = ArrayList<ExerciseSessionEntity>(Archive.BATCH)
        val exerciseFixes = ArrayList<Pair<String, ExerciseFixEntity>>(Archive.BATCH)

        /** Archived bout `clientId` → the local rowid this restore minted for it. Only bouts actually
         *  inserted are in here; see [flushExerciseFixes]. One entry per bout, not per fix. */
        val exerciseSessionIds = HashMap<String, Long>()

        val basal = ArrayList<BasalScheduleEntity>()
        val foods = ArrayList<FoodEntity>()
        val insulinTypes = ArrayList<InsulinTypeEntity>()
        val conformal = ArrayList<ConformalDeltaEntity>()
        val loras = ArrayList<LoraEntity>()
        val sources = ArrayList<JsonObject>()
        val profiles = ArrayList<JsonObject>()
        val savedMeals = ArrayList<Pair<Int, SavedMealEntity>>()
        val savedItems = ArrayList<Pair<Int, SavedMealItemEntity>>()

        /** Loaded on the first drawing seen, not up front: an archive with no strokes should not
         *  pay for a scan of a table it is not going to touch. */
        var strokeKeys: HashSet<Long>? = null

        /** `clientId` → the newest deletion stamp on record for it, live across the whole merge:
         *  loaded from the store on first use, then kept current as the file's own deletions land.
         *  See [ArchiveReader.deletions]. */
        var deletions: HashMap<String, Long>? = null

        var applied = ArchiveCounts()
        var duplicates = 0
        var skipped = 0

        fun skip() { skipped++ }
    }

    internal companion object {
        const val BUF = 1 shl 16

        /**
         * Give every source about to be inserted a per-sensor ordinal no row already [stored] uses.
         *
         * **The one field a merge insert cannot take verbatim.** Every other column of `cgm_source`
         * describes the sensor itself, so copying it out of the file is exactly right; `ordinal` describes
         * the sensor's place among the sensors on ONE phone. It is minted, once per row, inside
         * [com.t1dm.data.T1dmRepository.upsertSource]'s write transaction — and a restore does not go
         * through there, it inserts into the table directly. A file carrying 0..2 restored onto a phone
         * already holding 0..2 left six sensors sharing three numbers, permanently; with sensor names
         * hidden — the default — `CGM #1` is the only identity most surfaces show, so one label would name
         * two physical devices. That is the exact ambiguity the persisted number exists to prevent.
         *
         * The file's number is KEPT wherever it is free, so the ordinary restore — a whole backup onto a
         * fresh phone — reproduces the numbering the user already knows. Only a collision is renumbered,
         * and never a row already stored: an existing sensor's number is what the user has learned to read
         * as that device, and a restore may not move it.
         *
         * A row whose `sourceId` the phone already holds is passed through untouched and claims nothing:
         * the insert ignores it and the stored row keeps its own number. Without that, re-importing the
         * same file would burn a fresh ordinal per sensor every time and count the numbers up for ever.
         */
        fun renumbered(
            stored: List<CgmSourceEntity>,
            incoming: List<CgmSourceEntity>,
        ): List<CgmSourceEntity> {
            val known = stored.mapTo(HashSet(stored.size)) { it.sourceId }
            val taken = stored.mapTo(HashSet(stored.size)) { it.ordinal }
            var next = 0
            return incoming.map { row ->
                if (!known.add(row.sourceId)) return@map row
                if (row.ordinal >= 0 && taken.add(row.ordinal)) return@map row
                // The sentinel a file written before the column carried lands here too. Numbering it now
                // rather than on the next hydrate is what keeps an unnumbered sensor — one the privacy
                // label cannot name at all — from ever reaching the screen.
                while (!taken.add(next)) next++
                row.copy(ordinal = next)
            }
        }
    }
}
