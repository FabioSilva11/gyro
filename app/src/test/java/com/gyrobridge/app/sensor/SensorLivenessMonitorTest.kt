package com.gyrobridge.app.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLivenessMonitorTest {
    @Test
    fun `requests recovery when a started sensor produces no samples`() {
        val monitor = SensorLivenessMonitor(timeoutNanos = 1_000_000_000L)
        monitor.onSensorStarted(100L)

        assertFalse(monitor.shouldRecover(999_999_999L))
        assertTrue(monitor.shouldRecover(1_000_000_100L))
    }

    @Test
    fun `fresh samples postpone recovery`() {
        val monitor = SensorLivenessMonitor(timeoutNanos = 1_000_000_000L)
        monitor.onSensorStarted(100L)
        monitor.onSample(800_000_000L)

        assertFalse(monitor.shouldRecover(1_700_000_000L))
        assertTrue(monitor.shouldRecover(1_800_000_000L))
    }

    @Test
    fun `only one recovery is requested until sensor restarts`() {
        val monitor = SensorLivenessMonitor(timeoutNanos = 1_000_000_000L)
        monitor.onSensorStarted(100L)

        assertTrue(monitor.shouldRecover(1_000_000_100L))
        assertFalse(monitor.shouldRecover(3_000_000_000L))

        monitor.onSensorStarted(3_000_000_000L)
        assertTrue(monitor.shouldRecover(4_000_000_000L))
    }
}
