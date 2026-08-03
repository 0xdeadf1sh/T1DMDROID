package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.backup.Archive
import com.t1dm.data.backup.ArchiveReader
import com.t1dm.data.backup.ArchiveWriter
import com.t1dm.data.backup.NotAnArchiveException
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.BasalScheduleEntity
import com.t1dm.data.db.CgmReadingEntity
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.ConformalDeltaEntity
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.PaintStrokeBlob
import com.t1dm.data.db.PaintStrokeEntity
import com.t1dm.data.db.SavedMealEntity
import com.t1dm.data.db.SavedMealItemEntity
import com.t1dm.data.db.ServerProfileEntity
import com.t1dm.data.db.toBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * The archive end to end, against real SQLite: write a populated store out, read it into an empty
 * one, and hold the two against each other.
 *
 * The properties under test are the ones a backup is worthless without — that everything comes back,
 * that restoring twice is not restoring twice, that a local row is never displaced by an archived
 * one, and that a file cut short says so instead of quietly restoring less than it claims.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveRoundTripTest {

    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    @Before
    fun setUp() {
        source = newDb()
        target = newDb()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun newDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private suspend fun archiveOf(db: AppDatabase, config: String? = CONFIG): ByteArray {
        val out = ByteArrayOutputStream()
        ArchiveWriter(db).write(out, config, "0.22.0", NOW)
        return out.toByteArray()
    }

    private suspend fun restoreInto(db: AppDatabase, bytes: ByteArray) =
        ArchiveReader(db).read(ByteArrayInputStream(bytes))

    // ── the whole record, out and back ────────────────────────────────────────────────────────

    @Test
    fun everythingComesBack() = runTest {
        populate(source)
        val result = restoreInto(target, archiveOf(source))

        assertFalse("a complete archive must not read as truncated", result.truncated)
        assertEquals(0, result.skipped)
        assertEquals(AppDatabase.SCHEMA_VERSION, result.schemaVersion)
        assertEquals(NOW, result.createdAtMs)
        assertNotNull("the settings document did not survive the header", result.configJson)

        assertEquals(READINGS, target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 10_000).size)
        assertEquals(2, target.loggedDoseDao().pageFrom(Long.MIN_VALUE, Long.MIN_VALUE, 100).size)
        assertEquals(2, target.loggedMealDao().pageFrom(Long.MIN_VALUE, Long.MIN_VALUE, 100).size)
        assertEquals(2, target.basalScheduleDao().all().size)
        assertEquals(1, target.foodDao().allCustom().size)
        assertEquals(1, target.insulinTypeDao().allCustom().size)
        assertEquals(1, target.paintStrokeDao().pageFrom(Long.MIN_VALUE, 100).size)
        assertEquals(1, target.cgmSourceDao().all().size)
        assertEquals(1, target.serverProfileDao().all().size)
        assertEquals(1, target.savedMealDao().allMeals().size)
        assertEquals(2, target.savedMealDao().allItems().size)
        assertEquals(1, target.conformalDeltaDao().all().size)
    }

    @Test
    fun theFittedBandCorrectionSurvivesWithItsShapeIntact() = runTest {
        // The one archived table whose payload is only meaningful alongside its own declared shape:
        // a delta whose blob length disagrees with `steps · nQuantiles` is refused on read, so a
        // silent reshape here would cost the model its correction on every later forecast.
        populate(source)
        restoreInto(target, archiveOf(source))
        val original = source.conformalDeltaDao().all().single()
        val restored = target.conformalDeltaDao().all().single()
        assertEquals(original.modelId, restored.modelId)
        assertEquals(original.steps, restored.steps)
        assertEquals(original.nQuantiles, restored.nQuantiles)
        assertArrayEquals(original.deltaBlob, restored.deltaBlob)
        assertEquals(original.fittedAtMs, restored.fittedAtMs)
    }

    @Test
    fun aReadingSurvivesFieldForField() = runTest {
        populate(source)
        restoreInto(target, archiveOf(source))
        val original = source.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 1).single()
        val restored = target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 1).single()
        assertEquals(original, restored)
    }

    @Test
    fun aSavedMealKeepsItsPortions() = runTest {
        populate(source)
        restoreInto(target, archiveOf(source))
        val meal = target.savedMealDao().allMeals().single()
        val items = target.savedMealDao().itemsOf(meal.id)
        assertEquals("the portions did not follow their meal's new local id", 2, items.size)
        assertEquals(setOf("Oats", "Milk"), items.mapTo(HashSet()) { it.name })
        // The archived foodId is a per-device id and is deliberately dropped — carried over it would
        // point at whatever unrelated food happens to hold that id here.
        assertTrue("a stale food link was carried across", items.all { it.foodId == null })
    }

    // ── merging: the local row always wins ────────────────────────────────────────────────────

    @Test
    fun restoringTheSameFileTwiceChangesNothing() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        val first = restoreInto(target, bytes)
        val second = restoreInto(target, bytes)

        assertTrue("nothing was applied on the first pass", first.applied.total > 0)
        assertEquals("a second restore must be a no-op", 0, second.applied.total)
        assertEquals(first.applied.total, second.duplicates)
        assertEquals(READINGS, target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 10_000).size)
        assertEquals(1, target.savedMealDao().allMeals().size)
        assertEquals(2, target.savedMealDao().allItems().size)
        assertEquals(1, target.paintStrokeDao().pageFrom(Long.MIN_VALUE, 100).size)
    }

    @Test
    fun aLocalReadingIsNeverOverwrittenByAnArchivedOne() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        // The same grid slot, a different value: the phone's own reading is the authority (§3.1) and
        // an archive may only ever fill a gap.
        target.cgmReadingDao().upsert(reading(0).copy(bgMgdl = 999))
        restoreInto(target, bytes)
        val kept = target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 1).single()
        assertEquals(999, kept.bgMgdl)
    }

    @Test
    fun anArchivedEventDoesNotDuplicateOneTheServerAlreadyRehydrated() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        // Same clientId, arrived by a different road — exactly what a catch-up does after a reinstall.
        target.loggedDoseDao().insert(dose(0))
        restoreInto(target, bytes)
        val doses = target.loggedDoseDao().pageFrom(Long.MIN_VALUE, Long.MIN_VALUE, 100)
        assertEquals(2, doses.size)
        assertEquals(2, doses.mapTo(HashSet()) { it.clientId }.size)
    }

    @Test
    fun anArchivedSourceCannotStealTheActiveFlag() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        target.cgmSourceDao().upsert(cgmSource().copy(sourceId = "local-sensor", active = true))
        restoreInto(target, bytes)
        assertEquals("the exactly-one-active invariant broke", 1, target.cgmSourceDao().activeCount())
        assertEquals("local-sensor", target.cgmSourceDao().activeSourceId())
    }

    @Test
    fun aRestoredSourceMayTakeTheActiveFlagOnAFreshInstall() = runTest {
        populate(source)
        restoreInto(target, archiveOf(source))
        // The fresh-install case: nothing holds the flag, so the restored source becomes usable at
        // once rather than leaving the phone with a sensor it knows about but is not reading.
        assertEquals(1, target.cgmSourceDao().activeCount())
        assertEquals(SOURCE_ID, target.cgmSourceDao().activeSourceId())
    }

    @Test
    fun aFreshInstallActivatesTheSensorThatWasWorn_notTheOldestEverSeen() = runTest {
        // `cgm_source` accumulates every sensor the phone has ever heard, and the export walks it
        // `ORDER BY addedAtMs` — oldest first. Activating "the first row that landed" therefore
        // picked the sensor longest retired, and on a fresh install pointed the whole reading path
        // at a serial that will never advertise again: no BG, no stats, nothing to diagnose.
        source.cgmSourceDao().upsert(
            CgmSourceEntity(
                sourceId = "aidex-OLD", vendorId = "aidex", displayName = "last year's sensor",
                serialSuffix = "0001", active = false, warmupWindowMin = 60,
                addedAtMs = 1_600_000_000_000L, lastSeenMs = 1_600_100_000_000L,
            ),
        )
        populate(source) // adds SOURCE_ID with a much later addedAtMs, and active = true

        restoreInto(target, archiveOf(source))

        assertEquals("the exactly-one-active invariant broke", 1, target.cgmSourceDao().activeCount())
        assertEquals(
            "the restore activated a retired sensor",
            SOURCE_ID,
            target.cgmSourceDao().activeSourceId(),
        )
    }

    @Test
    fun aRestoredProfileTakesTheOneThatWasActive() = runTest {
        source.serverProfileDao().upsert(
            ServerProfileEntity(
                id = "profile-0", label = "old", baseUrl = "http://10.0.0.9:8080",
                active = false, createdAtMs = 0L, updatedAtMs = 0L,
            ),
        )
        populate(source) // adds profile-1, active = true
        restoreInto(target, archiveOf(source))
        assertEquals(1, target.serverProfileDao().activeCount())
        assertEquals("profile-1", target.serverProfileDao().active()!!.id)
    }

    @Test
    fun aRestoredBasalScheduleLandsInactive() = runTest {
        populate(source)
        restoreInto(target, archiveOf(source))
        // The active schedule is a live clinical choice on THIS phone; a file must not switch it.
        assertTrue(target.basalScheduleDao().all().none { it.active })
        assertTrue(target.basalScheduleDao().activeDoses().isEmpty())
    }

    @Test
    fun aWholeBasalScheduleIsSkippedWhenItsIdIsAlreadyHeld() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        target.basalScheduleDao().insertAll(listOf(basalRow(SCHEDULE_A, 0)))
        restoreInto(target, bytes)
        // One row locally, and the archive's two rows for that schedule are skipped WHOLE rather
        // than interleaved into a day the user never configured.
        assertEquals(1, target.basalScheduleDao().all().count { it.scheduleId == SCHEDULE_A })
        assertEquals(1, target.basalScheduleDao().all().count { it.scheduleId == SCHEDULE_B })
    }

    @Test
    fun twoSavedMealsSharingANameBothSurviveARestore() = runTest {
        // `saved_meal.name` carries no unique index and nothing enforces one, so two meals called
        // "Breakfast" are legitimate. Deduplicating on a set that grew as each archived meal was
        // accepted made the second look like a duplicate of the first: it was dropped, its portions
        // were dropped with it, and both were reported as rows the phone already held.
        val a = source.savedMealDao().insertMeal(SavedMealEntity(name = "Breakfast", updatedAt = 1L))
        val b = source.savedMealDao().insertMeal(SavedMealEntity(name = "Breakfast", updatedAt = 2L))
        source.savedMealDao().insertItems(
            listOf(
                SavedMealItemEntity(mealId = a, foodId = null, name = "Oats", grams = 60.0, carbsPer100g = 60.0, gi = 55.0, customCurve = null),
                SavedMealItemEntity(mealId = b, foodId = null, name = "Toast", grams = 80.0, carbsPer100g = 49.0, gi = 70.0, customCurve = null),
            ),
        )
        val bytes = archiveOf(source)

        val first = restoreInto(target, bytes)
        assertEquals("a same-named meal was collapsed away", 2, target.savedMealDao().allMeals().size)
        assertEquals(2, target.savedMealDao().allItems().size)
        assertEquals(2, first.applied.savedMeals)
        assertEquals(0, first.duplicates)

        // And still idempotent: the counting merge must not turn "two locally" into "add two more".
        val second = restoreInto(target, bytes)
        assertEquals(0, second.applied.total)
        assertEquals(2, target.savedMealDao().allMeals().size)
        assertEquals(2, target.savedMealDao().allItems().size)
    }

    @Test
    fun twoCustomFoodsSharingAKeyBothSurviveARestore() = runTest {
        source.foodDao().insert(customFood().copy(carbsPer100g = 48.0))
        source.foodDao().insert(customFood().copy(carbsPer100g = 51.0))
        val bytes = archiveOf(source)

        restoreInto(target, bytes)
        assertEquals(2, target.foodDao().allCustom().size)
        restoreInto(target, bytes)
        assertEquals("the second restore was not idempotent", 2, target.foodDao().allCustom().size)
    }

    @Test
    fun aCustomFoodIsMatchedByNameAndBrand() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        target.foodDao().insert(customFood())
        restoreInto(target, bytes)
        assertEquals("the same food was added twice", 1, target.foodDao().allCustom().size)
    }

    @Test
    fun aDrawingIsMatchedByItsAuthoringInstant() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        target.paintStrokeDao().insert(strokeRow())
        restoreInto(target, bytes)
        assertEquals(1, target.paintStrokeDao().pageFrom(Long.MIN_VALUE, 100).size)
    }

    // ── damaged and foreign files ─────────────────────────────────────────────────────────────

    @Test
    fun aTruncatedArchiveSaysSoAndStillRestoresWhatItHas() = runTest {
        populate(source)
        val whole = gunzip(archiveOf(source))
        // Cut mid-file, as a full disk or a killed process would. Everything up to the cut is still
        // good data and is worth having; what must not happen is the restore reporting success.
        val lines = whole.decodeToString().lines()
        val cut = lines.take(lines.size / 2).joinToString("\n").toByteArray()

        val result = restoreInto(target, cut)
        assertTrue("a file with no end record must be reported truncated", result.truncated)
        assertTrue("the surviving prefix was thrown away", result.applied.total > 0)
    }

    @Test
    fun aTruncatedGZIPPEDArchiveIsRecoveredRatherThanThrown() = runTest {
        // The test above cuts DECOMPRESSED bytes, which takes the reader's plain pass-through branch
        // and never touches the inflater — so it passed while the real case threw. Every archive the
        // writer emits is gzip, and a cut deflate stream raises EOFException from inside
        // `readLine`, which used to escape `read()` entirely: nothing was flushed, nothing counted,
        // and the caller reported total failure for a file most of which was good.
        populate(source)
        val whole = archiveOf(source)
        val cut = whole.copyOf(whole.size / 2)

        val result = restoreInto(target, cut)
        assertTrue("a cut gzip archive must be reported truncated", result.truncated)
        assertTrue("the surviving prefix was thrown away", result.applied.total > 0)
        assertTrue("nothing reached the store", target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 10_000).isNotEmpty())
    }

    @Test
    fun losingOnlyTheGzipTrailerCostsNothingAtAll() = runTest {
        // The nastiest shape: every record including the terminator is present and readable, and the
        // stream still throws on the 8-byte trailer. Before the fix this lost the ENTIRE restore —
        // every bounded table with it — for one missing byte.
        populate(source)
        val whole = archiveOf(source)
        val result = restoreInto(target, whole.copyOf(whole.size - 1))

        assertFalse("the terminator was read, so this file is not truncated", result.truncated)
        assertEquals(READINGS, target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 10_000).size)
        // The bounded tables are applied last of all and were the first thing lost.
        assertEquals(1, target.savedMealDao().allMeals().size)
        assertEquals(1, target.cgmSourceDao().all().size)
        assertEquals(1, target.conformalDeltaDao().all().size)
    }

    @Test
    fun oneCorruptLineCostsOnlyItself() = runTest {
        populate(source)
        val whole = gunzip(archiveOf(source)).decodeToString().lines().toMutableList()
        val before = whole.count { it.contains("\"t\":\"${Archive.T_READING}\"") }
        val victim = whole.indexOfFirst { it.contains("\"t\":\"${Archive.T_READING}\"") }
        whole[victim] = """{"t":"reading","s":"s","ts":"not a number}"""

        val result = restoreInto(target, whole.joinToString("\n").toByteArray())
        assertEquals("more than the damaged record was lost", 1, result.skipped)
        assertEquals(before - 1, result.applied.readings)
        assertFalse("a corrupt line must not look like truncation", result.truncated)
    }

    @Test
    fun aForeignFileIsRefusedRatherThanPartiallyRead() = runTest {
        val thrown = runCatching {
            restoreInto(target, """{"format":"something.else","version":1}""".toByteArray())
        }.exceptionOrNull()
        assertTrue("a foreign file must be distinguishable", thrown is NotAnArchiveException)
        assertTrue(runCatching { restoreInto(target, ByteArray(0)) }.exceptionOrNull() is NotAnArchiveException)
    }

    @Test
    fun anUncompressedArchiveStillRestores() = runTest {
        populate(source)
        // The reader sniffs the gzip magic rather than trusting a filename, so a hand-decompressed
        // archive — or one a provider mangled the extension of — is still readable.
        val result = restoreInto(target, gunzip(archiveOf(source)))
        assertFalse(result.truncated)
        assertEquals(READINGS, target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 10_000).size)
    }

    @Test
    fun anArchiveWithNoSettingsDocumentStillCarriesItsRows() = runTest {
        populate(source)
        val result = restoreInto(target, archiveOf(source, config = null))
        assertNull(result.configJson)
        assertTrue(result.applied.total > 0)
    }

    @Test
    fun aPrettyPrintedSettingsDocumentDoesNotSplitTheHeader() = runTest {
        // `SettingsStore.exportJson` pretty-prints — it was written for a document a human might
        // open — and embedding that verbatim put newlines INSIDE the header, which split one record
        // across several lines and left the archive unreadable to its own reader. The fixture used
        // by the other tests is single-line, so only the real settings document exposed it.
        populate(source)
        val bytes = archiveOf(source, config = PRETTY_CONFIG)

        // The header must be complete on line one — that is the whole invariant a line-delimited
        // format rests on, and it is what a multi-line settings document broke.
        val firstLine = gunzip(bytes).decodeToString().substringBefore('\n')
        val header = Archive.json.parseToJsonElement(firstLine).jsonObject
        assertEquals(Archive.FORMAT, (header["format"] as JsonPrimitive).content)
        assertNotNull("the settings document did not fit on the header line", header["config"])

        val result = restoreInto(target, bytes)
        assertNotNull("the settings document did not survive a pretty-printed input", result.configJson)
        assertTrue("the settings did not come back", result.configJson!!.contains("alarm.low_mgdl"))
        assertFalse(result.truncated)
        assertTrue(result.applied.total > 0)
    }

    @Test
    fun anUnparseableSettingsDocumentCostsTheSettingsAndNotTheRows() = runTest {
        populate(source)
        val result = restoreInto(target, archiveOf(source, config = "{ this is not json"))
        assertNull(result.configJson)
        // The rows are what an archive is for; a bad header must not take them down with it.
        assertFalse(result.truncated)
        assertEquals(READINGS, target.cgmReadingDao().pageFrom(SOURCE_ID, Long.MIN_VALUE, 10_000).size)
    }

    @Test
    fun anEmptyStoreProducesAReadableArchive() = runTest {
        val result = restoreInto(target, archiveOf(source))
        assertFalse(result.truncated)
        assertEquals(0, result.applied.total)
        assertEquals(0, result.skipped)
    }

    // ── the file itself ───────────────────────────────────────────────────────────────────────

    @Test
    fun theArchiveIsGzippedAndPagesBeyondOneBatch() = runTest {
        populate(source)
        val bytes = archiveOf(source)
        assertEquals(Archive.GZIP_MAGIC_0, bytes[0].toInt() and 0xff)
        assertEquals(Archive.GZIP_MAGIC_1, bytes[1].toInt() and 0xff)
        // More readings than one page, so the keyset walk is genuinely exercised rather than
        // completing in a single query and hiding a cursor bug.
        assertTrue("the fixture no longer spans multiple pages", READINGS > Archive.BATCH)
        assertTrue("compression bought nothing", bytes.size < gunzip(bytes).size / 2)
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private suspend fun populate(db: AppDatabase) {
        db.cgmSourceDao().upsert(cgmSource())
        for (i in 0 until READINGS) db.cgmReadingDao().upsert(reading(i))
        db.loggedDoseDao().insert(dose(0))
        db.loggedDoseDao().insert(dose(1))
        db.loggedMealDao().insert(mealRow(0))
        db.loggedMealDao().insert(mealRow(1))
        db.basalScheduleDao().insertAll(listOf(basalRow(SCHEDULE_A, 0), basalRow(SCHEDULE_B, 1)))
        db.foodDao().insert(customFood())
        db.insulinTypeDao().insert(
            com.t1dm.data.db.InsulinTypeEntity(
                name = "Fiasp", kind = DoseKind.BOLUS, durationMin = 300.0, k = 2.0, theta = 22.0,
                kaPerHour = null, kePerHour = null, customCurve = null, builtin = false, updatedAt = 4L,
            ),
        )
        db.paintStrokeDao().insert(strokeRow())
        db.conformalDeltaDao().upsert(conformalRow())
        db.serverProfileDao().upsert(
            ServerProfileEntity(
                id = "profile-1", label = "home", baseUrl = "http://10.0.0.2:8080",
                active = true, createdAtMs = 1L, updatedAtMs = 2L,
            ),
        )
        val mealId = db.savedMealDao().insertMeal(SavedMealEntity(name = "Porridge", updatedAt = 5L))
        db.savedMealDao().insertItems(
            listOf(
                SavedMealItemEntity(
                    mealId = mealId, foodId = 77L, name = "Oats", grams = 60.0,
                    carbsPer100g = 60.0, gi = 55.0, customCurve = null,
                ),
                SavedMealItemEntity(
                    mealId = mealId, foodId = null, name = "Milk", grams = 200.0,
                    carbsPer100g = 4.8, gi = 30.0, customCurve = null,
                ),
            ),
        )
    }

    private fun cgmSource() = CgmSourceEntity(
        sourceId = SOURCE_ID, vendorId = "aidex", displayName = "AiDEX X",
        serialSuffix = "4321", active = true, warmupWindowMin = 60,
        addedAtMs = 1_700_000_000_000L, lastSeenMs = 1_700_000_600_000L,
    )

    private fun reading(i: Int) = CgmReadingEntity(
        sourceId = SOURCE_ID,
        tsMs = T0 + i * 300_000L,
        bgMgdl = 90 + (i % 60),
        trendTenthsPerMin = if (i % 3 == 0) null else -5 + (i % 11),
        minFromStart = i * 5,
        quality = if (i % 4 == 0) null else 3,
        provenance = if (i % 7 == 0) ReadingProvenance.INTERPOLATED else ReadingProvenance.MEASURED,
        flag = if (i % 13 == 0) ReadingFlag.WARMUP else ReadingFlag.NORMAL,
        tzOffsetMin = 180,
        rxWallMs = T0 + i * 300_000L + 1234L,
        rssi = if (i % 5 == 0) null else -70 - (i % 20),
    )

    private fun dose(i: Int) = LoggedDoseEntity(
        clientId = "dose-$i", tsMs = T0 + i * 3_600_000L, kind = DoseKind.BOLUS,
        units = 3.5 + i, durationMin = 300.0, k = 2.0, theta = 25.0,
        kaPerHour = null, kePerHour = null, customCurve = null,
        tzOffsetMin = 180, note = if (i == 0) "with \"lunch\"\nand a newline" else null, updatedAt = 1L,
    )

    private fun mealRow(i: Int) = LoggedMealEntity(
        clientId = "meal-$i", tsMs = T0 + i * 3_600_000L, grams = 45.0 + i, gi = 62.0,
        k = 1.8, theta = 18.0, durationMin = 240.0, customCurve = null,
        tzOffsetMin = 180, note = null, updatedAt = 2L,
    )

    private fun basalRow(scheduleId: String, i: Int) = BasalScheduleEntity(
        scheduleId = scheduleId, label = "overnight", timeOfDayMin = 60 * (2 + i),
        doseU = 0.8, durationMin = 1440.0, kaPerHour = 0.4, kePerHour = 0.2,
        tzOffsetMin = 180, active = true, updatedAt = 3L,
    )

    private fun customFood() = FoodEntity(
        name = "Sourdough", brand = "local bakery", carbsPer100g = 48.0, gi = 54.0,
        category = "bread", source = "user", custom = true, customCurve = null, updatedAt = 6L,
    )

    /** A fitted §8.4 band correction. Its blob must satisfy `steps · nQuantiles · 8` bytes or the
     *  decoder refuses it, so the shape is built from the same two numbers it declares. */
    private fun conformalRow(steps: Int = 12, nQuantiles: Int = 7) = ConformalDeltaEntity(
        modelId = "t1dmai-best",
        steps = steps,
        nQuantiles = nQuantiles,
        deltaBlob = DoubleArray(steps * nQuantiles) { it * 0.25 }.toBlob(),
        nCal = 400, nEval = 120, maxAbsDeltaMgdl = 18.0,
        cov90Raw = 0.81, cov90Cal = 0.90, meanWidth90Raw = 60.0, meanWidth90Cal = 72.0,
        windowDays = 14, fittedAtMs = 7L,
    )

    private fun strokeRow() = PaintStrokeEntity(
        createdAtMs = 1_700_000_500_000L, tool = "marker", colorArgb = -0x10000, widthDp = 4.2f,
        minTsMs = T0, maxTsMs = T0 + 600_000L,
        points = PaintStrokeBlob.encode(
            longArrayOf(T0 + 600_000L, T0, T0 + 300_000L),
            floatArrayOf(0.2f, 0.8f, 0.5f),
        ),
    )

    private companion object {
        const val SOURCE_ID = "aidex-1234"
        const val SCHEDULE_A = "schedule-a"
        const val SCHEDULE_B = "schedule-b"
        const val T0 = 1_700_000_000_000L - 1_700_000_000_000L % 300_000L
        const val NOW = 1_712_345_400_000L

        /** Past `Archive.BATCH`, so the export's keyset paging and the restore's batched inserts are
         *  both exercised across a page boundary rather than completing in one. */
        const val READINGS = 1_200

        val CONFIG = """{"format":"t1dm.config","version":1,"exportedAtMs":1,"kv":{"alarm.low_mgdl":"75"}}"""

        /** Exactly the shape `SettingsStore.exportJson` produces — `JSONObject.toString(2)`, with a
         *  newline after every field. This is the input that broke the header in the first place. */
        val PRETTY_CONFIG = """
            {
              "format": "t1dm.config",
              "version": 1,
              "exportedAtMs": 1,
              "kv": {
                "alarm.low_mgdl": "75",
                "ui.theme": "tron"
              }
            }
        """.trimIndent()
    }
}
