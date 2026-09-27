package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalFieldTest {

    @Test
    fun a_decimal_comma_is_a_point_not_dropped() {
        assertEquals("1.5", decimalFieldText("1,5"))
        assertEquals("12.5", decimalFieldText("12,5"))
        assertEquals(1.5, decimalFieldText("1,5").toDouble(), 0.0)
    }

    @Test
    fun a_decimal_point_and_digits_pass_through() {
        assertEquals("1.5", decimalFieldText("1.5"))
        assertEquals("42", decimalFieldText("42"))
    }

    @Test
    fun anything_else_is_dropped() {
        assertEquals("15", decimalFieldText("1 5 U"))
        assertEquals("2.5", decimalFieldText("-2,5g"))
    }

    @Test
    fun two_separators_fail_the_parse() {
        assertNull(decimalFieldText("1,5,2").toDoubleOrNull())
        assertNull(decimalFieldText("1.5,2").toDoubleOrNull())
    }
}
