package com.t1dm.data.db

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.legacySensorModelIdFor
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
    @Test
    fun `the v20 migration DDL is exactly what Room generates`() {
        val schema = File("schemas/com.t1dm.data.db.AppDatabase/20.json")
            .takeIf { it.exists() }
            ?: File("data/schemas/com.t1dm.data.db.AppDatabase/20.json")
        assertTrue("exported schema 20.json is missing: ${schema.absolutePath}", schema.exists())
        val text = schema.readText()

        fun createSql(table: String): String {
            // The entity's own `createSql`, with Room's placeholder resolved.
            val at = text.indexOf("\"tableName\": \"$table\"")
            assertTrue("entity $table is absent from the exported schema", at > 0)
            val key = "\"createSql\": \""
            val from = text.indexOf(key, at) + key.length
            val to = text.indexOf("\",", from)
            return text.substring(from, to)
                .replace("\\\"", "\"")
                .replace("\${TABLE_NAME}", table)
        }

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

    private companion object {
        const val RAW_TABLE = "cgm_sample_raw"
        val BACKTICKED = Regex("`([A-Za-z_][A-Za-z0-9_]*)`")
    }
}
