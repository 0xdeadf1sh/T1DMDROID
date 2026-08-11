package com.t1dm.data.db

import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.legacySensorModelIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds `MIGRATION_10_11`'s frozen SQL against the constants the running app uses.
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
}
