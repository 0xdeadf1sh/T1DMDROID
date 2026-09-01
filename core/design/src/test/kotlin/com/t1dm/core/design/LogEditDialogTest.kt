package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogEditDialogTest {

    @Test
    fun `a blank index clears it rather than failing the field`() {
        assertTrue(giFieldValid(""))
        assertTrue(giFieldValid("   "))
        assertNull(giFieldOrNull(""))
    }

    @Test
    fun `an index outside 0 to 100 is refused, not clamped`() {
        assertFalse(giFieldValid("101"))
        assertFalse(giFieldValid("-1"))
        assertTrue(giFieldValid("0"))
        assertTrue(giFieldValid("100"))
        assertEquals(72.0, giFieldOrNull("72")!!, 0.0)
    }

    @Test
    fun `a fractional index is refused`() {
        // Whole points only; a stored fraction reads back as "GI 54.3".
        assertFalse(giFieldValid("72.5"))
        assertNull(giFieldOrNull("72.5"))
    }

    @Test
    fun `text that is not a number is refused`() {
        assertFalse(giFieldValid("high"))
        assertNull(giFieldOrNull("high"))
    }
}
