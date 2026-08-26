package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.PhysicalMovementConfig
import com.gyrobridge.app.domain.model.PhysicalMovementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalMovementDetectorTest {
    private val config = PhysicalMovementConfig(
        enabled = true,
        threshold = .25f,
        sensitivity = 1f,
        minimumActiveMs = 150L,
        stopTimeoutMs = 300L,
    )

    @Test fun `sustained forward signal activates forward`() {
        val detector = PhysicalMovementDetector(config)
        repeat(8) { index -> detector.process(PhysicalMovementInput(.8f, index % 3 == 0, index * 50_000_000L)) }
        assertEquals(PhysicalMovementState.FORWARD, detector.output.state)
        assertTrue(detector.output.confidence > 0f)
    }

    @Test fun `sustained backward signal activates backward`() {
        val detector = PhysicalMovementDetector(config)
        repeat(8) { index -> detector.process(PhysicalMovementInput(-.8f, index % 3 == 0, index * 50_000_000L)) }
        assertEquals(PhysicalMovementState.BACKWARD, detector.output.state)
    }

    @Test fun `alternating hand tremor remains stationary`() {
        val detector = PhysicalMovementDetector(config)
        repeat(20) { index -> detector.process(PhysicalMovementInput(if (index % 2 == 0) .35f else -.35f, false, index * 20_000_000L)) }
        assertEquals(PhysicalMovementState.STATIONARY, detector.output.state)
    }

    @Test fun `short impulse does not activate walking`() {
        val detector = PhysicalMovementDetector(config)
        detector.process(PhysicalMovementInput(2f, false, 0L))
        repeat(10) { index -> detector.process(PhysicalMovementInput(0f, false, (index + 1) * 20_000_000L)) }
        assertEquals(PhysicalMovementState.STATIONARY, detector.output.state)
    }

    @Test fun `walking returns to stationary after timeout`() {
        val detector = PhysicalMovementDetector(config)
        repeat(8) { index -> detector.process(PhysicalMovementInput(.8f, true, index * 50_000_000L)) }
        assertEquals(PhysicalMovementState.FORWARD, detector.output.state)
        detector.process(PhysicalMovementInput(0f, false, 800_000_000L))
        assertEquals(PhysicalMovementState.STATIONARY, detector.output.state)
    }
}
