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
    fun migrate1To13_fullChain() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(
            13,
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
