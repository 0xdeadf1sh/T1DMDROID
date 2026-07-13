package com.t1dm.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.MigrationRunner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the keep-forever ALTER-only migration (Phase 1: destructive migration is
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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
