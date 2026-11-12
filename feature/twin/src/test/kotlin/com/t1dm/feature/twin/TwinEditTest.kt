package com.t1dm.feature.twin

import com.t1dm.core.model.TwinScan
import com.t1dm.core.model.TwinSource
import com.t1dm.core.model.TwinTrait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TwinEditTest {

    private val scan = TwinScan(7, 100, TwinSource.MOCK, mapOf("height_cm" to 170.0, "weight_kg" to 70.0))

    @Test fun `no edits is the scan itself`() {
        assertSame(scan, effectiveScan(scan, emptyMap(), emptyMap()))
    }

    @Test fun `a draft beats a stored edit, which beats the scan`() {
        val e = effectiveScan(scan, mapOf("height_cm" to 180.0, "weight_kg" to 80.0), mapOf("height_cm" to 190.0))!!
        assertEquals(190.0, e[TwinTrait.HEIGHT]!!, 0.0)
        assertEquals(80.0, e[TwinTrait.WEIGHT]!!, 0.0)
        assertEquals(scan.id, e.id)
    }

    @Test fun `edits need a scan to land on`() {
        assertNull(effectiveScan(null, mapOf("height_cm" to 180.0), emptyMap()))
    }

    @Test fun `every editable trait drives a morph`() {
        val drives = setOf(TwinTrait.HEIGHT, TwinTrait.WEIGHT, TwinTrait.AGE, TwinTrait.SEX, TwinTrait.MUSCLE)
        assertEquals(drives, EDITABLE.toSet())
    }
}
