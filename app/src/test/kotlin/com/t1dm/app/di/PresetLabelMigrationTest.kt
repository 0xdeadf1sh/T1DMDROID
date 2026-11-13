package com.t1dm.app.di

import com.t1dm.core.nativecore.StubNativeCore
import com.t1dm.data.db.MigrationRunner
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetLabelMigrationTest {
    @Test
    fun `the v36 migration renames to exactly the catalogue's labels`() {
        assertEquals(
            StubNativeCore().insulinPresetCatalog().map { it.label },
            MigrationRunner.PRESET_LABELS_36,
        )
    }
}
