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

/** There is no destructive fallback, so a DDL drift is a launch crash on the phone. The helper must
 *  open the [BundledSQLiteDriver] production ships, and its connection-based validate is lenient
 *  about unknown tables — which is what lets the Room-invisible `food_fts` shadow tables pass. */
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
        // A data-only re-seed: the schema must stay byte-identical to 5.
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
        // `clientId` is back-filled with a fresh UUID before the UNIQUE index is built; the retired
        // sample.carbsG/bolusU/basalU columns are left dead in place.
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
        helper.createDatabase(7).close()
        helper.runMigrationsAndValidate(8, listOf(MigrationRunner.MIGRATION_7_8))
    }

    @Test
    fun migrate8To9_noteTableIsGoneAndQueuedNoteRowsArePurged() {
        // The outbox purge is the load-bearing half: `OutboxKind.valueOf("NOTE")` would throw on
        // every later drain.
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
        helper.createDatabase(9).close()
        helper.runMigrationsAndValidate(10, listOf(MigrationRunner.MIGRATION_9_10))
    }

    @Test
    fun migrate10To11_everyStoredSourceIsClassifiedAndNoReadingMoves() {
        // The backfill decides whether a year of history stays reachable after a sensor change.
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

        assertEquals(
            2,
            countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `sensorModelId` = 'aidexx:x'"),
        )
        // The debug source keeps its own class, so injected readings never graft onto real history.
        assertEquals(
            1,
            countRows(
                db,
                "SELECT COUNT(*) FROM `cgm_source` " +
                    "WHERE `sensorModelId` = 'aidexx:debug' AND `sourceId` = 'aidexx:DEBUG'",
            ),
        )
        // '' would be a class of its own and would strand a sensor.
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `sensorModelId` = ''"))
        assertEquals(3, countRows(db, "SELECT COUNT(*) FROM `cgm_source`"))
        assertEquals(3, countRows(db, "SELECT COUNT(*) FROM `cgm_reading`"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `active` = 1"))
        db.close()
    }

    @Test
    fun migrate11To12_advertNameIsAddedAndLeftNull() {
        // Nothing is backfilled: the advertised name was discarded at match time, so for a sensor
        // already on record it is genuinely unknown.
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
        // Defaulted to 0: removal is a decision the user has not made for any existing sensor.
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
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `active` = 1"))
        db.close()
    }

    @Test
    fun migrate13To14_authorityIsRenamedAndActivitySeededFromIt() {
        // `active` is RENAMED to `authoritative` and a new `active` is seeded from it: seeding every
        // known sensor active instead would open a link to every sensor ever met on the next start.
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
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `hidden` = 1 AND `sourceId` = 'aidexx:RETIRED'"))
        assertEquals(2, countRows(db, "SELECT COUNT(*) FROM `cgm_source`"))
        db.close()
    }

    @Test
    fun migrate14To15_bgSourceIsAddedAndLeftNull() {
        // Nothing is backfilled: a pre-v15 row genuinely has no record of which sensor wrote it.
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
        // No backfill: null `sample.exercise` means the magnitude was never recorded, which for
        // every bucket predating this feature is true.
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
        // The unique index is what makes an archive restore a merge rather than a duplication.
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
        // The column changes MEANING: whole active seconds per bucket become grams of carbohydrate
        // equivalent. No per-bucket function recovers the grams, so the old values are dropped.
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
        // No backfill is possible: `cgm_reading` only ever held the sample that won each slot.
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
        // The key is (sourceId, rxWallMs): two samples inside one slot survive, a repeated instant
        // does not.
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
    fun migrate18To19_theSecretTableIsAddedAndEverySourceIsNumberedInListOrder() {
        // The ordinal is what a user reads as "this physical sensor", so the backfill has to be
        // deterministic and to agree with the list order (`addedAtMs`, then `sourceId`).
        helper.createDatabase(18).use { db ->
            fun source(id: String, added: Long) = db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`sensorModelId`,`advertName`,`displayName`,`serialSuffix`," +
                    "`authoritative`,`active`,`warmupWindowMin`,`addedAtMs`,`lastSeenMs`,`hidden`) " +
                    "VALUES ('$id','aidexx','aidexx:x',NULL,'n','s',0,0,60,$added,$added,0)",
            )
            source("aidexx:C", 3_000)
            source("aidexx:A", 1_000)
            // Same instant as A, so only the sourceId tiebreak can separate them.
            source("aidexx:B", 1_000)
        }

        val db = helper.runMigrationsAndValidate(19, listOf(MigrationRunner.MIGRATION_18_19))

        assertEquals(1, countTables(db, "cgm_sensor_secret"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `cgm_sensor_secret`"))

        // Zero-based, gapless, in list order: A (1000), B (1000, later id), C (3000).
        for ((id, ordinal) in listOf("aidexx:A" to 0, "aidexx:B" to 1, "aidexx:C" to 2)) {
            assertEquals(
                "ordinal of $id",
                1,
                countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `sourceId` = '$id' AND `ordinal` = $ordinal"),
            )
        }
        // `ordinal < 0` is the unassigned sentinel.
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `ordinal` < 0"))
        assertEquals(3, countRows(db, "SELECT COUNT(DISTINCT `ordinal`) FROM `cgm_source`"))
        assertEquals(3, countRows(db, "SELECT COUNT(*) FROM `cgm_source`"))
        assertEquals(3, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `warmupWindowMin` = 60"))

        // One row per sensor: two secrets for one sensor is two answers to "which key does it hold".
        db.execSQL("INSERT INTO `cgm_sensor_secret` (`sourceId`,`blob`,`updatedAtMs`) VALUES ('aidexx:A',X'0102',1)")
        assertTrue(
            "a second secret was accepted for a sensor that already had one",
            runCatching {
                db.execSQL("INSERT INTO `cgm_sensor_secret` (`sourceId`,`blob`,`updatedAtMs`) VALUES ('aidexx:A',X'0304',2)")
            }.isFailure,
        )
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_sensor_secret`"))
        db.close()
    }

    @Test
    fun migrate18To19_isIdempotentOnADatabaseThatAlreadyHasTheColumn() {
        // A migration interrupted part way is re-applied whole on the next open, so both statements
        // must survive a re-run. The ALTER cannot, which is why it is not one of them.
        helper.createDatabase(18).use { db ->
            db.execSQL(
                "INSERT INTO `cgm_source` " +
                    "(`sourceId`,`vendorId`,`sensorModelId`,`advertName`,`displayName`,`serialSuffix`," +
                    "`authoritative`,`active`,`warmupWindowMin`,`addedAtMs`,`lastSeenMs`,`hidden`) " +
                    "VALUES ('aidexx:A','aidexx','aidexx:x',NULL,'n','s',1,1,60,1,1,0)",
            )
        }

        val db = helper.runMigrationsAndValidate(19, listOf(MigrationRunner.MIGRATION_18_19))
        db.execSQL(MigrationRunner.SQL_18_19_CREATE_SECRET)
        db.execSQL(MigrationRunner.SQL_18_19_BACKFILL_ORDINAL)

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `cgm_source` WHERE `ordinal` = 0"))
        assertEquals(1, countTables(db, "cgm_sensor_secret"))
        db.close()
    }

    @Test
    fun migrate19To20_theAdapterAndInfillTablesAreAdded() {
        helper.createDatabase(19).close()

        val db = helper.runMigrationsAndValidate(20, listOf(MigrationRunner.MIGRATION_19_20))

        assertEquals(1, countTables(db, "lora"))
        assertEquals(1, countTables(db, "bg_infill"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `lora`"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `bg_infill`"))
        db.close()
    }

    /** `loggedAtMs` takes `updatedAt` on every existing row: nothing could edit one before v21. */
    @Test
    fun migrate20To21_theMutationStampsBackfillAndTheTombstoneTableIsAdded() {
        val seed = helper.createDatabase(20)
        seed.execSQL(
            "INSERT INTO `logged_dose` (`clientId`,`tsMs`,`kind`,`units`,`durationMin`,`tzOffsetMin`," +
                "`note`,`updatedAt`) VALUES ('d-1',1700000100000,'BOLUS',4.0,360.0,0,NULL,1700000111111)",
        )
        seed.execSQL(
            "INSERT INTO `logged_meal` (`clientId`,`tsMs`,`grams`,`durationMin`,`tzOffsetMin`," +
                "`note`,`updatedAt`) VALUES ('m-1',1700000100000,45.0,180.0,0,NULL,1700000122222)",
        )
        seed.close()

        val db = helper.runMigrationsAndValidate(21, listOf(MigrationRunner.MIGRATION_20_21))

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `logged_dose`"))
        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `logged_meal`"))
        assertEquals(
            "every pre-v21 row's loggedAtMs is its updatedAt",
            0,
            countRows(db, "SELECT COUNT(*) FROM `logged_dose` WHERE `loggedAtMs` != `updatedAt`"),
        )
        assertEquals(
            0,
            countRows(db, "SELECT COUNT(*) FROM `logged_meal` WHERE `loggedAtMs` != `updatedAt`"),
        )
        assertEquals(
            "nothing has been edited yet — a fact about the rows, not an unknown",
            0,
            countRows(db, "SELECT COUNT(*) FROM `logged_dose` WHERE `mutatedAtMs` IS NOT NULL"),
        )
        assertEquals(1, countTables(db, "event_tombstone"))
        assertEquals(0, countRows(db, "SELECT COUNT(*) FROM `event_tombstone`"))
        db.close()
    }

    /** Every pre-v22 row becomes its own one-step span; the runs were never recorded. */
    @Test
    fun migrate21To22_theSpanKeyBackfillsToEachRowsOwnTs() {
        val seed = helper.createDatabase(21)
        seed.execSQL(
            "INSERT INTO `bg_infill` (`ts`,`mgdl`,`lo90`,`hi90`,`modelId`,`createdAtMs`) " +
                "VALUES (1700000100000,120.0,100.0,140.0,'m.pte',1700000100000)",
        )
        seed.close()

        val db = helper.runMigrationsAndValidate(22, listOf(MigrationRunner.MIGRATION_21_22))

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `bg_infill`"))
        assertEquals(
            0,
            countRows(db, "SELECT COUNT(*) FROM `bg_infill` WHERE `spanStartMs` != `ts`"),
        )
        assertEquals(
            "nothing has been promoted yet",
            0,
            countRows(db, "SELECT COUNT(*) FROM `bg_infill` WHERE `promotedAtMs` IS NOT NULL"),
        )
        db.close()
    }

    /** `ABSENT` refuses attach, so an adapter nobody measured is blocked, not assumed fine. */
    @Test
    fun migrate22To23_anUnmeasuredAdapterBackfillsToAbsent() {
        val seed = helper.createDatabase(22)
        seed.execSQL(
            "INSERT INTO `lora` (`modelId`,`name`,`blob`,`rank`,`alpha`,`targets`,`nParams`," +
                "`nTrain`,`nHoldout`,`epochs`,`holdoutBefore`,`holdoutAfter`,`improved`," +
                "`attached`,`createdAtMs`,`updatedAtMs`) VALUES ('m.pte','a',X'00',4,8.0,15,100," +
                "40,10,20,0.5,0.4,1,0,1700000000000,1700000000000)",
        )
        seed.close()

        val db = helper.runMigrationsAndValidate(23, listOf(MigrationRunner.MIGRATION_22_23))

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `lora`"))
        assertEquals(
            "an adapter nobody measured is ABSENT, which refuses attach",
            1,
            countRows(db, "SELECT COUNT(*) FROM `lora` WHERE `guardVerdict` = 'ABSENT'"),
        )
        assertEquals(
            "and it carries no override",
            0,
            countRows(db, "SELECT COUNT(*) FROM `lora` WHERE `guardOverrideAtMs` IS NOT NULL"),
        )
        db.close()
    }

    /** A pre-v24 row's fan is EMPTY, not levels synthesised from its two edges — a manufactured
     *  interior would be indistinguishable from one a model emitted. `tau` back-fills to 0.5. */
    @Test
    fun migrate23To24_anOldFillHasNoFanAndIsTheMedian() {
        val seed = helper.createDatabase(23)
        seed.execSQL(
            "INSERT INTO `bg_infill` (`ts`,`mgdl`,`lo90`,`hi90`,`modelId`,`createdAtMs`," +
                "`spanStartMs`,`promotedAtMs`) VALUES (1700000000000,120.0,95.0,150.0,'m.pte'," +
                "1700000000000,1700000000000,NULL)",
        )
        seed.close()

        val db = helper.runMigrationsAndValidate(24, listOf(MigrationRunner.MIGRATION_23_24))

        assertEquals(1, countRows(db, "SELECT COUNT(*) FROM `bg_infill`"))
        assertEquals(
            "a fill drawn before the fan was kept has no fan",
            1,
            countRows(db, "SELECT COUNT(*) FROM `bg_infill` WHERE LENGTH(`bandsMgdl`) = 0 AND LENGTH(`bandsRisk`) = 0"),
        )
        assertEquals(
            "and its line is the median, which is what it was",
            1,
            countRows(db, "SELECT COUNT(*) FROM `bg_infill` WHERE `tau` = 0.5"),
        )
        assertEquals(
            "the outer band it did carry survives untouched",
            1,
            countRows(db, "SELECT COUNT(*) FROM `bg_infill` WHERE `lo90` = 95.0 AND `hi90` = 150.0"),
        )
        db.close()
    }

    @Test
    fun migrate24To25_forecastsWithNoSensorAreDiscarded() {
        val seed = helper.createDatabase(24)
        seed.execSQL(
            "INSERT INTO `prediction` (`madeAtMs`,`modelId`,`horizonSteps`,`nQuantiles`,`stepMs`," +
                "`anchorTsMs`,`lastBg`,`lineBlob`,`fanBlob`,`todBlob`,`todConf`,`status`,`backend`," +
                "`precision`,`selected`,`stale`,`latencyMs`,`createdAtMs`) VALUES " +
                "(1700000000000,'m',24,7,300000,1700000000000,120.0,x'',x'',NULL,NULL,'OK'," +
                "'EXECUTORCH_XNNPACK_FP32','FP32',1,0,1.0,1700000000000)",
        )
        seed.close()

        val db = helper.runMigrationsAndValidate(25, listOf(MigrationRunner.MIGRATION_24_25))

        assertEquals(
            "a forecast that does not record its sensor cannot be attributed, so it is deleted",
            0,
            countRows(db, "SELECT COUNT(*) FROM `prediction`"),
        )
        db.execSQL(
            "INSERT INTO `prediction` (`madeAtMs`,`modelId`,`horizonSteps`,`nQuantiles`,`stepMs`," +
                "`anchorTsMs`,`sourceId`,`lastBg`,`lineBlob`,`fanBlob`,`todBlob`,`todConf`,`status`," +
                "`backend`,`precision`,`selected`,`stale`,`latencyMs`,`createdAtMs`) VALUES " +
                "(1700000300000,'m',24,7,300000,1700000300000,'vendorb:1',120.0,x'',x'',NULL,NULL,'OK'," +
                "'EXECUTORCH_XNNPACK_FP32','FP32',1,0,1.0,1700000300000)",
        )
        assertEquals(
            1,
            countRows(db, "SELECT COUNT(*) FROM `prediction` WHERE `sourceId` = 'vendorb:1'"),
        )
        db.close()
    }

    @Test
    fun migrate25To26_aCorrectionSurvivesAndGainsItsSensor() {
        val seed = helper.createDatabase(25)
        seed.execSQL(
            "INSERT INTO `conformal_delta` (`modelId`,`steps`,`nQuantiles`,`deltaBlob`,`nCal`,`nEval`," +
                "`maxAbsDeltaMgdl`,`cov90Raw`,`cov90Cal`,`meanWidth90Raw`,`meanWidth90Cal`," +
                "`windowDays`,`fittedAtMs`) VALUES ('m',24,7,x'',400,120,18.0,0.81,0.9,60.0,72.0,14,5)",
        )
        seed.close()

        val db = helper.runMigrationsAndValidate(26, listOf(MigrationRunner.MIGRATION_25_26))

        assertEquals(
            "the row is kept — the drill-down still has to say what the last fit bought",
            1,
            countRows(db, "SELECT COUNT(*) FROM `conformal_delta`"),
        )
        assertEquals(
            "with no sensor, which the apply reads as UNKNOWN and refuses to draw",
            1,
            countRows(db, "SELECT COUNT(*) FROM `conformal_delta` WHERE `sourceId` IS NULL"),
        )
        db.close()
    }

    @Test
    fun migrate1To26_fullChain() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            26,
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
                MigrationRunner.MIGRATION_20_21,
                MigrationRunner.MIGRATION_21_22,
                MigrationRunner.MIGRATION_22_23,
                MigrationRunner.MIGRATION_23_24,
                MigrationRunner.MIGRATION_24_25,
                MigrationRunner.MIGRATION_25_26,
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
