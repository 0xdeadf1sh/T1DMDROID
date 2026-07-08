package com.t1dm.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.MigrationRunner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the keep-forever ALTER-only migration (PLAN.private.md Phase 1: destructive migration is
 * FORBIDDEN). Creates the schema at v1, applies [MigrationRunner.MIGRATION_1_2], and lets
 * MigrationTestHelper assert the migrated DB matches the exported v2 schema exactly — catching any
 * DDL drift (index names, AUTOINCREMENT, nullability) between the hand-written migration and Room.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_additiveTablesMatchSchema() {
        helper.createDatabase(TEST_DB, 1).close()
        // validateDroppedTables=true and the exported 2.json together prove exact structural parity.
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MigrationRunner.MIGRATION_1_2)
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
