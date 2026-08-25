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

/** Not this format at all; the caller falls back to the older settings-and-drawings reader. */
class NotAnArchiveException(message: String) : IllegalArgumentException(message)

/**
 * [duplicates] counts records the phone already held — the expected outcome of re-importing, not a
 * fault. [truncated] means no `end` record; the partial restore is surfaced rather than thrown.
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
 * Merges: the local row always wins, nothing is updated or replaced, so importing the same file
 * twice is a no-op. NOT one transaction — each batch commits alone, which the merge semantics make
 * safe: an interrupted restore has applied a prefix and re-running the file completes it.
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
        // A cut-short gzip member raises EOFException from the inflater on `readLine`, not a clean
        // end of stream; catching it is what lets the prefix already read be flushed and counted.
        // `truncated` still says only whether the terminator was seen.
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
            // Reported through the result, not a log.
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

    /** Sniffs the magic rather than the filename, so an uncompressed archive still restores. */
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
     * The decode sits inside `runCatching`; the flush deliberately does NOT, or the catch would
     * swallow the `CancellationException` of a restore the user backed out of.
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
            // Immediate, not batched: a tombstone must be on record before the event it deletes is
            // merged. File order is not guaranteed, so [flushStreamed] re-applies them afterwards.
            Archive.T_TOMBSTONE -> {
                val r = runCatching { Archive.readTombstone(o) }.getOrNull() ?: return s.skip()
                s.tombstones.add(r)
                // Forward only. Walking a tombstone's `updatedAt` back below its event's lets the
                // next catch-up re-hydrate what the patient deleted.
                val ix = deletions(s)
                if ((ix[r.clientId] ?: Long.MIN_VALUE) < r.updatedAt) {
                    tx { db.eventTombstoneDao().upsert(r) }
                    ix[r.clientId] = r.updatedAt
                }
                s.applied = s.applied.copy(tombstones = s.applied.tombstones + 1)
            }
            // Merge-only: a local row at that slot is this install's own.
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
            // Buffered whole: saved meals and basal schedules merge only once the full set is known.
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
            // Held raw: the `active` flag is not the file's to decide. Decoded in `applyBounded`.
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
            // Placeholder; resolved in `applyBounded` once the parent meal has a local id.
            mealId = 0L,
            // Dropped, not carried: a per-device id would name an unrelated food here. The
            // nutrition fields beside it were snapshotted at save time.
            foodId = null,
            name = o.str("nm") ?: throw IllegalArgumentException("savedItem record has no nm"),
            grams = o.dbl("g") ?: throw IllegalArgumentException("savedItem record has no g"),
            carbsPer100g = o.dbl("c100") ?: throw IllegalArgumentException("savedItem record has no c100"),
            gi = o.dbl("gi"),
            customCurve = o.blob("cc"),
        )
    }

    private suspend fun flushStreamed(s: MergeState) {
        flushReadings(s)
        flushSamples(s)
        flushDoses(s)
        flushMeals(s)
        // File order is not guaranteed, so re-apply the file's own deletions now both are in.
        applyTombstones(s)
        flushStrokes(s)
        flushExerciseFixes(s)
    }

    /**
     * `clientId` → stamp. A restore is the one event path that bypasses
     * [com.t1dm.data.T1dmRepository.hydrateMealEvent]'s deletion filter, and catch-up cannot repair
     * a resurrected row: the high-water mark is a MAX and never moves back over it.
     */
    private suspend fun deletions(s: MergeState): HashMap<String, Long> =
        s.deletions ?: HashMap<String, Long>().also { m ->
            for (t in db.eventTombstoneDao().all()) m[t.clientId] = t.updatedAt
            s.deletions = m
        }

    private suspend fun deleted(s: MergeState, clientId: String, updatedAt: Long): Boolean =
        (deletions(s)[clientId] ?: Long.MIN_VALUE) >= updatedAt

    /**
     * The guard is on the LIVE ROW, not the tombstone table — the read branch has already stored
     * this deletion, so comparing the two compares a value against itself.
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

    /** No database constraint to merge on, so the authoring instant is the key. */
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

    /** The insert's rowids are kept: the only way an archived fix finds its local bout. */
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
     * A fix is applied only when its bout was inserted by THIS restore. A missing parent means the
     * phone already holds the bout, and with it its own track — a duplicate, not a fault.
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

    private suspend fun applyBounded(s: MergeState) = tx {
        // Whole-schedule merge: the rows carry no per-row identity, so a per-injection merge could
        // interleave two schedules. Always inactive — a file may not switch the live clinical choice.
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
            // Archive index → the minted rowid. A skipped meal's items find no entry and drop too.
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
            // A portion dropped with its already-held meal is a duplicate, not a loss.
            s.duplicates += (s.savedMeals.size - mealsAdded) + (s.savedItems.size - items.size)
        }

        // Exactly-one invariant: a restored row never takes the authoritative flag while a local row
        // holds it. On an empty table one restored row may claim it. A source's `active` flag has no
        // such invariant and rides in as stored.
        if (s.sources.isNotEmpty()) {
            val free = db.cgmSourceDao().authoritativeCount() == 0
            val rows = renumber(
                s.sources.mapNotNull { o ->
                    runCatching { Archive.readSource(o, authoritative = false) }.getOrNull()
                },
            )
            val added = db.cgmSourceDao().insertIgnoreAll(rows).count { it != -1L }
            if (free) {
                // The archive's own flag first, else the most recently heard. Never the first row:
                // the export walks oldest-first, so that is the sensor longest retired.
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
                // As for sources; no "last seen" for a server, so the fallback is most-recently-updated.
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
            // Restored DETACHED: a backup must not re-attach an adapter to a live forecast.
            // Deduplicated on (model, weights) here because the key is an autogenerated rowid, so
            // `INSERT OR IGNORE` can never collide.
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

    /** Runs inside [applyBounded]'s write transaction, so no ordinal can be minted between the read
     *  and the insert that follows. */
    private suspend fun renumber(rows: List<CgmSourceEntity>): List<CgmSourceEntity> =
        if (rows.isEmpty()) rows else renumbered(db.cgmSourceDao().all(), rows)

    /**
     * Local rows per natural key, spent down as archived rows match. Counted, not a seen-set:
     * `saved_meal.name`, `food.(name, brand)` and `insulin_type.name` have no unique index, so two
     * rows may share a key. N archived against M local insert exactly `max(0, N - M)`.
     */
    private class Multiset<K>(present: Collection<K>) {
        private val counts = HashMap<K, Int>()

        init {
            for (k in present) counts[k] = (counts[k] ?: 0) + 1
        }

        /** True when no local row is left to account for [key]. */
        fun claim(key: K): Boolean {
            val remaining = counts[key] ?: 0
            if (remaining == 0) return true
            counts[key] = remaining - 1
            return false
        }
    }

    private class MergeState {
        val readings = ArrayList<CgmReadingEntity>(Archive.BATCH)
        val samples = ArrayList<SampleEntity>(Archive.BATCH)
        val doses = ArrayList<LoggedDoseEntity>(Archive.BATCH)
        val meals = ArrayList<LoggedMealEntity>(Archive.BATCH)

        /** Re-applied after the events land; the file's record order is not guaranteed. */
        val tombstones = ArrayList<EventTombstoneEntity>()
        val strokes = ArrayList<PaintStrokeEntity>(Archive.BATCH)
        val exerciseSessions = ArrayList<ExerciseSessionEntity>(Archive.BATCH)
        val exerciseFixes = ArrayList<Pair<String, ExerciseFixEntity>>(Archive.BATCH)

        /** Archived bout `clientId` → the minted rowid. Only bouts this restore actually inserted. */
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

        var strokeKeys: HashSet<Long>? = null

        /** `clientId` → the newest deletion stamp; loaded on first use, then kept current. */
        var deletions: HashMap<String, Long>? = null

        var applied = ArchiveCounts()
        var duplicates = 0
        var skipped = 0

        fun skip() { skipped++ }
    }

    internal companion object {
        const val BUF = 1 shl 16

        /**
         * `ordinal` is the one column a merge insert cannot take verbatim: it names the sensor's place
         * on ONE phone. The file's number is kept where free, [stored] rows are never renumbered, and a
         * `sourceId` already held passes through claiming nothing, or a re-import burns one every time.
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
                // A file written before the column carried lands here as the sentinel; numbering it
                // now keeps an unnamable sensor off the screen.
                while (!taken.add(next)) next++
                row.copy(ordinal = next)
            }
        }
    }
}
