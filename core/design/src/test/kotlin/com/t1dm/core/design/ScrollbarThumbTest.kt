package com.t1dm.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollbarThumbTest {

    private val minThumb = 24f

    @Test
    fun `content that fits has no thumb`() {
        assertNull(scrollThumb(viewportPx = 1000f, maxScrollPx = 0, scrollPx = 0, minThumbPx = minThumb))
    }

    @Test
    fun `unmeasured scroll state has no thumb`() {
        // ScrollState.maxValue is Int.MAX_VALUE until the node has measured.
        assertTrue(isUnscrollable(Int.MAX_VALUE))
        assertNull(
            scrollThumb(viewportPx = 1000f, maxScrollPx = Int.MAX_VALUE, scrollPx = 0, minThumbPx = minThumb),
        )
        assertFalse(isUnscrollable(1))
    }

    @Test
    fun `thumb is the viewport's share of the content`() {
        val t = scrollThumb(viewportPx = 1000f, maxScrollPx = 2000, scrollPx = 0, minThumbPx = minThumb)!!
        assertEquals(1000f / 3f, t.heightPx, 0.01f)
        assertEquals(0f, t.topPx, 0.01f)
    }

    @Test
    fun `fully scrolled thumb lands flush with the track's bottom`() {
        val viewport = 1000f
        val t = scrollThumb(viewport, maxScrollPx = 2000, scrollPx = 2000, minThumbPx = minThumb)!!
        assertEquals(viewport, t.topPx + t.heightPx, 0.01f)
    }

    @Test
    fun `midpoint of the travel is the midpoint of the track`() {
        val viewport = 1000f
        val t = scrollThumb(viewport, maxScrollPx = 2000, scrollPx = 1000, minThumbPx = minThumb)!!
        assertEquals((viewport - t.heightPx) / 2f, t.topPx, 0.01f)
    }

    @Test
    fun `a very long document still gets a grabbable thumb, never taller than the track`() {
        val viewport = 300f
        val t = scrollThumb(viewport, maxScrollPx = 500_000, scrollPx = 250_000, minThumbPx = minThumb)!!
        assertEquals(minThumb, t.heightPx, 0.01f)
        assertTrue(t.topPx >= 0f)
        assertTrue(t.topPx + t.heightPx <= viewport + 0.01f)
    }

    @Test
    fun `a viewport shorter than the minimum thumb is not overrun`() {
        val viewport = 10f
        val t = scrollThumb(viewport, maxScrollPx = 900, scrollPx = 900, minThumbPx = minThumb)!!
        assertEquals(viewport, t.heightPx, 0.01f)
        assertEquals(0f, t.topPx, 0.01f)
    }

    @Test
    fun `an overscrolled offset cannot push the thumb past the track`() {
        val viewport = 1000f
        val t = scrollThumb(viewport, maxScrollPx = 2000, scrollPx = 2600, minThumbPx = minThumb)!!
        assertEquals(viewport, t.topPx + t.heightPx, 0.01f)
    }

    @Test
    fun `an unmeasured or non-overflowing lazy list has no thumb`() {
        assertNull(lazyThumb(400f, totalItems = 0, firstVisibleIndex = 0, visibleCount = 0, minThumbPx = minThumb))
        assertNull(lazyThumb(400f, totalItems = 8, firstVisibleIndex = 0, visibleCount = 8, minThumbPx = minThumb))
        assertNull(lazyThumb(0f, totalItems = 400, firstVisibleIndex = 0, visibleCount = 8, minThumbPx = minThumb))
    }

    @Test
    fun `lazy thumb is proportional to the visible item count and spans the track`() {
        val viewport = 340f
        val top = lazyThumb(viewport, totalItems = 400, firstVisibleIndex = 0, visibleCount = 10, minThumbPx = minThumb)!!
        assertEquals(minThumb, top.heightPx, 0.01f) // 10/400 of 340 px is below the floor
        assertEquals(0f, top.topPx, 0.01f)

        // 390 = total - visible: the last index with a full screen.
        val end = lazyThumb(viewport, totalItems = 400, firstVisibleIndex = 390, visibleCount = 10, minThumbPx = minThumb)!!
        assertEquals(viewport, end.topPx + end.heightPx, 0.01f)

        val half = lazyThumb(viewport, totalItems = 400, firstVisibleIndex = 195, visibleCount = 10, minThumbPx = minThumb)!!
        assertEquals((viewport - half.heightPx) / 2f, half.topPx, 0.01f)
    }
}
