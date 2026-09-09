package com.t1dm.app.notify

import com.t1dm.core.model.UnitSpace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** `String.format` takes the default locale, so it is pinned for the duration. */
class BgFormatTest {

    private lateinit var previousLocale: Locale

    @Before fun pinLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() = Locale.setDefault(previousLocale)

    @Test fun value_renders_each_unit_space() {
        assertEquals("--", BgFormat.value(null, UnitSpace.MgDl))
        assertEquals("--", BgFormat.value(null, UnitSpace.Kovatchev))
        assertEquals("118", BgFormat.value(118, UnitSpace.MgDl))
        assertEquals("6.5", BgFormat.value(118, UnitSpace.MmolL))
        assertEquals("+0.09", BgFormat.value(118, UnitSpace.Kovatchev))
    }

    /** Risk is signed about the neutral point (~112.5 mg/dL), 2 dp, as the graph's risk axis is. */
    @Test fun value_carries_the_risk_sign_and_the_axis_precision() {
        assertEquals("-3.16", BgFormat.value(20, UnitSpace.Kovatchev))
        assertEquals("-1.91", BgFormat.value(40, UnitSpace.Kovatchev))
        assertEquals("-0.88", BgFormat.value(70, UnitSpace.Kovatchev))
        assertEquals("-0.22", BgFormat.value(100, UnitSpace.Kovatchev))
        // The neutral point falls between two integer mg/dL, so neither side renders "-0.00".
        assertEquals("-0.01", BgFormat.value(112, UnitSpace.Kovatchev))
        assertEquals("+0.01", BgFormat.value(113, UnitSpace.Kovatchev))
        assertEquals("+0.88", BgFormat.value(180, UnitSpace.Kovatchev))
        assertEquals("+2.39", BgFormat.value(400, UnitSpace.Kovatchev))
        assertEquals("+2.81", BgFormat.value(500, UnitSpace.Kovatchev))
        // Clamped both ends: never NaN on the tile.
        assertEquals("-3.16", BgFormat.value(-5, UnitSpace.Kovatchev))
        assertEquals("+2.81", BgFormat.value(9999, UnitSpace.Kovatchev))
    }

    /** Signed values are untouched; "--" reads as signed, so it never gains a pad either. */
    @Test fun valueSignAligned_pads_only_the_unsigned_units() {
        assertEquals("+0.09", BgFormat.valueSignAligned(118, UnitSpace.Kovatchev))
        assertEquals("-0.88", BgFormat.valueSignAligned(70, UnitSpace.Kovatchev))
        assertEquals("--", BgFormat.valueSignAligned(null, UnitSpace.MgDl))
        assertEquals(0x2007, BgFormat.valueSignAligned(118, UnitSpace.MgDl)[0].code)
        assertEquals("118", BgFormat.valueSignAligned(118, UnitSpace.MgDl).substring(1))
        assertEquals("6.5", BgFormat.valueSignAligned(118, UnitSpace.MmolL).substring(1))
    }

    @Test fun kovatchev_value_and_label_agree() {
        assertEquals("risk", BgFormat.unitLabel(UnitSpace.Kovatchev))
        assertEquals("mg/dL", BgFormat.unitLabel(UnitSpace.MgDl))
        assertEquals("mmol/L", BgFormat.unitLabel(UnitSpace.MmolL))
    }

    @Test fun ageShort_covers_boundaries() {
        assertEquals("just now", BgFormat.ageShort(0L))
        assertEquals("5s ago", BgFormat.ageShort(5_000L))
        assertEquals("59s ago", BgFormat.ageShort(59_000L))
        assertEquals("1m ago", BgFormat.ageShort(60_000L))
        assertEquals("59m ago", BgFormat.ageShort(3_540_000L))
        assertEquals("1h 0m ago", BgFormat.ageShort(3_600_000L))
        assertEquals("1h 30m ago", BgFormat.ageShort(5_400_000L))
    }
}
