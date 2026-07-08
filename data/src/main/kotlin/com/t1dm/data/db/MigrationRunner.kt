package com.t1dm.data.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The single registry of schema migrations (PLAN.private.md Phase 1). Keep-forever storage
 * FORBIDS destructive migration: every future change is a new [Migration] whose body is
 * append-only DDL — `ALTER TABLE … ADD COLUMN`, `CREATE TABLE`, `CREATE INDEX` — never a
 * `DROP`/`RENAME`-that-loses-data and never `fallbackToDestructiveMigration`.
 *
 * Each version appends exactly one `Migration(n-1, n)` here and its exported `schemas/<db>/n.json`
 * gates it in CI. The DDL below is transcribed verbatim from the generated schema so the migrated
 * DB is byte-identical to a fresh `createAllTables`.
 */
object MigrationRunner {

    /**
     * v1 → v2 (PLAN.private.md Phase 3): additive only — the dedicated `prediction` table and the
     * N-profile `server_profile` table. No Phase-1 table is touched.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `prediction` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`madeAtMs` INTEGER NOT NULL, `modelId` TEXT NOT NULL, " +
                    "`horizonSteps` INTEGER NOT NULL, `nQuantiles` INTEGER NOT NULL, " +
                    "`stepMs` INTEGER NOT NULL, `anchorTsMs` INTEGER NOT NULL, " +
                    "`lastBg` REAL NOT NULL, `lineBlob` BLOB NOT NULL, `fanBlob` BLOB NOT NULL, " +
                    "`todBlob` BLOB, `todConf` REAL, `status` TEXT NOT NULL, `backend` TEXT NOT NULL, " +
                    "`precision` TEXT NOT NULL, `selected` INTEGER NOT NULL, `stale` INTEGER NOT NULL, " +
                    "`latencyMs` REAL, `createdAtMs` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_prediction_madeAtMs_modelId` " +
                    "ON `prediction` (`madeAtMs`, `modelId`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_madeAtMs` ON `prediction` (`madeAtMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_modelId` ON `prediction` (`modelId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `server_profile` (" +
                    "`id` TEXT NOT NULL, `label` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, " +
                    "`active` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                    "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    /** Apply every registered migration to a builder; the sole path that wires migrations. */
    fun <T : RoomDatabase> configure(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder.addMigrations(*ALL)
    // NOTE: no `.fallbackToDestructiveMigration(...)` — a missing migration must fail loudly,
    // never silently discard the user's keep-forever history.
}
