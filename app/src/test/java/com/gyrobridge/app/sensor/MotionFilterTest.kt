package com.gyrobridge.app.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionFilterTest {
    @Test fun `ema and low pass converge`() { val f=EmaFilter(.5f);assertEquals(0f,f.apply(0f,1),0f);assertEquals(5f,f.apply(10f,2),.001f);assertEquals(7.5f,f.apply(10f,3),.001f) }
    @Test fun `one euro output remains finite`() { val f=OneEuroFilter(1f,.04f,1f);repeat(100){ assertTrue(f.apply(it.toFloat(),1_000_000L+it*10_000_000L).isFinite()) } }
    @Test fun `adaptive filter responds faster to large motion`() { val f=AdaptiveFilter(.1f);f.apply(0f,1);val small=f.apply(.1f,2);val large=f.apply(10f,3);assertTrue(large-small>1f) }
}
