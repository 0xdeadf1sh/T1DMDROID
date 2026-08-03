package com.t1dm.data.backup

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.InsulinTypeEntity
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
        var line = reader.readLine()
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
            Archive.T_STROKE -> {
                val r = runCatching { Archive.readStroke(o) }.getOrNull() ?: return s.skip()
                s.strokes.add(r)
                if (s.strokes.size >= Archive.BATCH) flushStrokes(s)
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
        flushStrokes(s)
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
        val rows = s.doses.toList()
        s.doses.clear()
        val added = tx { db.loggedDoseDao().insertIgnoreAll(rows).count { it != -1L } }
        s.applied = s.applied.copy(doses = s.applied.doses + added)
        s.duplicates += rows.size - added
    }

    private suspend fun flushMeals(s: MergeState) {
        if (s.meals.isEmpty()) return
        val rows = s.meals.toList()
        s.meals.clear()
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
            val present = db.foodDao().customKeys().mapTo(HashSet()) { it.name to it.brand }
            val fresh = s.foods.filter { present.add(it.name to it.brand) }
            if (fresh.isNotEmpty()) db.foodDao().insertAll(fresh)
            s.applied = s.applied.copy(foods = fresh.size)
            s.duplicates += s.foods.size - fresh.size
        }

        if (s.insulinTypes.isNotEmpty()) {
            val present = db.insulinTypeDao().customNames().toHashSet()
            val fresh = s.insulinTypes.filter { present.add(it.name) }
            if (fresh.isNotEmpty()) db.insulinTypeDao().insertAll(fresh)
            s.applied = s.applied.copy(insulinTypes = fresh.size)
            s.duplicates += s.insulinTypes.size - fresh.size
        }

        if (s.savedMeals.isNotEmpty()) {
            val present = db.savedMealDao().names().toHashSet()
            // Archive index → the local row id the insert minted. Items whose meal was skipped as a
            // duplicate find no entry here and are dropped with it, rather than being orphaned onto
            // a meal that already has its own portions.
            val idByIndex = HashMap<Int, Long>(s.savedMeals.size)
            var mealsAdded = 0
            for ((ix, meal) in s.savedMeals) {
                if (!present.add(meal.name)) continue
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

        // A restored source or profile must never claim the active flag while a local one holds it:
        // both tables carry an exactly-one-active invariant, and breaking it would leave the phone
        // reading from — or syncing to — something the user never selected. When the table is empty
        // the FIRST restored row may take it, which is what makes a fresh install usable at once.
        if (s.sources.isNotEmpty()) {
            var free = db.cgmSourceDao().activeCount() == 0
            val rows = s.sources.mapNotNull { o ->
                runCatching { Archive.readSource(o, active = false) }.getOrNull()
            }
            val ids = db.cgmSourceDao().insertIgnoreAll(rows)
            var added = 0
            for ((i, id) in ids.withIndex()) {
                if (id == -1L) continue
                added++
                if (free) {
                    db.cgmSourceDao().setActive(rows[i].sourceId)
                    free = false
                }
            }
            s.applied = s.applied.copy(sources = added)
            s.duplicates += rows.size - added
            s.skipped += s.sources.size - rows.size
        }

        if (s.profiles.isNotEmpty()) {
            var free = db.serverProfileDao().activeCount() == 0
            val rows = s.profiles.mapNotNull { o ->
                runCatching { Archive.readProfile(o, active = false) }.getOrNull()
            }
            val ids = db.serverProfileDao().insertIgnoreAll(rows)
            var added = 0
            for ((i, id) in ids.withIndex()) {
                if (id == -1L) continue
                added++
                if (free) {
                    db.serverProfileDao().setActive(rows[i].id)
                    free = false
                }
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
    }

    private suspend fun <R> tx(body: suspend () -> R): R =
        db.useWriterConnection { transactor -> transactor.immediateTransaction { body() } }

    /** Everything in flight across the read: the pending batches, the lazily-loaded key sets, and
     *  the running tallies. One object so a flush needs no six-parameter signature. */
    private class MergeState {
        val readings = ArrayList<CgmReadingEntity>(Archive.BATCH)
        val samples = ArrayList<SampleEntity>(Archive.BATCH)
        val doses = ArrayList<LoggedDoseEntity>(Archive.BATCH)
        val meals = ArrayList<LoggedMealEntity>(Archive.BATCH)
        val strokes = ArrayList<PaintStrokeEntity>(Archive.BATCH)

        val basal = ArrayList<BasalScheduleEntity>()
        val foods = ArrayList<FoodEntity>()
        val insulinTypes = ArrayList<InsulinTypeEntity>()
        val conformal = ArrayList<ConformalDeltaEntity>()
        val sources = ArrayList<JsonObject>()
        val profiles = ArrayList<JsonObject>()
        val savedMeals = ArrayList<Pair<Int, SavedMealEntity>>()
        val savedItems = ArrayList<Pair<Int, SavedMealItemEntity>>()

        /** Loaded on the first drawing seen, not up front: an archive with no strokes should not
         *  pay for a scan of a table it is not going to touch. */
        var strokeKeys: HashSet<Long>? = null

        var applied = ArchiveCounts()
        var duplicates = 0
        var skipped = 0

        fun skip() { skipped++ }
    }

    private companion object {
        const val BUF = 1 shl 16
    }
}
