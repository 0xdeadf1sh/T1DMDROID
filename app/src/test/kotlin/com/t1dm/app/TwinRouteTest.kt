package com.t1dm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TwinRouteTest {

    @Test
    fun `twin leads the wheel and BG follows`() {
        assertEquals(listOf("twin", "dashboard"), destinations.take(2).map { it.route })
    }

    @Test
    fun `twin and BG each have their own trail`() {
        assertEquals(listOf(Crumb("Twin", null)), crumbsFor("twin", null))
        assertEquals(listOf(Crumb("Twin", null)), crumbsFor(null, null))
        assertEquals(listOf(Crumb("BG", null)), crumbsFor("dashboard", null))
    }
}
