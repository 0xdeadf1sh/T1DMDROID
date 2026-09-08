package com.t1dm.data.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.t1dm.data.meals.FoodSeed
import java.util.UUID

/** Append-only DDL, never destructive except MIGRATION_8_9; from schemas/<db>/n.json verbatim. */
object MigrationRunner {

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

    /** note table dropped by MIGRATION_8_9; kept so the chain from v3 still reaches v9 via v4. */
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

    /** FTS5 table and triggers from shared FoodFts.DDL, same as a fresh install's onCreate. */
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
            connection.execSQL("ALTER TABLE `logged_dose` ADD COLUMN `customCurve` BLOB")
            FoodFts.create(connection)
        }
    }

    /** Data-only: folds grown FoodSeed catalogue in, matched on name+brand over custom=0 rows. */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            val ts = System.currentTimeMillis()
            // Numbered params reuse name/brand in the subquery; brand IS ?2 is null-safe equality.
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

    /** Legacy rows get a fresh UUID BEFORE the UNIQUE index builds, or two collide on ''. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            addClientId(connection, "logged_meal")
            addClientId(connection, "logged_dose")
            // Rebuild, not ALTER TABLE DROP COLUMN: driver mishandles it, Room rejects extras.
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

    /** Sole subtractive migration: DELETE stops NOTE rows throwing on valueOf every drain. */
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX IF EXISTS `index_note_tsMs`")
            connection.execSQL("DROP TABLE IF EXISTS `note`")
            connection.execSQL("DELETE FROM `outbox` WHERE `kind` = 'NOTE'")
        }
    }

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

    /** Frozen literals: a migration must never read a constant a later rename could move. */
    internal const val SQL_10_11_BACKFILL_DEBUG =
        "UPDATE `cgm_source` SET `sensorModelId` = 'aidexx:debug' " +
            "WHERE `sensorModelId` = '' AND `sourceId` = 'aidexx:DEBUG'"

    internal const val SQL_10_11_BACKFILL_REAL =
        "UPDATE `cgm_source` SET `sensorModelId` = 'aidexx:x' WHERE `sensorModelId` = ''"

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE `cgm_source` ADD COLUMN `sensorModelId` TEXT NOT NULL DEFAULT ''",
            )
            // Debug first: the second statement claims every remaining unclassified row.
            connection.execSQL(SQL_10_11_BACKFILL_DEBUG)
            connection.execSQL(SQL_10_11_BACKFILL_REAL)
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_cgm_source_sensorModelId` ON `cgm_source` (`sensorModelId`)",
            )
        }
    }

    /** advertName stays null on existing rows: the name was discarded, backfill would invent it. */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `cgm_source` ADD COLUMN `advertName` TEXT")
        }
    }

    /** Display flag, not a delete: dropping erases that sensor's trace, orphans cgm_reading. */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `cgm_source` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Old active meant 'the one believed'; RENAMED to authoritative, new active seeded. */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `cgm_source` RENAME COLUMN `active` TO `authoritative`")
            connection.execSQL("ALTER TABLE `cgm_source` ADD COLUMN `active` INTEGER NOT NULL DEFAULT 0")
            // authoritative ⇒ active. The other way would open a link to every sensor ever met.
            connection.execSQL("UPDATE `cgm_source` SET `active` = `authoritative`")
        }
    }

    /** Null-defaulted, not backfilled: a pre-v15 row has no sensor record to distinguish it by. */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `sample` ADD COLUMN `bgSource` TEXT")
        }
    }

    /** No foreign key ties the two tables, by the decision recorded on [ExerciseFixEntity]. */
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

    /** sample.exercise becomes REAL, values dropped: held active SECONDS, §3 now fixes grams. */
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

    /** Constants for MigrationConstantsTest vs CgmRawSampleEntity; Room doesn't check DDL. */
    internal const val SQL_17_18_CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS `cgm_sample_raw` (" +
            "`sourceId` TEXT NOT NULL, `rxWallMs` INTEGER NOT NULL, `bgMgdl` INTEGER, " +
            "`trendTenthsPerMin` INTEGER, `minFromStart` INTEGER, `quality` INTEGER, " +
            "`flag` TEXT NOT NULL, `tzOffsetMin` INTEGER NOT NULL, `rssi` INTEGER, " +
            "PRIMARY KEY(`sourceId`, `rxWallMs`))"

    internal const val SQL_17_18_CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_cgm_sample_raw_rxWallMs` ON `cgm_sample_raw` (`rxWallMs`)"

    /** Begins empty, fills forward; rows also expire, so readers must treat absence as normal. */
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_17_18_CREATE_TABLE)
            connection.execSQL(SQL_17_18_CREATE_INDEX)
        }
    }

    /** Frozen, for the reason [SQL_17_18_CREATE_TABLE] is. */
    internal const val SQL_18_19_CREATE_SECRET =
        "CREATE TABLE IF NOT EXISTS `cgm_sensor_secret` (" +
            "`sourceId` TEXT NOT NULL, `blob` BLOB NOT NULL, `updatedAtMs` INTEGER NOT NULL, " +
            "PRIMARY KEY(`sourceId`))"

    /** DEFAULT is part of the schema Room compares against CgmSourceEntity's @ColumnInfo. */
    internal const val SQL_18_19_ADD_ORDINAL =
        "ALTER TABLE `cgm_source` ADD COLUMN `ordinal` INTEGER NOT NULL DEFAULT -1"

    /** Zero-based rank in addedAtMs,sourceId order; tiebreak's <= counts each row once, no gaps. */
    internal const val SQL_18_19_BACKFILL_ORDINAL =
        "UPDATE `cgm_source` SET `ordinal` = (" +
            "SELECT COUNT(*) FROM `cgm_source` c2 WHERE c2.`addedAtMs` < `cgm_source`.`addedAtMs` " +
            "OR (c2.`addedAtMs` = `cgm_source`.`addedAtMs` AND c2.`sourceId` <= `cgm_source`.`sourceId`)" +
            ") - 1"

    /** cgm_sensor_secret begins empty, never backfilled; ordinal IS, from a known order. */
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_18_19_CREATE_SECRET)
            connection.execSQL(SQL_18_19_ADD_ORDINAL)
            connection.execSQL(SQL_18_19_BACKFILL_ORDINAL)
        }
    }

    /** Frozen, for the reason [SQL_17_18_CREATE_TABLE] is. */
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

    /** loggedAtMs backfills from updatedAt (set at insert pre-v21); mutation columns stay null. */
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

    /** Backfill makes every pre-v22 row its own span: runs weren't recorded, nothing to invent. */
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

    /** guardVerdict backfills ABSENT (REFUSES attach); import/restore adapters get no verdict. */
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

    /** Blobs backfill EMPTY, not synthesised from lo90/hi90; tau backfills to 0.5, the median. */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_23_24_INFILL_BANDS_MGDL)
            connection.execSQL(SQL_23_24_INFILL_BANDS_RISK)
            connection.execSQL(SQL_23_24_INFILL_TAU)
        }
    }

    /** DELETE is the point: a pre-v25 row can't be attributed, so backfilling would fake it. */
    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_24_25_PREDICTION_SOURCE)
            connection.execSQL(SQL_24_25_DROP_UNATTRIBUTED)
        }
    }

    internal const val SQL_25_26_DELTA_SOURCE =
        "ALTER TABLE `conformal_delta` ADD COLUMN `sourceId` TEXT DEFAULT NULL"

    /** No DELETE unlike MIGRATION_24_25: a sourceless correction is refused, still says the fit. */
    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_25_26_DELTA_SOURCE)
        }
    }

    internal const val SQL_26_27_LOGGED_EXERCISE =
        "CREATE TABLE IF NOT EXISTS `logged_exercise` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clientId` TEXT NOT NULL, " +
            "`tsMs` INTEGER NOT NULL, `tzOffsetMin` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
            "`durationMin` REAL NOT NULL, `grams` REAL NOT NULL, `k` REAL NOT NULL, " +
            "`theta` REAL NOT NULL, `curveDurationMin` REAL NOT NULL, `sourceSessionId` INTEGER, " +
            "`updatedAt` INTEGER NOT NULL, `loggedAtMs` INTEGER NOT NULL DEFAULT 0, " +
            "`mutatedAtMs` INTEGER)"

    internal const val SQL_26_27_LOGGED_EXERCISE_TS =
        "CREATE INDEX IF NOT EXISTS `index_logged_exercise_tsMs` ON `logged_exercise` (`tsMs`)"

    internal const val SQL_26_27_LOGGED_EXERCISE_CLIENT_ID =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_logged_exercise_clientId` " +
            "ON `logged_exercise` (`clientId`)"

    /** No backfill from exercise_session: those bouts' grams are already in sample.exercise. */
    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(SQL_26_27_LOGGED_EXERCISE)
            connection.execSQL(SQL_26_27_LOGGED_EXERCISE_TS)
            connection.execSQL(SQL_26_27_LOGGED_EXERCISE_CLIENT_ID)
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
        MIGRATION_26_27,
    )

    fun <T : RoomDatabase> configure(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder.addMigrations(*ALL)
    // No fallbackToDestructiveMigration: a missing migration must fail loudly, not discard history.
}
