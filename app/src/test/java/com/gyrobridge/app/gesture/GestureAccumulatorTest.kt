package com.gyrobridge.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureAccumulatorTest {
    @Test fun `busy movements are merged`() { val a=GestureAccumulator(100f);a.add(GestureRequest(2f,0f,1));a.add(GestureRequest(3f,0f,2));a.add(GestureRequest(4f,0f,3));assertEquals(9f,a.take()!!.dx,0f);assertNull(a.take()) }
    @Test fun `overflow is clamped`() { val a=GestureAccumulator(10f);repeat(5){a.add(GestureRequest(7f,-7f))};val r=a.take()!!;assertEquals(10f,r.dx,0f);assertEquals(-10f,r.dy,0f) }
    @Test fun `subpixel movements are retained until dispatchable`() {
        val accumulator = GestureAccumulator(100f)
        accumulator.add(GestureRequest(.2f, 0f, 1))
        assertNull(accumulator.take(.5f))
        accumulator.add(GestureRequest(.35f, 0f, 2))
        assertEquals(.55f, accumulator.take(.5f)!!.dx, .0001f)
        assertNull(accumulator.take())
    }
}
