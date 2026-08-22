package com.t1dm.data.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.t1dm.data.meals.FoodSeed
import java.util.UUID

/**
 * The single registry of schema migrations (Phase 1). Keep-forever storage
 * FORBIDS destructive migration: every change is a new [Migration] whose body is append-only DDL —
 * `ALTER TABLE … ADD COLUMN`, `CREATE TABLE`, `CREATE INDEX` — and never
 * `fallbackToDestructiveMigration`.
 *
 * A withdrawn feature is the one exception, and [MIGRATION_8_9] is its only instance: when a
 * surface is removed whole, the table nothing will ever read again is dropped with it. That is
 * bounded by what the removal already decided — it may only drop a store no surviving code reads,
 * never trim a table another feature still keys off.
 *
 * Two migrations REBUILD a table rather than alter it, both `sample`, because SQLite offers no way
 * to do what they need: [MIGRATION_6_7] dropped three dead columns Room compares exactly, and
 * [MIGRATION_16_17] changed a column's declared type. Each states what it discarded and why.
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
     * v2 → v3 (§3.3, Phase 4): additive only — the curve-engine event stores
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
     * v3 → v4: additive only — the free-text `note` table (the durable `NOTE`-outbox producer). No
     * existing table is touched. DDL transcribed verbatim from the generated `schemas/<db>/4.json`
     * so the migrated DB is byte-identical to a fresh `createAllTables`.
     *
     * The table it creates is dropped again by [MIGRATION_8_9]; this step stays because the chain
     * from v3 must still reach v4 before it can reach v9.
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

    /**
     * v8 → v9 (the free-text note surface is withdrawn): the sole **subtractive** migration. The
     * `note` table and its `tsMs` index are dropped, because the screen, the domain type, the DAO
     * and the `POST /v1/notes` push are all gone and nothing left in the app can read a row.
     * Dropping the index explicitly is redundant under SQLite (`DROP TABLE` takes its indices with
     * it) and kept so the intent survives a reader who does not know that.
     *
     * The second statement is the one that must not be forgotten. `OutboxEntity.kind` persists as
     * the enum's `name`, and `Converters.stringToOutboxKind` resolves it with `valueOf` — so a
     * `NOTE` row left queued from a pre-upgrade build would throw the moment the drainer read it,
     * and keep throwing on every drain thereafter. Its endpoint no longer exists on the server
     * either (the wire contract dropped `POST /v1/notes`), so the row could never be delivered:
     * purging it here is the only outcome available, and it must happen in the same transaction
     * that removes the enum constant's last legal source.
     */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX IF EXISTS `index_note_tsMs`")
            connection.execSQL("DROP TABLE IF EXISTS `note`")
            connection.execSQL("DELETE FROM `outbox` WHERE `kind` = 'NOTE'")
        }
    }

    /**
     * v9 → v10 (on-device band recalibration): additive only — the single `conformal_delta` table
     * holding one fitted `SPEC/inference.md` §8.4 correction per model. No existing table is
     * touched, and nothing already stored changes meaning: the `prediction` fan stays the raw fan
     * the model produced, because the correction is a display quantity that never travels and never
     * classifies. DDL transcribed verbatim from the generated `schemas/<db>/10.json` so the migrated
     * DB is byte-identical to a fresh `createAllTables`.
     *
     * No index accompanies it. `modelId` IS the primary key, and the two queries the table has —
     * one model's correction, and all of them — are covered by that alone.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `conformal_delta` (" +
                    "`modelId` TEXT NOT NULL, `steps` INTEGER NOT NULL, " +
                    "`nQuantiles` INTEGER NOT NULL, `deltaBlob` BLOB NOT NULL, " +
                    "`nCal` INTEGER NOT NULL, `nEval` INTEGER NOT NULL, " +
                    "`maxAbsDeltaMgdl` REAL NOT NULL, `cov90Raw` REAL, `cov90Cal` REAL, " +
                    "`meanWidth90Raw` REAL, `meanWidth90Cal` REAL, " +
                    "`windowDays` INTEGER NOT NULL, `fittedAtMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`modelId`))",
            )
        }
    }

    /**
     * The two v10 → v11 backfill statements, named so `MigrationConstantsTest` can assert on the SQL
     * [MIGRATION_10_11] actually runs rather than on a transcription of it.
     *
     * **The literals are frozen on purpose.** A migration describes what the schema became at a
     * point in history, so it may never read a constant that a later edit could move underneath it —
     * a rename of `CgmSensorModelId.AIDEX_X` must not retroactively change what every already-upgraded
     * device was given. That test is what holds these strings and `CgmSensorModelId` together instead.
     */
    internal const val SQL_10_11_BACKFILL_DEBUG =
        "UPDATE `cgm_source` SET `sensorModelId` = 'aidexx:debug' " +
            "WHERE `sensorModelId` = '' AND `sourceId` = 'aidexx:DEBUG'"

    internal const val SQL_10_11_BACKFILL_REAL =
        "UPDATE `cgm_source` SET `sensorModelId` = 'aidexx:x' WHERE `sensorModelId` = ''"

    /**
     * v10 → v11 (displayed history spans a sensor MODEL, not one physical sensor): additive only —
     * `cgm_source` gains `sensorModelId` plus its index. No reading moves and no row is deleted; what
     * changes is only the scope the BG panel's query selects over.
     *
     * **Why the backfill can be exact.** Every row that can exist at v10 was written by the AiDEX X
     * plugin — it is the only vendor plugin there has ever been — so every real sensor belongs to
     * [com.t1dm.core.model.CgmSensorModelId.AIDEX_X]. The one exception is the debug-injection source,
     * which keeps its own class so injected readings stay out of the real trace exactly as they were
     * before this column existed.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `cgm_source` ADD COLUMN `sensorModelId` TEXT NOT NULL DEFAULT ''",
            )
            // Debug first: the second statement claims every remaining unclassified row, so ordering
            // them the other way would sweep the debug source into the real sensor's class.
            connection.execSQL(SQL_10_11_BACKFILL_DEBUG)
            connection.execSQL(SQL_10_11_BACKFILL_REAL)
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cgm_source_sensorModelId` ON `cgm_source` (`sensorModelId`)",
            )
        }
    }

    /**
     * v11 → v12 (a source records what it advertised): additive only — `cgm_source` gains a nullable
     * `advertName`. No index: nothing queries by it, and the table holds one row per sensor the phone
     * has ever met.
     *
     * **Deliberately left null for every existing row.** The advertised name was matched, used to
     * strip the serial, and then discarded, so for a sensor already on record the app genuinely does
     * not know which brand it announced — a backfill could only invent one, and an invented value is
     * indistinguishable from an observed one precisely where the difference matters. Null means "never
     * recorded", and that is the honest answer.
     *
     * What it is FOR: [com.t1dm.core.model.CgmSourceDescriptor.sensorModelId] is assigned once, at
     * first sighting, from the vendor's prefix table. If that table ever splits one class into
     * several, this column is the only thing that could reclassify sensors already filed — sensors
     * met after this migration, at least. Those met before keep whatever class they were given.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `cgm_source` ADD COLUMN `advertName` TEXT")
        }
    }

    /**
     * v12 → v13 (a retired sensor can be taken off the lists): additive only — `cgm_source` gains
     * `hidden`, defaulting to 0 so every sensor already on record stays listed exactly as it was.
     *
     * **A display flag, deliberately not a delete.** The BG panel's history spans a sensor MODEL and
     * takes its id set from this table ([CgmSourceDao.observeIdsForSensorModel]), so dropping the row
     * would erase that sensor's stretch of the trace while leaving its `cgm_reading` rows behind,
     * unreachable and unreclaimable. Hiding costs one column and loses nothing.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `cgm_source` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v13 → v14 (several sensors may be read at once): `cgm_source.active` is RENAMED to
     * `authoritative`, and a new `active` takes the name.
     *
     * The column never meant "the app is reading this sensor" — it meant "this is the one sensor
     * every value on screen is derived from", which is a far narrower claim and the only one the app
     * could make while it read one sensor at a time. Splitting the two lets a second sensor be read
     * and drawn without being believed. The old rows carry the narrow meaning, so the rename moves
     * the data and the new column is seeded from it: the sensor that was authoritative is also, and
     * still, the one being read.
     *
     * A rename rather than add-and-backfill because the old column IS the new `authoritative`, row
     * for row — copying it into a fresh column and dropping the original would do the same work while
     * leaving a window where a crash lost which source was believed. `ALTER TABLE … RENAME COLUMN`
     * needs SQLite 3.25+, which is not a question here: the database ships its own
     * `BundledSQLiteDriver` precisely so the OEM build cannot decide what SQL is available
     * ([AppDatabase.build]).
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `cgm_source` RENAME COLUMN `active` TO `authoritative`")
            connection.execSQL("ALTER TABLE `cgm_source` ADD COLUMN `active` INTEGER NOT NULL DEFAULT 0")
            // authoritative ⇒ active. Seeding the other way — every known sensor active — would have
            // the app open a link to every sensor it has ever met on the next start.
            connection.execSQL("UPDATE `cgm_source` SET `active` = `authoritative`")
        }
    }

    /**
     * v14 → v15 (contract 0.4.0): `sample` records WHICH sensor its `bg` came from.
     *
     * Additive and null-defaulted. A row written before this genuinely has no record of the sensor
     * behind it — every sensor the phone had met was authoritative in turn, and the projection kept
     * no trace of which — so backfilling the current one would be indistinguishable from having
     * known. Null means "not recorded", which is the honest answer and what the wire sends.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `sample` ADD COLUMN `bgSource` TEXT")
        }
    }

    /**
     * v15 → v16 (logged exercise): additive only — `exercise_session`, one row per start-to-stop
     * bout, and `exercise_fix`, one row per accepted GPS fix on its track.
     *
     * No existing table is touched, and nothing already stored changes meaning. In particular the
     * `sample.exercise` column is untouched and is not backfilled: it has existed and been null since
     * v1, and null there means the magnitude was never recorded, which for every bucket predating
     * this is exactly true.
     *
     * No foreign key ties the two tables, by the decision recorded on [ExerciseFixEntity]. DDL
     * transcribed verbatim from the generated `schemas/<db>/16.json` (its `${TABLE_NAME}` placeholders
     * resolved) so the migrated DB is byte-identical to a fresh `createAllTables`.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_session` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clientId` TEXT NOT NULL, " +
                    "`startMs` INTEGER NOT NULL, `endMs` INTEGER, `tzOffsetMin` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `activeSec` INTEGER NOT NULL, `distanceM` REAL, " +
                    "`kcal` INTEGER, `interrupted` INTEGER NOT NULL, `note` TEXT, " +
                    "`updatedAt` INTEGER NOT NULL)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_session_clientId` " +
                    "ON `exercise_session` (`clientId`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_exercise_session_startMs` ON `exercise_session` (`startMs`)",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_fix` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                    "`tsMs` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, " +
                    "`accuracyM` REAL NOT NULL, `speedMps` REAL)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_exercise_fix_sessionId_tsMs` " +
                    "ON `exercise_fix` (`sessionId`, `tsMs`)",
            )
        }
    }

    /**
     * v16 → v17 (the exercise scalar changes unit): `sample.exercise` becomes REAL and every stored
     * value is dropped.
     *
     * The column carried whole active SECONDS per five-minute bucket, `0..300`. `invariants.md` §3
     * now fixes the quantity as grams of carbohydrate equivalent — glucose disposal expressed as the
     * carbohydrate it offsets — which §5's exercise gamma lays on the grid at an order of a couple of
     * grams per bucket. The two are two orders of magnitude apart and the column syncs, so a stored
     * `300` read as grams is not a rounding error but a hundredfold one, on a channel the model is
     * meant to consume.
     *
     * **The old values are not converted, because they cannot be.** A bucket's seconds say how long
     * the bout was open inside that five minutes; the disposal curve spreads a bout's whole magnitude
     * across its length and the ninety minutes after it, so no per-bucket function of the seconds
     * recovers the grams. NULL is the honest answer — "never recorded in this unit" — and it is what
     * the bouts still on record in `exercise_session` will produce again if anything ever backfills
     * them from their own durations.
     *
     * A table rebuild, following [MIGRATION_6_7]: SQLite cannot change a column's declared type, and
     * Room compares `TableInfo` exactly, so an INTEGER `exercise` under a `Double?` field is refused
     * at open. The new DDL is transcribed from the generated `schemas/<db>/17.json` (its
     * `${TABLE_NAME}` placeholder resolved) so the migrated table is byte-identical to a fresh
     * `createAllTables`. No index and no foreign key references `sample`, so the rebuild has nothing
     * else to restore.
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `sample_new` (`ts` INTEGER NOT NULL, " +
                    "`tzOffsetMin` INTEGER NOT NULL, `bgMgdl` INTEGER, `bgSource` TEXT, " +
                    "`bgProvenance` TEXT, `bgFlag` TEXT, `steps` INTEGER, `mood` INTEGER, " +
                    "`hr` INTEGER, `sleep` INTEGER, `exercise` REAL, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`ts`))",
            )
            // `exercise` is deliberately absent from both column lists: it defaults to NULL.
            connection.execSQL(
                "INSERT INTO `sample_new` " +
                    "(`ts`,`tzOffsetMin`,`bgMgdl`,`bgSource`,`bgProvenance`,`bgFlag`,`steps`,`mood`," +
                    "`hr`,`sleep`,`updatedAt`) " +
                    "SELECT `ts`,`tzOffsetMin`,`bgMgdl`,`bgSource`,`bgProvenance`,`bgFlag`,`steps`," +
                    "`mood`,`hr`,`sleep`,`updatedAt` FROM `sample`",
            )
            connection.execSQL("DROP TABLE `sample`")
            connection.execSQL("ALTER TABLE `sample_new` RENAME TO `sample`")
        }
    }

    /**
     * The `cgm_sample_raw` DDL, frozen. Held as constants rather than inline so
     * `MigrationConstantsTest` can hold them against [CgmRawSampleEntity]'s own field list: the
     * migration is the one description of this table that Room does not check, so a column added to
     * the entity and forgotten here upgrades an existing install into a schema Room rejects at open
     * — on a store with no destructive fallback, which is a launch crash rather than a lost row.
     */
    internal const val SQL_17_18_CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS `cgm_sample_raw` (" +
            "`sourceId` TEXT NOT NULL, `rxWallMs` INTEGER NOT NULL, `bgMgdl` INTEGER, " +
            "`trendTenthsPerMin` INTEGER, `minFromStart` INTEGER, `quality` INTEGER, " +
            "`flag` TEXT NOT NULL, `tzOffsetMin` INTEGER NOT NULL, `rssi` INTEGER, " +
            "PRIMARY KEY(`sourceId`, `rxWallMs`))"

    internal const val SQL_17_18_CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_cgm_sample_raw_rxWallMs` ON `cgm_sample_raw` (`rxWallMs`)"

    /**
     * v17 → v18 (the sub-grid sample record): additive only — `cgm_sample_raw`, one row per accepted
     * sample at its true receive instant.
     *
     * Nothing existing is touched and nothing is backfilled. A backfill is not merely unnecessary
     * here but impossible: `cgm_reading` holds the sample that WON each slot and has never held the
     * ones it displaced, so the history this table would want has already been discarded. The store
     * therefore begins empty and fills forward, and every reader of it must treat an absent row as
     * normal — which it also has to do anyway, because these rows expire (see
     * `T1dmRepository.RAW_SAMPLE_RETENTION_MS`).
     *
     * DDL transcribed verbatim from the generated `schemas/<db>/18.json` (its `${TABLE_NAME}`
     * placeholders resolved) so the migrated DB is byte-identical to a fresh `createAllTables`.
     */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_17_18_CREATE_TABLE)
            connection.execSQL(SQL_17_18_CREATE_INDEX)
        }
    }

    /**
     * The `cgm_sensor_secret` DDL, frozen, and held as a constant for the reason
     * [SQL_17_18_CREATE_TABLE] is: the migration is the one description of this table Room does not
     * check, and on a store with no destructive fallback a forgotten column is a launch crash rather
     * than a lost row.
     */
    internal const val SQL_18_19_CREATE_SECRET =
        "CREATE TABLE IF NOT EXISTS `cgm_sensor_secret` (" +
            "`sourceId` TEXT NOT NULL, `blob` BLOB NOT NULL, `updatedAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`sourceId`))"

    /**
     * The `cgm_source.ordinal` column. The DEFAULT is part of the schema Room compares, not a
     * convenience: `CgmSourceEntity` declares `@ColumnInfo(defaultValue = "-1")`, and a column added
     * without it would differ from the one a fresh install builds and Room would refuse to open the
     * upgraded database.
     */
    internal const val SQL_18_19_ADD_ORDINAL =
        "ALTER TABLE `cgm_source` ADD COLUMN `ordinal` INTEGER NOT NULL DEFAULT -1"

    /**
     * Number the rows already on record, zero-based.
     *
     * Counts how many rows sort before each one in `addedAtMs, sourceId` — the same order every list
     * query already uses, so the numbers a phone shows after upgrading are the order it has always
     * listed its sensors in. Deterministic and total: the tiebreak on `sourceId` means two rows sharing
     * an `addedAtMs` still get distinct numbers, and `<=` on the tied branch makes each row count itself
     * so the sequence starts at zero with no gaps.
     */
    internal const val SQL_18_19_BACKFILL_ORDINAL =
        "UPDATE `cgm_source` SET `ordinal` = (" +
            "SELECT COUNT(*) FROM `cgm_source` c2 WHERE c2.`addedAtMs` < `cgm_source`.`addedAtMs` " +
            "OR (c2.`addedAtMs` = `cgm_source`.`addedAtMs` AND c2.`sourceId` <= `cgm_source`.`sourceId`)" +
            ") - 1"

    /**
     * v18 → v19: the sealed per-sensor secret store, and a stable per-sensor ordinal.
     *
     * Both in ONE migration on purpose. There is no destructive fallback, so a DDL that disagrees with
     * an entity by one column is a launch crash on the user's daily-driver phone, and the chain
     * validator that would catch it is instrumented. One migration means one hand-written DDL, one
     * exported schema and one instrumented validation to arrange rather than two.
     *
     * `cgm_sensor_secret` begins empty and is never backfilled: it holds material established during a
     * pairing that has already happened, so nothing on this phone could reconstruct a row for a sensor
     * bound before the table existed. `ordinal` IS backfilled, because the order it records is one the
     * database already has.
     *
     * No sensor family is named anywhere in here. DDL transcribed verbatim from the generated
     * `schemas/<db>/19.json` (its `${'$'}{TABLE_NAME}` placeholder resolved) so the migrated database is
     * byte-identical to a fresh `createAllTables`.
     */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_18_19_CREATE_SECRET)
            connection.execSQL(SQL_18_19_ADD_ORDINAL)
            connection.execSQL(SQL_18_19_BACKFILL_ORDINAL)
        }
    }

    /**
     * The `lora` and `bg_infill` DDL, frozen, for the reason every other migration constant here is
     * frozen: the migration is the one description of these tables Room does not check, and on a
     * store with no destructive fallback a forgotten column is a launch crash rather than a lost row.
     */
    internal const val SQL_19_20_CREATE_LORA =
        "CREATE TABLE IF NOT EXISTS `lora` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `modelId` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `blob` BLOB NOT NULL, `rank` INTEGER NOT NULL, " +
            "`alpha` REAL NOT NULL, `targets` INTEGER NOT NULL, `nParams` INTEGER NOT NULL, " +
            "`nTrain` INTEGER NOT NULL, `nHoldout` INTEGER NOT NULL, `epochs` INTEGER NOT NULL, " +
            "`holdoutBefore` REAL NOT NULL, `holdoutAfter` REAL NOT NULL, " +
            "`improved` INTEGER NOT NULL, `attached` INTEGER NOT NULL, " +
            "`createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL)"

    internal const val SQL_19_20_CREATE_LORA_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_lora_modelId` ON `lora` (`modelId`)"

    internal const val SQL_19_20_CREATE_INFILL =
        "CREATE TABLE IF NOT EXISTS `bg_infill` (" +
            "`ts` INTEGER NOT NULL, `mgdl` REAL NOT NULL, `lo90` REAL NOT NULL, " +
            "`hi90` REAL NOT NULL, `modelId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`ts`))"

    /**
     * v19 → v20 (the adapter and the reconstructed sample): additive only. `lora` holds one fitted
     * personalisation per row; `bg_infill` holds what a model reconstructed over a sensor gap, in
     * its own table so nothing that reads glucose can mistake it for one.
     */
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_19_20_CREATE_LORA)
            connection.execSQL(SQL_19_20_CREATE_LORA_INDEX)
            connection.execSQL(SQL_19_20_CREATE_INFILL)
        }
    }

    internal const val SQL_20_21_DOSE_LOGGED_AT =
        "ALTER TABLE `logged_dose` ADD COLUMN `loggedAtMs` INTEGER NOT NULL DEFAULT 0"

    internal const val SQL_20_21_DOSE_LOGGED_AT_BACKFILL =
        "UPDATE `logged_dose` SET `loggedAtMs` = `updatedAt`"

    internal const val SQL_20_21_DOSE_MUTATED_AT =
        "ALTER TABLE `logged_dose` ADD COLUMN `mutatedAtMs` INTEGER"

    internal const val SQL_20_21_DOSE_MUTATED_ACTING =
        "ALTER TABLE `logged_dose` ADD COLUMN `mutatedActingUntilMs` INTEGER"

    internal const val SQL_20_21_MEAL_LOGGED_AT =
        "ALTER TABLE `logged_meal` ADD COLUMN `loggedAtMs` INTEGER NOT NULL DEFAULT 0"

    internal const val SQL_20_21_MEAL_LOGGED_AT_BACKFILL =
        "UPDATE `logged_meal` SET `loggedAtMs` = `updatedAt`"

    internal const val SQL_20_21_MEAL_MUTATED_AT =
        "ALTER TABLE `logged_meal` ADD COLUMN `mutatedAtMs` INTEGER"

    internal const val SQL_20_21_CREATE_TOMBSTONE =
        "CREATE TABLE IF NOT EXISTS `event_tombstone` (" +
            "`clientId` TEXT NOT NULL, `kind` TEXT NOT NULL, `tsMs` INTEGER NOT NULL, " +
            "`tzOffsetMin` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`createdAtMs` INTEGER NOT NULL, `pushEnqueuedAtMs` INTEGER, " +
            "`actingUntilMs` INTEGER, PRIMARY KEY(`clientId`))"

    internal const val SQL_20_21_CREATE_TOMBSTONE_TS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_event_tombstone_tsMs` ON `event_tombstone` (`tsMs`)"

    internal const val SQL_20_21_CREATE_TOMBSTONE_PUSH_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_event_tombstone_pushEnqueuedAtMs` " +
            "ON `event_tombstone` (`pushEnqueuedAtMs`)"

    /**
     * v20 → v21 (a logged event becomes editable and deletable): additive only.
     *
     * `loggedAtMs` is back-filled from `updatedAt`, which is the honest reading for a pre-v21 row:
     * nothing could edit one, so `updatedAt` was only ever set at insert. `mutatedAtMs` and
     * `mutatedActingUntilMs` stay null — no row has been edited yet, which is a fact about them
     * rather than an unknown.
     *
     * `event_tombstone` is what makes a deletion survive: the row it replaces is gone, so the
     * tombstone is the only thing left carrying the `updatedAt` a stale redelivery is rejected by,
     * the only local record hydration can refuse against, and the only term keeping the event
     * high-water mark from moving backward.
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_20_21_DOSE_LOGGED_AT)
            connection.execSQL(SQL_20_21_DOSE_LOGGED_AT_BACKFILL)
            connection.execSQL(SQL_20_21_DOSE_MUTATED_AT)
            connection.execSQL(SQL_20_21_DOSE_MUTATED_ACTING)
            connection.execSQL(SQL_20_21_MEAL_LOGGED_AT)
            connection.execSQL(SQL_20_21_MEAL_LOGGED_AT_BACKFILL)
            connection.execSQL(SQL_20_21_MEAL_MUTATED_AT)
            connection.execSQL(SQL_20_21_CREATE_TOMBSTONE)
            connection.execSQL(SQL_20_21_CREATE_TOMBSTONE_TS_INDEX)
            connection.execSQL(SQL_20_21_CREATE_TOMBSTONE_PUSH_INDEX)
        }
    }

    internal const val SQL_21_22_INFILL_SPAN =
        "ALTER TABLE `bg_infill` ADD COLUMN `spanStartMs` INTEGER NOT NULL DEFAULT 0"

    internal const val SQL_21_22_INFILL_PROMOTED =
        "ALTER TABLE `bg_infill` ADD COLUMN `promotedAtMs` INTEGER"

    internal const val SQL_21_22_BACKFILL_SPAN =
        "UPDATE `bg_infill` SET `spanStartMs` = `ts`"

    internal const val SQL_21_22_INFILL_SPAN_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_bg_infill_spanStartMs` ON `bg_infill` (`spanStartMs`)"

    /**
     * v21 → v22 (a reconstructed span becomes an identity, and can be promoted): additive only.
     *
     * The backfill makes every pre-v22 row its own one-step span. That is honest rather than
     * clever: the contiguous runs were never recorded, and reconstructing them with a
     * gap-and-island query inside a migration would invent an identity nothing ever authored. The
     * cost is a longer span list for old fills, and a fill is cheap to remake.
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_21_22_INFILL_SPAN)
            connection.execSQL(SQL_21_22_INFILL_PROMOTED)
            connection.execSQL(SQL_21_22_BACKFILL_SPAN)
            connection.execSQL(SQL_21_22_INFILL_SPAN_INDEX)
        }
    }

    internal const val SQL_22_23_LORA_GUARD_VERDICT =
        "ALTER TABLE `lora` ADD COLUMN `guardVerdict` TEXT NOT NULL DEFAULT 'ABSENT'"

    internal const val SQL_22_23_LORA_GUARD_WINDOWS =
        "ALTER TABLE `lora` ADD COLUMN `guardWindows` INTEGER NOT NULL DEFAULT 0"

    internal const val SQL_22_23_LORA_GUARD_FROZEN =
        "ALTER TABLE `lora` ADD COLUMN `guardFrozenMgdl` REAL NOT NULL DEFAULT 0"

    internal const val SQL_22_23_LORA_GUARD_ADAPTED =
        "ALTER TABLE `lora` ADD COLUMN `guardAdaptedMgdl` REAL NOT NULL DEFAULT 0"

    internal const val SQL_22_23_LORA_GUARD_RETENTION =
        "ALTER TABLE `lora` ADD COLUMN `guardRetention` REAL NOT NULL DEFAULT 0"

    internal const val SQL_22_23_LORA_GUARD_SIGN =
        "ALTER TABLE `lora` ADD COLUMN `guardSignAgreement` REAL NOT NULL DEFAULT 0"

    internal const val SQL_22_23_LORA_GUARD_WHY =
        "ALTER TABLE `lora` ADD COLUMN `guardWhy` TEXT NOT NULL DEFAULT ''"

    internal const val SQL_22_23_LORA_N_PAIRED =
        "ALTER TABLE `lora` ADD COLUMN `nPaired` INTEGER NOT NULL DEFAULT 0"

    internal const val SQL_22_23_LORA_DISTILL_SCALE =
        "ALTER TABLE `lora` ADD COLUMN `distillScale` REAL NOT NULL DEFAULT 0"

    internal const val SQL_22_23_LORA_GUARD_OVERRIDE =
        "ALTER TABLE `lora` ADD COLUMN `guardOverrideAtMs` INTEGER"

    internal const val SQL_22_23_LORA_HISTORY_MUTATED =
        "ALTER TABLE `lora` ADD COLUMN `historyMutatedAtMs` INTEGER"

    internal const val SQL_22_23_LORA_FITTED_AT =
        "ALTER TABLE `lora` ADD COLUMN `fittedAtMs` INTEGER NOT NULL DEFAULT 0"

    /**
     * v22 → v23 (an adapter carries the verdict on its own dose response): additive only.
     *
     * `guardVerdict` back-fills to `ABSENT`, which is the honest state for a row nothing measured
     * — and the state that REFUSES attach. Silence is not a pass: an adapter that arrived by
     * import or by an archive restore has no verdict either, and both routes end here.
     *
     * `historyMutatedAtMs` lives with the guard's own columns rather than with the log-mutation
     * work that motivated it, because both are inputs to one predicate: an adapter is refused when
     * it was never measured, when it was measured and blocked, or when the history it was fitted
     * on has since been rewritten.
     */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_22_23_LORA_GUARD_VERDICT)
            connection.execSQL(SQL_22_23_LORA_GUARD_WINDOWS)
            connection.execSQL(SQL_22_23_LORA_GUARD_FROZEN)
            connection.execSQL(SQL_22_23_LORA_GUARD_ADAPTED)
            connection.execSQL(SQL_22_23_LORA_GUARD_RETENTION)
            connection.execSQL(SQL_22_23_LORA_GUARD_SIGN)
            connection.execSQL(SQL_22_23_LORA_GUARD_WHY)
            connection.execSQL(SQL_22_23_LORA_N_PAIRED)
            connection.execSQL(SQL_22_23_LORA_DISTILL_SCALE)
            connection.execSQL(SQL_22_23_LORA_GUARD_OVERRIDE)
            connection.execSQL(SQL_22_23_LORA_HISTORY_MUTATED)
            connection.execSQL(SQL_22_23_LORA_FITTED_AT)
        }
    }

    internal const val SQL_23_24_INFILL_BANDS_MGDL =
        "ALTER TABLE `bg_infill` ADD COLUMN `bandsMgdl` BLOB NOT NULL DEFAULT x''"

    internal const val SQL_23_24_INFILL_BANDS_RISK =
        "ALTER TABLE `bg_infill` ADD COLUMN `bandsRisk` BLOB NOT NULL DEFAULT x''"

    internal const val SQL_23_24_INFILL_TAU =
        "ALTER TABLE `bg_infill` ADD COLUMN `tau` REAL NOT NULL DEFAULT 0.5"

    internal const val SQL_24_25_PREDICTION_SOURCE =
        "ALTER TABLE `prediction` ADD COLUMN `sourceId` TEXT DEFAULT NULL"

    /** Every pre-v25 forecast, discarded: none of them records which sensor conditioned it. */
    internal const val SQL_24_25_DROP_UNATTRIBUTED = "DELETE FROM `prediction`"

    /**
     * v23 → v24 (a reconstructed span keeps its whole fan, and the τ its line was read at):
     * additive only.
     *
     * The two blobs back-fill EMPTY rather than to a fan synthesised from `lo90`/`hi90`. A pre-v24
     * row records two edges and nothing between them; manufacturing the five interior levels would
     * put a shape on the panel that no model ever emitted, and would then be indistinguishable
     * from one that did. An empty fan draws as the single band it actually is and refuses to move
     * its τ — which is why `tau` back-fills to `0.5`: every row written before this migration is
     * the median, and saying so is what keeps a promoted pre-v24 value honest.
     */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_23_24_INFILL_BANDS_MGDL)
            connection.execSQL(SQL_23_24_INFILL_BANDS_RISK)
            connection.execSQL(SQL_23_24_INFILL_TAU)
        }
    }

    /**
     * v24 → v25 (a forecast records the sensor that conditioned it): one nullable column, and every
     * existing row deleted.
     *
     * The column exists so [com.t1dm.data.T1dmRepository]'s maturation walk can refuse a window
     * whose forecast came from one sensor and whose truth comes from another. Two sensors worn at
     * once disagree — 28 mg/dL median between two of this patient's — and a swap silently turned
     * every older window into a measurement of that gap, which the §8.4 fit then pushed into the
     * displayed band.
     *
     * The DELETE is the point, not housekeeping. A pre-v25 row cannot be attributed: the sensor
     * that conditioned it is not recorded anywhere, and it cannot be inferred from the row. Left in
     * place as NULL they would simply be refused by the walk for as long as they were retained,
     * carrying blobs for a purpose nothing can serve. Backfilling them with the CURRENT
     * authoritative source would be worse than deleting them — it would assert the very
     * cross-sensor attribution this column exists to prevent, and do it invisibly.
     *
     * What it costs: the realised-accuracy tables, CG-EGA and the band fit all read these rows, so
     * every model's history restarts here. A calibration already fitted from them survives in
     * `conformal_delta` and is not touched — one that was fitted across a sensor swap is dropped
     * from the model's own screen, by hand, because only the user knows whether it was.
     */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_24_25_PREDICTION_SOURCE)
            connection.execSQL(SQL_24_25_DROP_UNATTRIBUTED)
        }
    }

    internal const val SQL_25_26_DELTA_SOURCE =
        "ALTER TABLE `conformal_delta` ADD COLUMN `sourceId` TEXT DEFAULT NULL"

    /**
     * v25 → v26 (a band correction records the sensor it was fitted for): additive only.
     *
     * No DELETE, unlike [MIGRATION_24_25]. A correction with no source is refused by the apply — the
     * fan draws raw — but the row still says what the last fit bought and when, which the drill-down
     * needs in order to explain why nothing is being applied. A deleted row would leave that screen
     * unable to distinguish "never fitted" from "fitted, and no longer trusted".
     */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_25_26_DELTA_SOURCE)
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
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
    )

    /** Apply every registered migration to a builder; the sole path that wires migrations. */
    fun <T : RoomDatabase> configure(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder.addMigrations(*ALL)
    // NOTE: no `.fallbackToDestructiveMigration(...)` — a missing migration must fail loudly,
    // never silently discard the user's keep-forever history.
}
