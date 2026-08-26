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

    @Test fun `low amplitude step keeps the walking direction active`() {
        val detector = PhysicalMovementDetector(config.copy(threshold = .35f, stopTimeoutMs = 1_000L))
        repeat(8) { index ->
            detector.process(
                PhysicalMovementInput(
                    forwardAcceleration = if (index % 2 == 0) .13f else -.04f,
                    stepDetected = index == 0 || index == 6,
                    timestampNanos = index * 100_000_000L,
                ),
            )
        }
        assertEquals(PhysicalMovementState.FORWARD, detector.output.state)
    }

    @Test fun `walking remains active across normal step gap`() {
        val detector = PhysicalMovementDetector(config.copy(stopTimeoutMs = 1_000L))
        repeat(8) { index -> detector.process(PhysicalMovementInput(.8f, index == 0, index * 50_000_000L)) }
        assertEquals(PhysicalMovementState.FORWARD, detector.output.state)
        detector.process(PhysicalMovementInput(0f, false, 700_000_000L))
        assertEquals(PhysicalMovementState.FORWARD, detector.output.state)
    }

    @Test fun `oscillating forward gait remains forward after a detected step`() {
        val detector = PhysicalMovementDetector(config)
        val gait = floatArrayOf(.9f, .55f, -.45f, -.7f, .5f, .75f, -.4f, -.65f, .55f, .7f)

        gait.forEachIndexed { index, acceleration ->
            detector.process(
                PhysicalMovementInput(
                    forwardAcceleration = acceleration,
                    stepDetected = index == 0 || index == 5,
                    timestampNanos = index * 50_000_000L,
                ),
            )
        }

        assertEquals(PhysicalMovementState.FORWARD, detector.output.state)
    }

    @Test fun `oscillating backward gait remains backward after a detected step`() {
        val detector = PhysicalMovementDetector(config)
        val gait = floatArrayOf(-.9f, -.55f, .45f, .7f, -.5f, -.75f, .4f, .65f, -.55f, -.7f)

        gait.forEachIndexed { index, acceleration ->
            detector.process(
                PhysicalMovementInput(
                    forwardAcceleration = acceleration,
                    stepDetected = index == 0 || index == 5,
                    timestampNanos = index * 50_000_000L,
                ),
            )
        }

        assertEquals(PhysicalMovementState.BACKWARD, detector.output.state)
    }

    @Test fun `low amplitude walking does not flip direction on every foot impact`() {
        val detector = PhysicalMovementDetector(
            config.copy(threshold = .12f, minimumActiveMs = 50L, stopTimeoutMs = 1_000L),
        )
        val gait = floatArrayOf(.16f, .09f, -.10f, .08f, -.11f, .10f, -.09f, .09f, -.10f, .08f)

        val outputs = gait.mapIndexed { index, acceleration ->
            detector.process(
                PhysicalMovementInput(
                    forwardAcceleration = acceleration,
                    stepDetected = true,
                    timestampNanos = index * 100_000_000L,
                ),
            ).state
        }

        assertEquals(PhysicalMovementState.FORWARD, outputs.first { it != PhysicalMovementState.STATIONARY })
        assertTrue(outputs.drop(2).none { it == PhysicalMovementState.BACKWARD })
    }

    @Test fun `detector accumulates forward and backward PDR steps`() {
        val detector = PhysicalMovementDetector(
            config.copy(threshold = .12f, minimumActiveMs = 50L, stopTimeoutMs = 1_000L, stepLengthMeters = .7f),
        )

        val forward = detector.process(PhysicalMovementInput(.16f, true, 0L))
        val forwardAgain = detector.process(PhysicalMovementInput(.09f, true, 100_000_000L))

        assertEquals(2, forwardAgain.stepCount)
        assertEquals(1.4f, forwardAgain.pdrPositionMeters, .0001f)
        assertEquals(PhysicalMovementState.FORWARD, forwardAgain.state)
    }

    @Test fun `available step sensor blocks acceleration without a step event`() {
        val detector = PhysicalMovementDetector(config.copy(stopTimeoutMs = 1_000L))

        repeat(12) { index ->
            detector.process(
                PhysicalMovementInput(
                    forwardAcceleration = .9f,
                    stepDetected = false,
                    timestampNanos = index * 50_000_000L,
                    stepSensorAvailable = true,
                ),
            )
        }

        assertEquals(PhysicalMovementState.STATIONARY, detector.output.state)
        assertEquals(0, detector.output.stepCount)
    }
}
