package com.t1dm.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.MigrationRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the keep-forever hand-written migration chain (Phase 1: destructive migration is
 * FORBIDDEN). Creates the schema at version N, applies the hand-written migration(s), and lets
 * [MigrationTestHelper] assert the migrated DB matches the exported N+1 schema exactly — catching any
 * DDL drift (index names, AUTOINCREMENT, nullability) between the migration and Room.
 *
 * Uses the **driver-based** [MigrationTestHelper] constructor with [BundledSQLiteDriver]: production
 * ships the bundled SQLite (its FTS5 — the OEM/HyperOS system SQLite omits `fts5`, see [AppDatabase]),
 * so migrations now run as connection-based `SQLiteConnection.execSQL` and the test must open the same
 * driver. The connection-based `runMigrationsAndValidate(version, migrations)` is lenient about
 * unknown tables, which is exactly right for v5 — the FTS5 `food_fts` shadow tables are Room-invisible
 * virtual tables that a strict stray-table check would false-positive on.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dbFile = instrumentation.targetContext.getDatabasePath(TEST_DB)

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        dbFile,
        BundledSQLiteDriver(),
        AppDatabase::class,
    )

    @Before
    fun cleanFile() {
        // Each @Test recreates the DB at v1/vN from scratch; drop any file the previous method left.
        dbFile.delete()
        instrumentation.targetContext.getDatabasePath("$TEST_DB-wal").delete()
        instrumentation.targetContext.getDatabasePath("$TEST_DB-shm").delete()
    }

    @Test
    fun migrate1To2_additiveTablesMatchSchema() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(2, listOf(MigrationRunner.MIGRATION_1_2))
    }

    @Test
    fun migrate2To3_curveEngineTablesMatchSchema() {
        helper.createDatabase(2).close()
        helper.runMigrationsAndValidate(3, listOf(MigrationRunner.MIGRATION_2_3))
    }

    @Test
    fun migrate3To4_noteTableMatchesSchema() {
        helper.createDatabase(3).close()
        helper.runMigrationsAndValidate(4, listOf(MigrationRunner.MIGRATION_3_4))
    }

    @Test
    fun migrate1To4_fullChain() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            4,
            listOf(
                MigrationRunner.MIGRATION_1_2,
                MigrationRunner.MIGRATION_2_3,
                MigrationRunner.MIGRATION_3_4,
            ),
        )
    }

    @Test
    fun migrate4To5_mealBuilderTablesMatchSchema() {
        helper.createDatabase(4).close()
        // The FTS5 `food_fts` + its shadow tables (created by MIGRATION_4_5) are Room-invisible; the
        // connection-based validator ignores unknown tables, so every ENTITY table is still checked
        // against 5.json while the virtual tables are left alone.
        helper.runMigrationsAndValidate(5, listOf(MigrationRunner.MIGRATION_4_5))
    }

    @Test
    fun migrate1To5_fullChain() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            5,
            listOf(
                MigrationRunner.MIGRATION_1_2,
                MigrationRunner.MIGRATION_2_3,
                MigrationRunner.MIGRATION_3_4,
                MigrationRunner.MIGRATION_4_5,
            ),
        )
    }

    @Test
    fun migrate5To6_reseedIsSchemaNeutral() {
        // MIGRATION_5_6 is a data-only re-seed (inserts the grown FoodSeed rows into `food`); the
        // schema must be byte-identical to 5, so validating against 6.json still passes.
        helper.createDatabase(5).close()
        helper.runMigrationsAndValidate(6, listOf(MigrationRunner.MIGRATION_5_6))
    }

    @Test
    fun migrate1To6_fullChain() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            6,
            listOf(
                MigrationRunner.MIGRATION_1_2,
                MigrationRunner.MIGRATION_2_3,
                MigrationRunner.MIGRATION_3_4,
                MigrationRunner.MIGRATION_4_5,
                MigrationRunner.MIGRATION_5_6,
            ),
        )
    }

    @Test
    fun migrate6To7_clientIdColumnsAndUniqueIndexMatchSchema() {
        // v7 (app-authoritative redesign, §3.2/H1): additive `clientId` on logged_meal/logged_dose,
        // each back-filled with a fresh UUID before the UNIQUE index is built. The retired
        // sample.carbsG/bolusU/basalU columns are left dead in place, so the schema stays ALTER-only.
        helper.createDatabase(6).close()
        helper.runMigrationsAndValidate(7, listOf(MigrationRunner.MIGRATION_6_7))
    }

    @Test
    fun migrate1To7_fullChain() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            7,
            listOf(
                MigrationRunner.MIGRATION_1_2,
                MigrationRunner.MIGRATION_2_3,
                MigrationRunner.MIGRATION_3_4,
                MigrationRunner.MIGRATION_4_5,
                MigrationRunner.MIGRATION_5_6,
                MigrationRunner.MIGRATION_6_7,
            ),
        )
    }

    @Test
    fun migrate7To8_paintStrokeTableMatchesSchema() {
        // v8 (graph annotation layer): one additive table + its two time-bound indices, nothing else
        // touched — the drawings the user paints over the BG panel.
        helper.createDatabase(7).close()
        helper.runMigrationsAndValidate(8, listOf(MigrationRunner.MIGRATION_7_8))
    }

    @Test
    fun migrate8To9_noteTableIsGoneAndQueuedNoteRowsArePurged() {
        // v9 (the free-text note surface is withdrawn): the sole subtractive step. Seed the v8 DB with
        // a note row and a queued NOTE outbox row, then assert both are gone — the outbox purge is the
        // load-bearing half, since `OutboxKind.valueOf("NOTE")` would throw on every later drain.
        helper.createDatabase(8).use { db ->
            db.execSQL("INSERT INTO `note` (`tsMs`,`tzOffsetMin`,`text`,`updatedAt`) VALUES (1,0,'x',1)")
            db.execSQL(
                "INSERT INTO `outbox` (`kind`,`dedupKey`,`payload`,`createdAtMs`,`attempts`,`nextAttemptMs`,`state`) " +
                    "VALUES ('NOTE','note:1:2',X'00',1,0,0,'PENDING')",
            )
            db.execSQL(
                "INSERT INTO `outbox` (`kind`,`dedupKey`,`payload`,`createdAtMs`,`attempts`,`nextAttemptMs`,`state`) " +
                    "VALUES ('ALERT','alert:1:low',X'00',1,0,0,'PENDING')",
            )
        }

        val db = helper.runMigrationsAndValidate(9, listOf(MigrationRunner.MIGRATION_8_9))

        assertEquals(0, countTables(db, "note"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `outbox` WHERE `kind` = 'NOTE'"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `outbox`"))
        db.close()
    }

    @Test
    fun migrate9To10_conformalDeltaTableMatchesSchema() {
        // v10 (on-device band recalibration): one additive table, no index, nothing else touched.
        helper.createDatabase(9).close()
        helper.runMigrationsAndValidate(10, listOf(MigrationRunner.MIGRATION_9_10))
    }

    @Test
    fun migrate10To11_everyStoredSourceIsClassifiedAndNoReadingMoves() {
        // v11 (displayed history spans a sensor MODEL, not one physical sensor): `cgm_source` gains
        // `sensorModelId` + its index. Seed two real sensors and the debug source, each holding a reading,
        // then assert the backfill is exact and additive — this is the step that decides whether a
        // year of history stays reachable on the BG panel after a sensor change.
        helper.createDatabase(10).use { db ->
            fun source(id: String, added: Long, active: Int) = db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`displayName`,`serialSuffix`,`active`,`warmupWindowMin`," +
                    "`addedAtMs`,`lastSeenMs`) VALUES ('$id','aidexx','n','s',$active,60,$added,$added)",
            )
            fun reading(id: String, ts: Long) = db.execSQL(
                "INSERT INTO `cgm_reading` " +
                    "(`sourceId`,`tsMs`,`bgMgdl`,`trendTenthsPerMin`,`minFromStart`,`quality`," +
                    "`provenance`,`flag`,`tzOffsetMin`,`rxWallMs`,`rssi`) " +
                    "VALUES ('$id',$ts,120,NULL,120,NULL,'MEASURED','NORMAL',0,$ts,NULL)",
            )
            source("aidexx:EXPIRED", 1_000, 0)
            source("aidexx:FRESH", 2_000, 1)
            source("aidexx:DEBUG", 3_000, 0)
            reading("aidexx:EXPIRED", 300_000L)
            reading("aidexx:FRESH", 600_000L)
            reading("aidexx:DEBUG", 900_000L)
        }

        val db = helper.runMigrationsAndValidate(11, listOf(MigrationRunner.MIGRATION_10_11))

        // Both real sensors land in one class — the whole point: they now share a trace.
        assertEquals(
            2,
            countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `sensorModelId` = 'aidexx:x'"),
        )
        // The debug source keeps its own, so injected readings do not graft onto the real history.
        assertEquals(
            1,
            countRows(
                db,
                "SELECT COUNT(*) FROM `cgm_source` " +
                    "WHERE `sensorModelId` = 'aidexx:debug' AND `sourceId` = 'aidexx:DEBUG'",
            ),
        )
        // No row may be left unclassified: '' would be a class of its own and would strand a sensor.
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `sensorModelId` = ''"))
        // Additive: the migration classifies sources and touches nothing else.
        assertEquals(3, countRows(db, "SELECT COUNT(*) FROM `cgm_source`"))
        assertEquals(3, countRows(db, "SELECT COUNT(*) FROM `cgm_reading`"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `active` = 1"))
        db.close()
    }

    @Test
    fun migrate11To12_advertNameIsAddedAndLeftNull() {
        // v12 (a source records what it advertised): one nullable column, nothing backfilled. The
        // null is the point — the advertised name was discarded at match time, so for a sensor
        // already on record the app genuinely does not know it, and inventing one here would be
        // indistinguishable from having observed it.
        helper.createDatabase(11).use { db ->
            db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`sensorModelId`,`displayName`,`serialSuffix`,`active`," +
                    "`warmupWindowMin`,`addedAtMs`,`lastSeenMs`) " +
                    "VALUES ('aidexx:OLD','aidexx','aidexx:x','n','s',1,60,1,1)",
            )
        }

        val db = helper.runMigrationsAndValidate(12, listOf(MigrationRunner.MIGRATION_11_12))

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `advertName` IS NULL"))
        assertEquals(
            1,
            countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `sensorModelId` = 'aidexx:x'"),
        )
        db.close()
    }

    @Test
    fun migrate12To13_hiddenIsAddedAndEveryExistingSourceStaysListed() {
        // v13 (a retired sensor can be taken off the lists): one column, defaulted to 0. The default
        // is the point — removal is a decision the user has not made for any sensor already on record,
        // and a row that came back hidden would vanish from the list without being asked for.
        helper.createDatabase(12).use { db ->
            db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`sensorModelId`,`advertName`,`displayName`,`serialSuffix`," +
                    "`active`,`warmupWindowMin`,`addedAtMs`,`lastSeenMs`) " +
                    "VALUES ('aidexx:OLD','aidexx','aidexx:x',NULL,'n','s',1,60,1,1)",
            )
        }

        val db = helper.runMigrationsAndValidate(13, listOf(MigrationRunner.MIGRATION_12_13))

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `hidden` = 0"))
        // Additive: the migration adds a column and touches nothing else, the active flag included.
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `active` = 1"))
        db.close()
    }

    @Test
    fun migrate13To14_authorityIsRenamedAndActivitySeededFromIt() {
        // v14 (several sensors may be read at once): `active` is RENAMED to `authoritative` and a new
        // `active` takes the name, seeded from it. The seeding is the whole of the migration's
        // judgement — the sensor that was believed is also, and still, the one being read — and the
        // direction matters: seeding every known sensor active instead would have the app open a link
        // to every sensor it has ever met on the next start.
        helper.createDatabase(13).use { db ->
            db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`sensorModelId`,`advertName`,`displayName`,`serialSuffix`," +
                    "`active`,`warmupWindowMin`,`addedAtMs`,`lastSeenMs`,`hidden`) " +
                    "VALUES ('aidexx:BELIEVED','aidexx','aidexx:x',NULL,'n','s',1,60,1,1,0)",
            )
            db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`sensorModelId`,`advertName`,`displayName`,`serialSuffix`," +
                    "`active`,`warmupWindowMin`,`addedAtMs`,`lastSeenMs`,`hidden`) " +
                    "VALUES ('aidexx:RETIRED','aidexx','aidexx:x',NULL,'n2','s2',0,60,2,2,1)",
            )
        }

        val db = helper.runMigrationsAndValidate(14, listOf(MigrationRunner.MIGRATION_13_14))

        // The old flag's meaning travelled to the new name, row for row.
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `authoritative` = 1"))
        assertEquals(
            1,
            countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `authoritative` = 1 AND `sourceId` = 'aidexx:BELIEVED'"),
        )
        // authoritative ⇒ active, and only that row.
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `active` = 1"))
        assertEquals(
            1,
            countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `active` = 1 AND `sourceId` = 'aidexx:BELIEVED'"),
        )
        // Everything else on both rows is untouched — a rename must not disturb a retired sensor.
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `hidden` = 1 AND `sourceId` = 'aidexx:RETIRED'"))
        assertEquals(2, countRows(db, "SELECT COUNT(*) FROM `cgm_source`"))
        db.close()
    }

    @Test
    fun migrate14To15_bgSourceIsAddedAndLeftNull() {
        // v15 (contract 0.4.0): `sample` records which sensor its bg came from. One nullable column,
        // nothing backfilled — a row written before it genuinely has no record of the sensor behind
        // it, every sensor the phone had met having been authoritative in turn, so stamping the
        // current one would be indistinguishable from having known.
        helper.createDatabase(14).use { db ->
            db.execSQL(
                "INSERT INTO `sample` (`ts`,`tzOffsetMin`,`bgMgdl`,`bgProvenance`,`bgFlag`," +
                    "`steps`,`mood`,`hr`,`sleep`,`exercise`,`updatedAt`) " +
                    "VALUES (300000,0,120,'MEASURED','NORMAL',NULL,NULL,NULL,NULL,NULL,1)",
            )
        }

        val db = helper.runMigrationsAndValidate(15, listOf(MigrationRunner.MIGRATION_14_15))

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `sample` WHERE `bgSource` IS NULL"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `sample` WHERE `bgMgdl` = 120"))
        db.close()
    }

    @Test
    fun migrate15To16_exerciseTablesMatchSchemaAndNoSampleIsBackfilled() {
        // v16 (logged exercise): two additive tables, nothing else touched. The `sample.exercise`
        // column has existed and been null since v1, and null there means the magnitude was never
        // recorded — which for every bucket predating this feature is exactly true, so a backfill
        // would invent a bout that was never walked.
        helper.createDatabase(15).use { db ->
            db.execSQL(
                "INSERT INTO `sample` (`ts`,`tzOffsetMin`,`bgMgdl`,`bgSource`,`bgProvenance`,`bgFlag`," +
                    "`steps`,`mood`,`hr`,`sleep`,`exercise`,`updatedAt`) " +
                    "VALUES (300000,0,120,NULL,'MEASURED','NORMAL',400,NULL,NULL,NULL,NULL,1)",
            )
        }

        val db = helper.runMigrationsAndValidate(16, listOf(MigrationRunner.MIGRATION_15_16))

        assertEquals(1, countTables(db, "exercise_session"))
        assertEquals(1, countTables(db, "exercise_fix"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `exercise_session`"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `exercise_fix`"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `sample` WHERE `exercise` IS NULL"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `sample` WHERE `steps` = 400"))
        db.close()
    }

    @Test
    fun migrate15To16_theBoutClientIdIsUnique() {
        // The unique index is what makes an archive restore a merge rather than a duplication: a bout
        // the phone already holds must lose to itself, not land twice with two tracks.
        helper.createDatabase(15).close()
        val db = helper.runMigrationsAndValidate(16, listOf(MigrationRunner.MIGRATION_15_16))

        fun insertBout() = db.execSQL(
            "INSERT INTO `exercise_session` " +
                "(`clientId`,`startMs`,`endMs`,`tzOffsetMin`,`kind`,`activeSec`,`distanceM`,`kcal`," +
                "`interrupted`,`note`,`updatedAt`) " +
                "VALUES ('bout-1',1000,2000,0,'WALK',900,1500.0,120,0,NULL,2000)",
        )
        insertBout()
        assertTrue(
            "a second bout under the same clientId was accepted",
            runCatching { insertBout() }.isFailure,
        )
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `exercise_session`"))
        db.close()
    }

    @Test
    fun migrate16To17_exerciseBecomesGramsAndTheSecondsAreDropped() {
        // v17 changes what the column MEANS, not just its type: whole active seconds per bucket
        // (0..300) become grams of carbohydrate equivalent (order 2.5). No per-bucket function of the
        // seconds recovers the grams — the disposal curve spreads a bout's magnitude over its length
        // plus ninety minutes — so the old values are dropped rather than converted, and everything
        // else in the row must survive the rebuild untouched.
        helper.createDatabase(16).use { db ->
            db.execSQL(
                "INSERT INTO `sample` (`ts`,`tzOffsetMin`,`bgMgdl`,`bgSource`,`bgProvenance`,`bgFlag`," +
                    "`steps`,`mood`,`hr`,`sleep`,`exercise`,`updatedAt`) " +
                    "VALUES (300000,60,120,'src-a','MEASURED','NORMAL',400,3,72,1,300,1700)",
            )
            db.execSQL(
                "INSERT INTO `sample` (`ts`,`tzOffsetMin`,`bgMgdl`,`bgSource`,`bgProvenance`,`bgFlag`," +
                    "`steps`,`mood`,`hr`,`sleep`,`exercise`,`updatedAt`) " +
                    "VALUES (600000,60,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,1800)",
            )
        }

        val db = helper.runMigrationsAndValidate(17, listOf(MigrationRunner.MIGRATION_16_17))

        assertEquals(2, countRows(db, "SELECT COUNT(*) FROM `sample`"))
        assertEquals(2, countRows(db, "SELECT COUNT(*) FROM `sample` WHERE `exercise` IS NULL"))
        // Every other column of the rebuilt row is the one that went in.
        assertEquals(
            1,
            countRows(
                db,
                "SELECT COUNT(*) FROM `sample` WHERE `ts` = 300000 AND `tzOffsetMin` = 60 AND " +
                    "`bgMgdl` = 120 AND `bgSource` = 'src-a' AND `bgProvenance` = 'MEASURED' AND " +
                    "`bgFlag` = 'NORMAL' AND `steps` = 400 AND `mood` = 3 AND `hr` = 72 AND " +
                    "`sleep` = 1 AND `updatedAt` = 1700",
            ),
        )
        // REAL affinity, so a fractional gram survives where the old column would have rounded it.
        db.execSQL(
            "INSERT INTO `sample` (`ts`,`tzOffsetMin`,`exercise`,`updatedAt`) VALUES (900000,60,2.5,1900)",
        )
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `sample` WHERE `exercise` = 2.5"))
        db.close()
    }

    @Test
    fun migrate17To18_rawSampleTableIsAddedEmptyAndNothingElseMoves() {
        // v18 (the sub-grid sample record): one additive table, nothing else touched. No backfill is
        // possible even in principle — `cgm_reading` has only ever held the sample that won each
        // slot, so the samples this table exists to keep were discarded before it existed.
        helper.createDatabase(17).use { db ->
            db.execSQL(
                "INSERT INTO `cgm_reading` (`sourceId`,`tsMs`,`bgMgdl`,`trendTenthsPerMin`," +
                    "`minFromStart`,`quality`,`provenance`,`flag`,`tzOffsetMin`,`rxWallMs`,`rssi`) " +
                    "VALUES ('src-a',300000,120,3,400,1,'MEASURED','NORMAL',60,299000,-70)",
            )
        }

        val db = helper.runMigrationsAndValidate(18, listOf(MigrationRunner.MIGRATION_17_18))

        assertEquals(1, countTables(db, "cgm_sample_raw"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `cgm_sample_raw`"))
        assertEquals("the grid series was touched", 1, countRows(db, "SELECT COUNT(*) FROM `cgm_reading`"))
        assertEquals(
            1,
            countRows(
                db,
                "SELECT COUNT(*) FROM `cgm_reading` WHERE `tsMs` = 300000 AND `rxWallMs` = 299000 " +
                    "AND `bgMgdl` = 120 AND `provenance` = 'MEASURED'",
            ),
        )
        // Off-grid receive instants are the point of the table; the key is (sourceId, rxWallMs), so
        // two samples of one source inside one slot both survive and a repeat of an instant does not.
        db.execSQL(
            "INSERT INTO `cgm_sample_raw` " +
                "(`sourceId`,`rxWallMs`,`bgMgdl`,`trendTenthsPerMin`,`minFromStart`,`quality`," +
                "`flag`,`tzOffsetMin`,`rssi`) VALUES ('src-a',299000,120,3,400,1,'NORMAL',60,-70)",
        )
        db.execSQL(
            "INSERT INTO `cgm_sample_raw` " +
                "(`sourceId`,`rxWallMs`,`bgMgdl`,`trendTenthsPerMin`,`minFromStart`,`quality`," +
                "`flag`,`tzOffsetMin`,`rssi`) VALUES ('src-a',419000,124,3,402,1,'NORMAL',60,-72)",
        )
        assertEquals(2, countRows(db, "SELECT COUNT(*) FROM `cgm_sample_raw`"))
        assertTrue(
            "a second sample was accepted under an instant already held",
            runCatching {
                db.execSQL(
                    "INSERT INTO `cgm_sample_raw` " +
                        "(`sourceId`,`rxWallMs`,`bgMgdl`,`trendTenthsPerMin`,`minFromStart`," +
                        "`quality`,`flag`,`tzOffsetMin`,`rssi`) " +
                        "VALUES ('src-a',299000,999,3,400,1,'NORMAL',60,-70)",
                )
            }.isFailure,
        )
        db.close()
    }

    @Test
    fun migrate18To19_isTheReservedNoOpAndLeavesTheSchemaExactlyAsItWas() {
        // Schema 19 is reserved on this branch: the storage it introduces belongs to a path only the
        // local-only branch carries, so the migration executes no statement. It is still registered and
        // still validated, because Room needs a path for every step and because "this step changes
        // nothing here" is a claim worth holding to the schema rather than assuming.
        helper.createDatabase(18).use { db ->
            db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`sensorModelId`,`advertName`,`displayName`,`serialSuffix`," +
                    "`authoritative`,`active`,`warmupWindowMin`,`addedAtMs`,`lastSeenMs`,`hidden`) " +
                    "VALUES ('aidexx:A','aidexx','aidexx:x',NULL,'n','s',1,1,60,1,1,0)",
            )
        }

        val db = helper.runMigrationsAndValidate(19, listOf(MigrationRunner.MIGRATION_18_19))

        // The row is untouched, and nothing was created beside it.
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source`"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `warmupWindowMin` = 60"))
        db.close()
    }

    @Test
    fun migrate19To20_theAdapterAndInfillTablesAreAdded() {
        // v20 (the adapter and the reconstructed sample): two additive tables, both hand-written
        // DDL. `runMigrationsAndValidate` is what compares them against what Room expects — and
        // there is no destructive fallback, so a one-column drift is a launch crash on the phone.
        helper.createDatabase(19).close()

        val db = helper.runMigrationsAndValidate(20, listOf(MigrationRunner.MIGRATION_19_20))

        assertEquals(1, countTables(db, "lora"))
        assertEquals(1, countTables(db, "bg_infill"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `lora`"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `bg_infill`"))
        db.close()
    }

    @Test
    fun migrate1To20_fullChain() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            20,
            listOf(
                MigrationRunner.MIGRATION_1_2,
                MigrationRunner.MIGRATION_2_3,
                MigrationRunner.MIGRATION_3_4,
                MigrationRunner.MIGRATION_4_5,
                MigrationRunner.MIGRATION_5_6,
                MigrationRunner.MIGRATION_6_7,
                MigrationRunner.MIGRATION_7_8,
                MigrationRunner.MIGRATION_8_9,
                MigrationRunner.MIGRATION_9_10,
                MigrationRunner.MIGRATION_10_11,
                MigrationRunner.MIGRATION_11_12,
                MigrationRunner.MIGRATION_12_13,
                MigrationRunner.MIGRATION_13_14,
                MigrationRunner.MIGRATION_14_15,
                MigrationRunner.MIGRATION_15_16,
                MigrationRunner.MIGRATION_16_17,
                MigrationRunner.MIGRATION_17_18,
                MigrationRunner.MIGRATION_18_19,
                MigrationRunner.MIGRATION_19_20,
            ),
        )
    }

    private fun countTables(db: SQLiteConnection, name: String): Int =
        countRows(db, "SELECT COUNT(*) FROM `sqlite_master` WHERE `type` = 'table' AND `name` = '$name'")

    private fun countRows(db: SQLiteConnection, sql: String): Int {
        val stmt = db.prepare(sql)
        try {
            stmt.step()
            return stmt.getLong(0).toInt()
        } finally {
            stmt.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
