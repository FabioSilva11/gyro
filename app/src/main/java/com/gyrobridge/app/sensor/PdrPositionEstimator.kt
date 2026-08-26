package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.PhysicalMovementState

data class PdrPosition(
    val positionMeters: Float = 0f,
    val stepCount: Int = 0,
)

class PdrPositionEstimator(private val stepLengthMeters: Float = .7f) {
    private var positionMeters = 0f
    private var stepCount = 0

    fun onStep(direction: PhysicalMovementState): PdrPosition {
        when (direction) {
            PhysicalMovementState.FORWARD -> {
                positionMeters += stepLengthMeters
                stepCount++
            }
            PhysicalMovementState.BACKWARD -> {
                positionMeters -= stepLengthMeters
                stepCount++
            }
            PhysicalMovementState.STATIONARY -> Unit
        }
        return PdrPosition(positionMeters, stepCount)
    }

    fun reset() {
        positionMeters = 0f
        stepCount = 0
    }
}
