package com.t1dm.data.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.t1dm.data.meals.FoodSeed
import java.util.UUID

/**
 * The single registry of schema migrations (Phase 1). Keep-forever storage
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
     * v1 → v2 (Phase 3): additive only — the dedicated `prediction` table and the
     * N-profile `server_profile` table. No Phase-1 table is touched.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
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
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_prediction_madeAtMs_modelId` " +
                    "ON `prediction` (`madeAtMs`, `modelId`)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_madeAtMs` ON `prediction` (`madeAtMs`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_prediction_modelId` ON `prediction` (`modelId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `server_profile` (" +
                    "`id` TEXT NOT NULL, `label` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, " +
                    "`active` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                    "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
        }
    }

    /**
     * v2 → v3 (SPEC.private.md §3.3, Phase 4): additive only — the curve-engine event stores
     * `logged_dose` (self-describing bolus/basal curve params), `logged_meal` (grams + GI +
     * optional custom appearance curve), and `basal_schedule` (daily MDI schedule / search
     * space). No existing table is touched. DDL transcribed verbatim from the generated
     * `schemas/<db>/3.json` so the migrated DB is byte-identical to a fresh `createAllTables`.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `logged_dose` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tsMs` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `units` REAL NOT NULL, `durationMin` REAL NOT NULL, " +
                    "`k` REAL, `theta` REAL, `kaPerHour` REAL, `kePerHour` REAL, " +
                    "`tzOffsetMin` INTEGER NOT NULL, `note` TEXT, `updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_logged_dose_tsMs` ON `logged_dose` (`tsMs`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `logged_meal` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tsMs` INTEGER NOT NULL, " +
                    "`grams` REAL NOT NULL, `gi` REAL, `k` REAL, `theta` REAL, " +
                    "`durationMin` REAL NOT NULL, `customCurve` BLOB, `tzOffsetMin` INTEGER NOT NULL, " +
                    "`note` TEXT, `updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_logged_meal_tsMs` ON `logged_meal` (`tsMs`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `basal_schedule` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scheduleId` TEXT NOT NULL, " +
                    "`label` TEXT NOT NULL, `timeOfDayMin` INTEGER NOT NULL, `doseU` REAL NOT NULL, " +
                    "`durationMin` REAL NOT NULL, `kaPerHour` REAL NOT NULL, `kePerHour` REAL NOT NULL, " +
                    "`tzOffsetMin` INTEGER NOT NULL, `active` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_basal_schedule_scheduleId` ON `basal_schedule` (`scheduleId`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_basal_schedule_active` ON `basal_schedule` (`active`)",
            )
        }
    }

    /**
     * v3 → v4 (Phase 4, journal): additive only — the free-text `note` table
     * (the durable `NOTE`-outbox producer). No existing table is touched. DDL transcribed verbatim
     * from the generated `schemas/<db>/4.json` so the migrated DB is byte-identical to a fresh
     * `createAllTables`.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `note` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tsMs` INTEGER NOT NULL, " +
                    "`tzOffsetMin` INTEGER NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_note_tsMs` ON `note` (`tsMs`)")
        }
    }

    /**
     * v4 → v5 (Phase 4, meal builder): additive only — the glycemic dictionary
     * `food`, the `saved_meal`/`saved_meal_item` pair, `insulin_type`, and the hand-rolled FTS5
     * search index [FoodFts] over `food`. No existing table is touched. The entity-table DDL is
     * transcribed verbatim from the generated `schemas/<db>/5.json`; the FTS5 virtual table + its
     * sync triggers come from the shared [FoodFts.DDL] (the identical DDL the `onCreate` callback
     * runs on a fresh install), so a migrated DB matches a fresh `createAllTables` for every Room
     * entity table (the FTS shadow tables, being Room-invisible, are outside that comparison).
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `food` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`brand` TEXT, `carbsPer100g` REAL NOT NULL, `gi` REAL, `category` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `custom` INTEGER NOT NULL, `customCurve` BLOB, " +
                    "`updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_food_name` ON `food` (`name`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_food_custom` ON `food` (`custom`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `saved_meal` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `saved_meal_item` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mealId` INTEGER NOT NULL, " +
                    "`foodId` INTEGER, `name` TEXT NOT NULL, `grams` REAL NOT NULL, " +
                    "`carbsPer100g` REAL NOT NULL, `gi` REAL, `customCurve` BLOB)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_saved_meal_item_mealId` ON `saved_meal_item` (`mealId`)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `insulin_type` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `durationMin` REAL NOT NULL, `k` REAL, `theta` REAL, " +
                    "`kaPerHour` REAL, `kePerHour` REAL, `customCurve` BLOB, " +
                    "`builtin` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_insulin_type_builtin` ON `insulin_type` (`builtin`)")
            // Additive column: a custom insulin type's drawn action curve, symmetric with
            // `logged_meal.customCurve` (nullable BLOB, no default).
            connection.execSQL("ALTER TABLE `logged_dose` ADD COLUMN `customCurve` BLOB")
            FoodFts.create(connection)
        }
    }

    /**
     * v5 → v6 (Phase 7C, glycemic-dictionary expansion): a **data-only** additive
     * re-seed — no table or column is added or changed, so a fresh `createAllTables` at v6 is
     * schema-identical to v5. Its sole job is to fold the grown [FoodSeed] catalogue into an
     * install that already seeded the smaller Phase-4 set: it inserts every bundled row NOT already
     * present (matched by the `name` + `brand` natural key, restricted to seeded `custom = 0` rows),
     * so the ~171 original rows are preserved and the new ones appended. A fresh install never runs
     * this — it seeds the full catalogue in Kotlin via `MealsController.seedIfEmpty`.
     *
     * Inserting into `food` fires the [FoodFts] `food_ai` AFTER-INSERT trigger (created by
     * [MIGRATION_4_5]/`onCreate`), so `food_fts` repopulates for exactly the new rows — no duplicate
     * FTS entries for the pre-existing ones, which the `NOT EXISTS` guard skips. Reading the current
     * [FoodSeed.ROWS] keeps this convergent: whatever the catalogue is at build time, running the
     * migration chain always lands the DB on the full current set (idempotent under re-runs).
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            val ts = System.currentTimeMillis()
            // Numbered params (?1..?7) so the NOT EXISTS subquery reuses name/brand without rebinding.
            // `brand IS ?2` is null-safe equality (matches both a NULL brand and a literal one).
            val stmt = connection.prepare(
                "INSERT INTO `food` (name, brand, carbsPer100g, gi, category, source, custom, customCurve, updatedAt) " +
                    "SELECT ?1, ?2, ?3, ?4, ?5, ?6, 0, NULL, ?7 " +
                    "WHERE NOT EXISTS (SELECT 1 FROM `food` WHERE name = ?1 AND brand IS ?2 AND custom = 0)",
            )
            try {
                for (r in FoodSeed.ROWS) {
                    stmt.bindText(1, r.name)
                    if (r.brand == null) stmt.bindNull(2) else stmt.bindText(2, r.brand)
                    stmt.bindDouble(3, r.carbsPer100g)
                    if (r.gi == null) stmt.bindNull(4) else stmt.bindDouble(4, r.gi)
                    stmt.bindText(5, r.category)
                    stmt.bindText(6, FoodSeed.SOURCE)
                    stmt.bindLong(7, ts)
                    stmt.step()
                    stmt.reset()
                    stmt.clearBindings()
                }
            } finally {
                stmt.close()
            }
        }
    }

    /**
     * v6 → v7 (app-authoritative redesign, §3.2/§3.4, H1): additive only — `logged_meal` and
     * `logged_dose` each gain a phone-minted `clientId` (the stable id the server keys meal/dose
     * upserts on and the app re-hydrates by) plus a UNIQUE index over it. `ADD COLUMN` requires a
     * non-null default so pre-existing rows populate, so each legacy row is then back-filled with a
     * fresh random UUID BEFORE the UNIQUE index is built — otherwise two legacy rows would collide
     * on the placeholder `''`. The entity declares no column default, so Room's schema validation
     * ignores the DB-side `DEFAULT ''` (a column default is validated only when the entity declares
     * one).
     *
     * The retired `sample.carbsG/bolusU/basalU` dose projections are intentionally LEFT IN PLACE
     * (leave-dead): `DROP COLUMN` rewrites the whole table, and once nothing writes them the columns
     * are inert.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            addClientId(connection, "logged_meal")
            addClientId(connection, "logged_dose")
            // #5: carbs/bolus/basal are curve EVENTS now, so `sample` must drop those three dead
            // columns to match SampleEntity — Room validates TableInfo exactly and rejects a table
            // with extra columns. Rebuild the table (portable) rather than ALTER TABLE DROP COLUMN,
            // which the bundled SQLite driver does not handle reliably. Schema mirrors 7.json exactly.
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `sample_new` (`ts` INTEGER NOT NULL, " +
                    "`tzOffsetMin` INTEGER NOT NULL, `bgMgdl` INTEGER, `bgProvenance` TEXT, " +
                    "`bgFlag` TEXT, `steps` INTEGER, `mood` INTEGER, `hr` INTEGER, `sleep` INTEGER, " +
                    "`exercise` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ts`))",
            )
            connection.execSQL(
                "INSERT INTO `sample_new` " +
                    "(`ts`,`tzOffsetMin`,`bgMgdl`,`bgProvenance`,`bgFlag`,`steps`,`mood`,`hr`,`sleep`,`exercise`,`updatedAt`) " +
                    "SELECT `ts`,`tzOffsetMin`,`bgMgdl`,`bgProvenance`,`bgFlag`,`steps`,`mood`,`hr`,`sleep`,`exercise`,`updatedAt` " +
                    "FROM `sample`",
            )
            connection.execSQL("DROP TABLE `sample`")
            connection.execSQL("ALTER TABLE `sample_new` RENAME TO `sample`")
        }

        private fun addClientId(connection: SQLiteConnection, table: String) {
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `clientId` TEXT NOT NULL DEFAULT ''")
            val ids = ArrayList<Long>()
            val select = connection.prepare("SELECT `id` FROM `$table`")
            try {
                while (select.step()) ids.add(select.getLong(0))
            } finally {
                select.close()
            }
            val update = connection.prepare("UPDATE `$table` SET `clientId` = ?1 WHERE `id` = ?2")
            try {
                for (id in ids) {
                    update.bindText(1, UUID.randomUUID().toString())
                    update.bindLong(2, id)
                    update.step()
                    update.reset()
                    update.clearBindings()
                }
            } finally {
                update.close()
            }
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_clientId` ON `$table` (`clientId`)",
            )
        }
    }

    /**
     * v7 → v8 (graph annotation layer): additive only — the single new `bg_paint_stroke` table
     * holding the freehand drawings painted over the BG panel, plus the two time-bound indices the
     * viewport cull selects on. No existing table is touched and nothing else reads the rows, so the
     * migration cannot perturb any channel, calculator or §3.6 rail. DDL transcribed verbatim from
     * the generated `schemas/<db>/8.json` (its `${TABLE_NAME}` placeholder resolved) so the migrated
     * DB is byte-identical to a fresh `createAllTables`.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `bg_paint_stroke` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                    "`tool` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `widthDp` REAL NOT NULL, " +
                    "`minTsMs` INTEGER NOT NULL, `maxTsMs` INTEGER NOT NULL, `points` BLOB NOT NULL)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bg_paint_stroke_minTsMs` ON `bg_paint_stroke` (`minTsMs`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_bg_paint_stroke_maxTsMs` ON `bg_paint_stroke` (`maxTsMs`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
    )

    /** Apply every registered migration to a builder; the sole path that wires migrations. */
    fun <T : RoomDatabase> configure(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder.addMigrations(*ALL)
    // NOTE: no `.fallbackToDestructiveMigration(...)` — a missing migration must fail loudly,
    // never silently discard the user's keep-forever history.
}
