package com.t1dm.data.db

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.legacySensorModelIdFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A migration may not read a Kotlin constant a later rename could move, and its hand-written DDL is
 * checked nowhere until an upgrade runs on a device — a launch crash, with no destructive fallback.
 * These read `MigrationRunner`'s own statements, never a transcription.
 */
class MigrationConstantsTest {

    /** One exported schema, whichever directory the suite runs from. */
    private fun schemaText(version: Int): String {
        val schema = File("schemas/com.t1dm.data.db.AppDatabase/$version.json")
            .takeIf { it.exists() }
            ?: File("data/schemas/com.t1dm.data.db.AppDatabase/$version.json")
        assertTrue("exported schema $version.json is missing: ${schema.absolutePath}", schema.exists())
        return schema.readText()
    }

    private fun createSqlOf(text: String, table: String): String {
        val at = text.indexOf("\"tableName\": \"$table\"")
        assertTrue("entity $table is absent from the exported schema", at > 0)
        val key = "\"createSql\": \""
        val from = text.indexOf(key, at) + key.length
        val to = text.indexOf("\",", from)
        return text.substring(from, to)
            .replace("\\\"", "\"")
            .replace("\${TABLE_NAME}", table)
    }

    @Test
    fun `the v20 migration DDL is exactly what Room generates`() {
        val text = schemaText(20)
        fun createSql(table: String) = createSqlOf(text, table)

        assertEquals(createSql("lora"), MigrationRunner.SQL_19_20_CREATE_LORA)
        assertEquals(createSql("bg_infill"), MigrationRunner.SQL_19_20_CREATE_INFILL)
        // Room stores the index with the table placeholder unresolved, so put it back.
        val index = MigrationRunner.SQL_19_20_CREATE_LORA_INDEX
            .replace("`lora`", "`\${TABLE_NAME}`")
            .replace("\"", "\\\"")
        assertTrue("the lora index DDL is not the one Room expects: $index", text.contains(index))
    }

    /**
     * A Kotlin default governs the INSERT and says nothing about the DDL, so an additive migration can
     * build a different table from a fresh install's. Read from the migration's own SQL by reflection.
     */
    @Test
    fun `every column the additive migrations add is in the exported schema`() {
        val text = schemaText(23)
        val adds = MigrationRunner::class.java.declaredFields
            .filter { it.name.startsWith("SQL_20_21_") || it.name.startsWith("SQL_21_22_") || it.name.startsWith("SQL_22_23_") }
            .mapNotNull { f -> f.also { it.isAccessible = true }.get(MigrationRunner) as? String }
            .filter { it.contains("ADD COLUMN") }
        assertTrue("no ADD COLUMN migrations were found by reflection", adds.size >= 15)

        val re = Regex("ALTER TABLE `(\\w+)` ADD COLUMN (.+)$")
        for (sql in adds) {
            val m = re.find(sql.trim()) ?: error("unparsed additive migration: $sql")
            val (table, column) = m.destructured
            val create = createSqlOf(text, table)
            assertTrue(
                "`$table` in schema 23 lacks the column v21-23 adds — migration: $column | createSql: $create",
                create.contains(column.trim()),
            )
        }
    }

    @Test
    fun `the real-sensor backfill writes exactly CgmSensorModelId AIDEX_X`() {
        assertTrue(
            "MIGRATION_10_11 no longer backfills ${CgmSensorModelId.AIDEX_X}: " +
                MigrationRunner.SQL_10_11_BACKFILL_REAL,
            MigrationRunner.SQL_10_11_BACKFILL_REAL.contains("'${CgmSensorModelId.AIDEX_X}'"),
        )
    }

    @Test
    fun `the debug backfill matches both the debug source id and the debug class`() {
        assertTrue(
            "MIGRATION_10_11 no longer recognises ${CgmSourceId.DEBUG.value}: " +
                MigrationRunner.SQL_10_11_BACKFILL_DEBUG,
            MigrationRunner.SQL_10_11_BACKFILL_DEBUG.contains("'${CgmSourceId.DEBUG.value}'"),
        )
        assertTrue(
            "MIGRATION_10_11 no longer backfills ${CgmSensorModelId.AIDEX_DEBUG}: " +
                MigrationRunner.SQL_10_11_BACKFILL_DEBUG,
            MigrationRunner.SQL_10_11_BACKFILL_DEBUG.contains("'${CgmSensorModelId.AIDEX_DEBUG}'"),
        )
    }

    @Test
    fun `the debug class is distinct, so injected readings stay off the real trace`() {
        assertTrue(
            "the debug source would share the real sensor's history",
            CgmSensorModelId.AIDEX_DEBUG != CgmSensorModelId.AIDEX_X,
        )
    }

    /** Different code paths: SQL in the migration, Kotlin in the archive reader. */
    @Test
    fun `the archive fallback agrees with the migration backfill`() {
        assertEquals(CgmSensorModelId.AIDEX_DEBUG, legacySensorModelIdFor(CgmSourceId.DEBUG.value))
        assertEquals(CgmSensorModelId.AIDEX_X, legacySensorModelIdFor("aidexx:22222C74D9"))

        assertTrue(
            "the migration would classify the debug source differently from a restore",
            MigrationRunner.SQL_10_11_BACKFILL_DEBUG
                .contains("'${legacySensorModelIdFor(CgmSourceId.DEBUG.value)}'"),
        )
        assertTrue(
            "the migration would classify a real sensor differently from a restore",
            MigrationRunner.SQL_10_11_BACKFILL_REAL
                .contains("'${legacySensorModelIdFor("aidexx:22222C74D9")}'"),
        )
    }

    @Test
    fun `the raw-sample DDL declares exactly the entity's columns`() {
        val declared = BACKTICKED.findAll(MigrationRunner.SQL_17_18_CREATE_TABLE)
            .map { it.groupValues[1] }
            .toSet() - RAW_TABLE
        val fields = CgmRawSampleEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith('$') }
            .map { it.name }
            .toSet()
        assertEquals(
            "MIGRATION_17_18's DDL and CgmRawSampleEntity disagree about the columns: " +
                MigrationRunner.SQL_17_18_CREATE_TABLE,
            fields,
            declared,
        )
    }

    @Test
    fun `the raw-sample DDL names the table and index Room will look for`() {
        assertTrue(
            "the migration builds a table Room is not looking for: " +
                MigrationRunner.SQL_17_18_CREATE_TABLE,
            MigrationRunner.SQL_17_18_CREATE_TABLE.contains("`$RAW_TABLE`"),
        )
        assertTrue(
            "Room derives the index name from the entity; the migration must use the same one: " +
                MigrationRunner.SQL_17_18_CREATE_INDEX,
            MigrationRunner.SQL_17_18_CREATE_INDEX.contains("`index_${RAW_TABLE}_rxWallMs`"),
        )
        assertTrue(
            "the index must be built on the column the retention sweep filters by",
            MigrationRunner.SQL_17_18_CREATE_INDEX.contains("ON `$RAW_TABLE` (`rxWallMs`)"),
        )
    }

    @Test
    fun `the raw-sample DDL is idempotent`() {
        assertTrue(MigrationRunner.SQL_17_18_CREATE_TABLE.startsWith("CREATE TABLE IF NOT EXISTS "))
        assertTrue(MigrationRunner.SQL_17_18_CREATE_INDEX.startsWith("CREATE INDEX IF NOT EXISTS "))
    }

    @Test
    fun `the sensor-secret DDL declares exactly the entity's columns`() {
        val declared = BACKTICKED.findAll(MigrationRunner.SQL_18_19_CREATE_SECRET)
            .map { it.groupValues[1] }
            .toSet() - SECRET_TABLE
        val fields = CgmSensorSecretEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith('$') }
            .map { it.name }
            .toSet()
        assertEquals(
            "MIGRATION_18_19's DDL and CgmSensorSecretEntity disagree about the columns: " +
                MigrationRunner.SQL_18_19_CREATE_SECRET,
            fields,
            declared,
        )
    }

    @Test
    fun `the sensor-secret DDL names the table Room will look for and is idempotent`() {
        assertTrue(
            "the migration builds a table Room is not looking for: " +
                MigrationRunner.SQL_18_19_CREATE_SECRET,
            MigrationRunner.SQL_18_19_CREATE_SECRET.contains("`$SECRET_TABLE`"),
        )
        assertTrue(MigrationRunner.SQL_18_19_CREATE_SECRET.startsWith("CREATE TABLE IF NOT EXISTS "))
        assertTrue(
            "the secret is keyed per source",
            MigrationRunner.SQL_18_19_CREATE_SECRET.contains("PRIMARY KEY(`sourceId`)"),
        )
    }

    /** Room compares column defaults, so the ALTER must carry the one the entity declares. */
    @Test
    fun `the ordinal column matches what the entity declares, default included`() {
        val alter = MigrationRunner.SQL_18_19_ADD_ORDINAL
        assertTrue("the entity must declare this column: $alter", "ordinal" in ENTITY_FIELDS)
        assertTrue(alter, alter.contains("ALTER TABLE `cgm_source` ADD COLUMN `ordinal`"))
        assertTrue("the type Room generates for an Int: $alter", alter.contains("INTEGER NOT NULL"))
        assertTrue(
            "the default must match @ColumnInfo(defaultValue = \"-1\"): $alter",
            alter.contains("DEFAULT -1"),
        )
        assertTrue(
            "the SQL default and CgmSourceDescriptor.UNASSIGNED_ORDINAL must be the same value: $alter",
            alter.contains("DEFAULT ${com.t1dm.core.model.CgmSourceDescriptor.UNASSIGNED_ORDINAL}"),
        )
    }

    /**
     * The `sourceId` tiebreak stops two rows sharing a number; `<=` on that branch is what makes the
     * sequence start at zero. Both are easy to write the other way round.
     */
    @Test
    fun `the ordinal backfill numbers every row once, from zero, in list order`() {
        val rows = listOf(
            "v1:B" to 100L,
            "v1:C" to 100L, // a tie, broken on sourceId
            "v2:A" to 50L,  // a second vendor, and the oldest row
            "v1:D" to 300L,
        )
        // The statement's own arithmetic, in Kotlin.
        val numbered = rows.associate { (id, added) ->
            id to rows.count { (id2, added2) -> added2 < added || (added2 == added && id2 <= id) } - 1
        }
        assertEquals(listOf(0, 1, 2, 3), numbered.values.sorted())
        assertEquals(0, numbered["v2:A"])
        assertEquals(1, numbered["v1:B"])
        assertEquals(2, numbered["v1:C"])
        assertEquals(3, numbered["v1:D"])

        val sql = MigrationRunner.SQL_18_19_BACKFILL_ORDINAL
        assertTrue("must number `cgm_source`.`ordinal`: $sql", sql.contains("SET `ordinal` ="))
        assertTrue("the tiebreak keeps two rows from sharing a number: $sql", sql.contains("`sourceId` <="))
        assertTrue("zero-based: $sql", sql.trimEnd().endsWith("- 1"))
    }

    /**
     * An allowlist, not a denylist: a denylist would have to spell every vendor's name in a file
     * shared verbatim with the public branch.
     */
    @Test
    fun `the v19 statements name only tables and columns`() {
        val statements = listOf(
            MigrationRunner.SQL_18_19_CREATE_SECRET,
            MigrationRunner.SQL_18_19_ADD_ORDINAL,
            MigrationRunner.SQL_18_19_BACKFILL_ORDINAL,
        )
        val allowed = setOf(
            SECRET_TABLE, "sourceId", "blob", "updatedAtMs",
            "cgm_source", "ordinal", "addedAtMs",
        )
        for (sql in statements) {
            val named = BACKTICKED.findAll(sql).map { it.groupValues[1] }.toSet()
            assertTrue("$sql names something that is not a table or column: ${named - allowed}", (named - allowed).isEmpty())
        }
    }

    /**
     * `loggedAtMs` takes `updatedAt`: a backfill to 0 would put every existing dose's log-gap mark at
     * the epoch and make `Rails.mandatoryConfirmation` fire forever.
     */
    @Test
    fun `the v21 statements add columns and back-fill loggedAtMs from updatedAt`() {
        for (sql in listOf(
            MigrationRunner.SQL_20_21_DOSE_LOGGED_AT,
            MigrationRunner.SQL_20_21_MEAL_LOGGED_AT,
        )) {
            assertTrue("must be additive: $sql", sql.contains("ADD COLUMN"))
            assertTrue("must default so existing rows are legal: $sql", sql.contains("DEFAULT 0"))
        }
        for (sql in listOf(
            MigrationRunner.SQL_20_21_DOSE_LOGGED_AT_BACKFILL,
            MigrationRunner.SQL_20_21_MEAL_LOGGED_AT_BACKFILL,
        )) {
            assertTrue("must back-fill from updatedAt, not from 0: $sql", sql.contains("= `updatedAt`"))
        }
        for (sql in listOf(
            MigrationRunner.SQL_20_21_DOSE_MUTATED_AT,
            MigrationRunner.SQL_20_21_DOSE_MUTATED_ACTING,
            MigrationRunner.SQL_20_21_MEAL_MUTATED_AT,
        )) {
            assertTrue("nullable — no row has been edited: $sql", !sql.contains("NOT NULL"))
        }
        val create = MigrationRunner.SQL_20_21_CREATE_TOMBSTONE
        assertTrue(create, create.contains("`event_tombstone`"))
        assertTrue("clientId is the key a redelivery is matched on", create.contains("PRIMARY KEY(`clientId`)"))
        assertTrue("the ordering stamp must be present", create.contains("`updatedAt` INTEGER NOT NULL"))
        assertTrue("the rail reads this after the dose row is gone", create.contains("`actingUntilMs`"))
    }

    /**
     * `ABSENT` means nobody has checked, and must block like a checked-and-failed adapter; a `'PASS'`
     * default would silently attach every adapter that predates the guard.
     */
    @Test
    fun `the v23 verdict back-fills to the state that refuses attach`() {
        val sql = MigrationRunner.SQL_22_23_LORA_GUARD_VERDICT
        assertTrue("must be additive: $sql", sql.contains("ADD COLUMN"))
        assertTrue("an unmeasured adapter must default to ABSENT: $sql", sql.contains("DEFAULT 'ABSENT'"))
        assertFalse("never PASS: $sql", sql.contains("PASS"))

        // Nullable: a defaulted zero would read as "overridden at the epoch".
        for (sql in listOf(
            MigrationRunner.SQL_22_23_LORA_GUARD_OVERRIDE,
            MigrationRunner.SQL_22_23_LORA_HISTORY_MUTATED,
        )) {
            assertTrue("must be nullable: $sql", !sql.contains("NOT NULL"))
        }
    }

    @Test
    fun `the v27 replay-table DDL is exactly what Room generates`() {
        val text = schemaText(27)
        assertEquals(createSqlOf(text, REPLAY_TABLE), MigrationRunner.SQL_26_27_LOGGED_EXERCISE)
        // Room stores an index with the table placeholder unresolved, so put it back.
        for (sql in listOf(
            MigrationRunner.SQL_26_27_LOGGED_EXERCISE_TS,
            MigrationRunner.SQL_26_27_LOGGED_EXERCISE_CLIENT_ID,
        )) {
            val index = sql.replace("`$REPLAY_TABLE`", "`\${TABLE_NAME}`").replace("\"", "\\\"")
            assertTrue("an index DDL Room is not looking for: $index", text.contains(index))
        }
    }

    /** The unwind re-derives a replay's curve from the row, so the shape columns must be NOT NULL. */
    @Test
    fun `the replay DDL declares exactly the entity's columns, curve shape included`() {
        val declared = BACKTICKED.findAll(MigrationRunner.SQL_26_27_LOGGED_EXERCISE)
            .map { it.groupValues[1] }
            .toSet() - REPLAY_TABLE
        val fields = LoggedExerciseEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith('$') }
            .map { it.name }
            .toSet()
        assertEquals(fields, declared)
        for (column in listOf("grams", "k", "theta", "curveDurationMin")) {
            assertTrue(
                "`$column` must be NOT NULL or a delete cannot reproduce what the row laid down",
                MigrationRunner.SQL_26_27_LOGGED_EXERCISE.contains("`$column` REAL NOT NULL"),
            )
        }
    }

    private companion object {
        const val RAW_TABLE = "cgm_sample_raw"
        const val SECRET_TABLE = "cgm_sensor_secret"
        const val REPLAY_TABLE = "logged_exercise"
        val BACKTICKED = Regex("`([A-Za-z_][A-Za-z0-9_]*)`")

        val ENTITY_FIELDS: Set<String> = CgmSourceEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith('$') }
            .map { it.name }
            .toSet()
    }
}
