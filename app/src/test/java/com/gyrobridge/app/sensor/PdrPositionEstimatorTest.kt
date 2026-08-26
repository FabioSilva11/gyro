package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.PhysicalMovementState
import org.junit.Assert.assertEquals
import org.junit.Test

class PdrPositionEstimatorTest {
    @Test fun `forward steps advance the relative position`() {
        val estimator = PdrPositionEstimator(stepLengthMeters = .7f)

        estimator.onStep(PhysicalMovementState.FORWARD)
        val position = estimator.onStep(PhysicalMovementState.FORWARD)

        assertEquals(1.4f, position.positionMeters, .0001f)
        assertEquals(2, position.stepCount)
    }

    @Test fun `backward steps subtract from the relative position`() {
        val estimator = PdrPositionEstimator(stepLengthMeters = .7f)

        estimator.onStep(PhysicalMovementState.FORWARD)
        val position = estimator.onStep(PhysicalMovementState.BACKWARD)

        assertEquals(0f, position.positionMeters, .0001f)
        assertEquals(2, position.stepCount)
    }

    @Test fun `stationary samples do not create a step`() {
        val estimator = PdrPositionEstimator(stepLengthMeters = .7f)

        val position = estimator.onStep(PhysicalMovementState.STATIONARY)

        assertEquals(0f, position.positionMeters, .0001f)
        assertEquals(0, position.stepCount)
    }
}
