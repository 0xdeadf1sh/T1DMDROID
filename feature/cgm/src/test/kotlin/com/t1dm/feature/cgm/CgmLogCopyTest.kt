package com.t1dm.feature.cgm

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class CgmLogCopyTest {

    private fun marked(s: String) = buildAnnotatedString { markLine { append(s) } }

    /** As SelectionManager.getSelectedText builds it: each Text's slice, appended bare. */
    private fun copy(vararg slices: AnnotatedString) =
        buildAnnotatedString { slices.forEach { append(it) } }

    @Test
    fun `a selection across lines copies one line each`() {
        val a = marked("12:00:00.000 I RX abc")
        val hex = marked("0000  01 02\n0010  03")
        val b = marked("12:00:01.000 W LOG def")
        val got = joinMarkedLines(copy(a.subSequence(13, a.length), hex, b.subSequence(0, 12)))
        assertEquals("I RX abc\n0000  01 02\n0010  03\n12:00:01.000", got)
    }

    @Test
    fun `a selection inside one line is left as is`() {
        val a = marked("12:00:00.000 I RX abc")
        assertEquals("RX", joinMarkedLines(copy(a.subSequence(15, 17))))
    }
}
