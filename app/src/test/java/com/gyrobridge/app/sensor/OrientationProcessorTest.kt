package com.gyrobridge.app.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationProcessorTest {
    @Test fun `wrap 359 to 0 is one degree`() = assertEquals(1f, OrientationProcessor.normalizeAngle(0f - 359f), .0001f)
    @Test fun `wrap 0 to 359 is minus one degree`() = assertEquals(-1f, OrientationProcessor.normalizeAngle(359f - 0f), .0001f)
    @Test fun `normalization stays within signed half circle`() {
        assertEquals(-179f, OrientationProcessor.normalizeAngle(181f), .0001f)
        assertEquals(179f, OrientationProcessor.normalizeAngle(-181f), .0001f)
    }

    @Test fun `view yaw and pitch are extracted from forward vector`() {
        val yaw90 = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f)
        val pitch30 = floatArrayOf(1f, 0f, 0f, 0f, .8660254f, -.5f, 0f, .5f, .8660254f)
        assertEquals(90f, OrientationProcessor.viewAngles(yaw90).first, .001f)
        assertEquals(30f, OrientationProcessor.viewAngles(pitch30).second, .001f)
    }

    @Test fun `pure roll does not leak into camera yaw or pitch`() {
        val roll90 = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val (yaw, pitch, roll) = OrientationProcessor.viewAngles(roll90)
        assertEquals(0f, yaw, .001f)
        assertEquals(0f, pitch, .001f)
        assertEquals(90f, roll, .001f)
    }

    @Test fun `display rotation change does not discard captured reference`() {
        val processor = OrientationProcessor()
        processor.setCurrentMatrixForTest(floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f))
        assertTrue(processor.captureReference())

        processor.onDisplayRotationChanged(android.view.Surface.ROTATION_90)

        assertTrue(processor.hasReference())
    }
}
