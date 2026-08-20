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
 * Holds the migrations' frozen SQL against the Kotlin the running app uses.
 *
 * Two things are pinned here, for the same reason in two shapes: a migration describes what the
 * schema became at a fixed point, so it cannot read a constant a later edit could move underneath
 * it, and nothing else notices when the two drift.
 *
 *  - `MIGRATION_10_11`'s sensor-model literals against [CgmSensorModelId] / [CgmSourceId].
 *  - `MIGRATION_17_18`'s `cgm_sample_raw` DDL against [CgmRawSampleEntity]'s own field list. Room
 *    checks the entity against the schema it generates, and it checks the migrated database against
 *    that schema at open — but nothing checks the migration's hand-written DDL until an upgrade runs
 *    on a real install, where a mismatch is a launch crash rather than a lost row (there is no
 *    destructive fallback).
 *
 * A migration may not read [CgmSensorModelId] — it describes what the schema became at a fixed point, and
 * a later rename there would silently rewrite history for every device that upgrades afterwards. So
 * the model ids appear in the migration as literal strings, and this is what stops the two drifting:
 * rename [CgmSensorModelId.AIDEX_X] without amending the SQL and stored sensors would be backfilled into
 * one class while every newly discovered sensor joined another, splitting one history in two on the
 * BG panel with nothing failing anywhere.
 *
 * These read [MigrationRunner]'s own statement strings rather than a transcription of them —
 * a copy of the SQL here would pass happily while the migration said something else.
 *
 * Nothing runs this on its own: it is reached only by `:data:testDebugUnitTest`, invoked by hand.
 * Run it after touching either side.
 */
class MigrationConstantsTest {

    /**
     * The v19→v20 DDL against the schema Room itself generated for those entities.
     *
     * Room checks the ENTITY against its generated schema, and it checks a MIGRATED database
     * against that schema at open — but nothing checks the hand-written migration SQL until an
     * upgrade runs on a real install, where a one-column drift is a launch crash rather than a lost
     * row (there is no destructive fallback). The exported schema JSON is tracked, so that check
     * can happen here, on every build, instead of on the phone.
     */
    /** One exported schema, whichever directory the suite runs from. */
    private fun schemaText(version: Int): String {
        val schema = File("schemas/com.t1dm.data.db.AppDatabase/$version.json")
            .takeIf { it.exists() }
            ?: File("data/schemas/com.t1dm.data.db.AppDatabase/$version.json")
        assertTrue("exported schema $version.json is missing: ${schema.absolutePath}", schema.exists())
        return schema.readText()
    }

    /** One entity's own `createSql`, with Room's placeholder resolved. */
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
        // The index carries its own name into the schema, so a rename here is a mismatch there.
        // Room stores it with the table placeholder unresolved, so put the placeholder back rather
        // than resolving the schema's copy.
        val index = MigrationRunner.SQL_19_20_CREATE_LORA_INDEX
            .replace("`lora`", "`\${TABLE_NAME}`")
            .replace("\"", "\\\"")
        assertTrue("the lora index DDL is not the one Room expects: $index", text.contains(index))
    }

    /**
     * Every column v21, v22 and v23 ADD, against the schema Room generates for a FRESH install.
     *
     * The v20 check above compares whole `CREATE TABLE`s, which an additive migration has none of.
     * That left the three additive versions spot-checked by substring, and it is exactly the additive
     * case that drifts: a Kotlin default governs the INSERT and says nothing about the DDL, so an
     * `ALTER TABLE … DEFAULT 0` on an upgrade against a column declared without `@ColumnInfo`
     * produces two different tables for one schema version. Room reports that as a mismatch on the
     * first open of whichever install it did not generate the schema from.
     *
     * The check reads each migration's own SQL rather than a list written out here: a column added
     * to the migration and not to the entity fails without anything being added to this test.
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

    /**
     * An upgrade in place and a restore from a pre-`modelId` archive must put the same sensor in the
     * same class. They are different code paths — SQL in the migration, Kotlin in the archive reader
     * — so nothing but this makes them agree.
     */
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

    /**
     * The upgrade path and the fresh-install path must build the same `cgm_sample_raw`. Room builds
     * one from [CgmRawSampleEntity]; the migration builds the other by hand. Add a field to the
     * entity without amending the DDL and a fresh install gets the column while an upgrade does not
     * — and Room refuses to open the upgraded database at all.
     */
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

    /** The table both statements name, and the index name Room derives from `@Index("rxWallMs")`. */
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

    /** Re-running a migration must not fail on an object that already exists. */
    @Test
    fun `the raw-sample DDL is idempotent`() {
        assertTrue(MigrationRunner.SQL_17_18_CREATE_TABLE.startsWith("CREATE TABLE IF NOT EXISTS "))
        assertTrue(MigrationRunner.SQL_17_18_CREATE_INDEX.startsWith("CREATE INDEX IF NOT EXISTS "))
    }

    /**
     * The same guard as the raw-sample one, for the same reason: `cgm_sensor_secret` is built by hand in
     * the migration and by Room on a fresh install, and nothing compares the two until an upgrade runs on
     * a real device — where a mismatch is a launch crash, not a lost row.
     */
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

    /**
     * The `ordinal` column, and its DEFAULT.
     *
     * Room compares column defaults as part of the schema, so the ALTER must carry the same one
     * [CgmSourceEntity] declares — otherwise the upgraded database differs from a fresh install in a way
     * Room refuses to open, on a store with no destructive fallback.
     */
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
        // The same sentinel is now also stated in Kotlin, for the descriptor the lists are built from,
        // and the two have to agree: a row carrying the column default must read back as unnumbered.
        assertTrue(
            "the SQL default and CgmSourceDescriptor.UNASSIGNED_ORDINAL must be the same value: $alter",
            alter.contains("DEFAULT ${com.t1dm.core.model.CgmSourceDescriptor.UNASSIGNED_ORDINAL}"),
        )
    }

    /**
     * The backfill must number every row exactly once, from zero, in the order the lists already use.
     *
     * Run here as SQL semantics rather than as a string match: the tiebreak on `sourceId` is what stops
     * two sensors sharing an `addedAtMs` from sharing a number, and `<=` on that branch is what makes each
     * row count itself so the sequence starts at zero. Both are easy to write the other way round and
     * neither would fail anywhere else.
     */
    @Test
    fun `the ordinal backfill numbers every row once, from zero, in list order`() {
        val rows = listOf(
            "v1:B" to 100L,
            "v1:C" to 100L, // a tie, broken on sourceId
            "v2:A" to 50L,  // a second vendor, and the oldest row
            "v1:D" to 300L,
        )
        // The statement's own arithmetic, evaluated in Kotlin against the same order.
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
     * The v19 statements name only tables and columns — no sensor vendor, family or model anywhere.
     *
     * Stated as an ALLOWLIST of the identifiers these statements may use, rather than as a list of names
     * they may not. A denylist would have to spell every vendor's name in a file that is shared verbatim
     * with the public branch, which is the opposite of what it is trying to enforce; and it would go stale
     * the moment a family were added. A migration describes what the schema became at a fixed point, so a
     * name that could be renamed later must not appear in one at all.
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
     * The v21 statements are additive and back-fill honestly.
     *
     * The backfill is the half worth pinning: `loggedAtMs` takes `updatedAt`, which is right only
     * because nothing could edit a pre-v21 row. A backfill to `0` would put every existing dose's
     * log-gap mark at the epoch and make `Rails.mandatoryConfirmation` fire forever.
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
     * The v23 statements are additive, and the verdict back-fills to the state that REFUSES.
     *
     * That default is the safety property: `ABSENT` means nobody has checked whether this adapter
     * still lets the model respond to insulin, and an unchecked adapter must be blocked exactly
     * like a checked-and-failed one. A default of `'PASS'` would silently attach every adapter
     * that predates the guard.
     */
    @Test
    fun `the v23 verdict back-fills to the state that refuses attach`() {
        val sql = MigrationRunner.SQL_22_23_LORA_GUARD_VERDICT
        assertTrue("must be additive: $sql", sql.contains("ADD COLUMN"))
        assertTrue("an unmeasured adapter must default to ABSENT: $sql", sql.contains("DEFAULT 'ABSENT'"))
        assertFalse("never PASS: $sql", sql.contains("PASS"))

        // The override and the history stamp are NULLABLE: absent is the ordinary state for both,
        // and a defaulted zero would read as "overridden at the epoch".
        for (sql in listOf(
            MigrationRunner.SQL_22_23_LORA_GUARD_OVERRIDE,
            MigrationRunner.SQL_22_23_LORA_HISTORY_MUTATED,
        )) {
            assertTrue("must be nullable: $sql", !sql.contains("NOT NULL"))
        }
    }

    private companion object {
        const val RAW_TABLE = "cgm_sample_raw"
        const val SECRET_TABLE = "cgm_sensor_secret"
        val BACKTICKED = Regex("`([A-Za-z_][A-Za-z0-9_]*)`")

        val ENTITY_FIELDS: Set<String> = CgmSourceEntity::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith('$') }
            .map { it.name }
            .toSet()
    }
}
