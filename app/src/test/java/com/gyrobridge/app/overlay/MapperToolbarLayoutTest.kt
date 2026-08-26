package com.gyrobridge.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapperToolbarLayoutTest {
    @Test
    fun `landscape toolbar never crosses screen midpoint`() {
        val geometry = MapperToolbarLayout.calculate(screenWidthPx = 2712f, density = 3f)

        assertEquals(6, geometry.buttons.size)
        assertTrue(geometry.panelRight <= 2712f / 2f)
        assertTrue(geometry.buttons.all { it.right <= 2712f / 2f })
    }

    @Test
    fun `portrait toolbar remains inside half width and two compact rows`() {
        val geometry = MapperToolbarLayout.calculate(screenWidthPx = 1220f, density = 3f)

        assertTrue(geometry.panelRight <= 610f)
        assertEquals(geometry.buttons[0].top, geometry.buttons[2].top, .01f)
        assertTrue(geometry.buttons[3].top > geometry.buttons[0].bottom)
        assertTrue(geometry.panelBottom <= 120f * 3f)
    }
}
