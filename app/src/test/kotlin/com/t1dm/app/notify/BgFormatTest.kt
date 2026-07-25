package com.t1dm.app.notify

import com.t1dm.core.model.UnitSpace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * [BgFormat.value] in all three unit spaces — the formatter every headless chrome surface shares
 * (breadcrumb, ongoing notification, all four widget tiers) — plus [BgFormat.ageShort], the
 * second-resolution age the live notification ticks on (F1).
 *
 * `String.format` here takes the default locale (as the graph axis does), so the locale is pinned
 * for the duration rather than left to the host's.
 */
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

    /** Risk is signed about the neutral point (~112.5 mg/dL) and 2 dp deep, matching the graph's
     *  risk axis; the sign is what makes a lone number readable as hypo- or hyper-side. */
    @Test fun value_carries_the_risk_sign_and_the_axis_precision() {
        assertEquals("-3.16", BgFormat.value(20, UnitSpace.Kovatchev))
        assertEquals("-1.91", BgFormat.value(40, UnitSpace.Kovatchev))
        assertEquals("-0.88", BgFormat.value(70, UnitSpace.Kovatchev))
        assertEquals("-0.22", BgFormat.value(100, UnitSpace.Kovatchev))
        // The neutral point falls between two integer mg/dL, so neither side ever renders "-0.00".
        assertEquals("-0.01", BgFormat.value(112, UnitSpace.Kovatchev))
        assertEquals("+0.01", BgFormat.value(113, UnitSpace.Kovatchev))
        assertEquals("+0.88", BgFormat.value(180, UnitSpace.Kovatchev))
        assertEquals("+2.39", BgFormat.value(400, UnitSpace.Kovatchev))
        assertEquals("+2.81", BgFormat.value(500, UnitSpace.Kovatchev))
        // Both clamps hold: the transform is total on a nonsense reading, never NaN on the tile.
        assertEquals("-3.16", BgFormat.value(-5, UnitSpace.Kovatchev))
        assertEquals("+2.81", BgFormat.value(9999, UnitSpace.Kovatchev))
    }

    /** The number and its label are produced together; a mismatch is the defect this pair pins. */
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
