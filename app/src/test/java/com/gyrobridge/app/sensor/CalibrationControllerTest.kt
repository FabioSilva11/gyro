package com.gyrobridge.app.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationControllerTest {
    @Test fun `movement restarts the stable interval`() {
        val controller = CalibrationController(stableForNanos = 300_000_000L, timeoutNanos = 3_000_000_000L)
        controller.begin(0L)

        assertFalse(controller.onSample(stable = true, nowNanos = 200_000_000L).captureReference)
        assertFalse(controller.onSample(stable = false, nowNanos = 250_000_000L).captureReference)
        assertFalse(controller.onSample(stable = true, nowNanos = 400_000_000L).captureReference)
        assertTrue(controller.onSample(stable = true, nowNanos = 560_000_000L).captureReference)
    }

    @Test fun `timeout captures even without a stable interval`() {
        val controller = CalibrationController(stableForNanos = 300_000_000L, timeoutNanos = 1_000_000_000L)
        controller.begin(100L)

        assertTrue(controller.onSample(stable = false, nowNanos = 1_000_000_101L).captureReference)
    }

    @Test fun `capture is emitted only once`() {
        val controller = CalibrationController(stableForNanos = 10L, timeoutNanos = 100L)
        controller.begin(0L)

        assertTrue(controller.onSample(stable = true, nowNanos = 11L).captureReference)
        assertFalse(controller.onSample(stable = true, nowNanos = 20L).captureReference)
    }
}
