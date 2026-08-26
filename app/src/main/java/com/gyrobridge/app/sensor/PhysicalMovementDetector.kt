package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.PhysicalMovementConfig
import com.gyrobridge.app.domain.model.PhysicalMovementState
import com.gyrobridge.app.domain.model.sanitized
import kotlin.math.abs

data class PhysicalMovementInput(
    val forwardAcceleration: Float,
    val stepDetected: Boolean,
    val timestampNanos: Long,
    val stepSensorAvailable: Boolean = false,
)

data class PhysicalMovementOutput(
    val state: PhysicalMovementState = PhysicalMovementState.STATIONARY,
    val confidence: Float = 0f,
    val forwardSignal: Float = 0f,
    val pdrPositionMeters: Float = 0f,
    val stepCount: Int = 0,
    val timestampNanos: Long = 0L,
)

class PhysicalMovementDetector(config: PhysicalMovementConfig) {
    private val config = config.sanitized()
    private val window = FloatArray(WINDOW_SIZE)
    private var windowCount = 0
    private var windowIndex = 0
    private var candidate = PhysicalMovementState.STATIONARY
    private var candidateSinceNanos = 0L
    private var lastIntentNanos = 0L
    private var lastSampleNanos = 0L
    private var lastStepNanos = NO_TIMESTAMP
    private var oppositeSinceNanos = 0L
    private val pdr = PdrPositionEstimator(config.stepLengthMeters)
    var output = PhysicalMovementOutput()
        private set

    fun process(input: PhysicalMovementInput): PhysicalMovementOutput {
        if (lastSampleNanos != 0L && input.timestampNanos - lastSampleNanos >= config.stopTimeoutMs * 1_000_000L) {
            resetWindow()
            candidate = PhysicalMovementState.STATIONARY
            output = output.copy(
                state = PhysicalMovementState.STATIONARY,
                confidence = 0f,
                forwardSignal = 0f,
                timestampNanos = input.timestampNanos,
            )
        }
        lastSampleNanos = input.timestampNanos
        val scaled = input.forwardAcceleration * config.sensitivity
        if (input.stepDetected) lastStepNanos = input.timestampNanos
        window[windowIndex] = scaled
        windowIndex = (windowIndex + 1) % window.size
        windowCount = (windowCount + 1).coerceAtMost(window.size)
        var sum = 0f
        var absoluteSum = 0f
        for (index in 0 until windowCount) {
            sum += window[index]
            absoluteSum += abs(window[index])
        }
        val signal = if (windowCount == 0) 0f else sum / windowCount
        val activity = if (windowCount == 0) 0f else absoluteSum / windowCount
        val threshold = if (output.state == PhysicalMovementState.STATIONARY) config.threshold else config.threshold * EXIT_THRESHOLD_RATIO
        val stepRecent = lastStepNanos != NO_TIMESTAMP &&
            input.timestampNanos - lastStepNanos <= config.stopTimeoutMs * 1_000_000L
        val stepThreshold = (config.threshold * .35f).coerceAtLeast(.05f)
        val stepDirection = when {
            input.stepDetected && scaled >= stepThreshold && config.forwardEnabled -> PhysicalMovementState.FORWARD
            input.stepDetected && scaled <= -stepThreshold && config.backwardEnabled -> PhysicalMovementState.BACKWARD
            else -> PhysicalMovementState.STATIONARY
        }
        val signedDetection = when {
            signal >= threshold && config.forwardEnabled -> PhysicalMovementState.FORWARD
            signal <= -threshold && config.backwardEnabled -> PhysicalMovementState.BACKWARD
            else -> PhysicalMovementState.STATIONARY
        }
        val gaitActive = activity >= threshold && stepRecent
        val movementSensorGate = !input.stepSensorAvailable || stepRecent
        val detected = when {
            movementSensorGate && signedDetection != PhysicalMovementState.STATIONARY -> signedDetection
            movementSensorGate && stepDirection != PhysicalMovementState.STATIONARY -> stepDirection
            movementSensorGate && gaitActive && candidate != PhysicalMovementState.STATIONARY -> candidate
            else -> PhysicalMovementState.STATIONARY
        }

        if (detected != PhysicalMovementState.STATIONARY) {
            lastIntentNanos = input.timestampNanos
            if (candidate == PhysicalMovementState.STATIONARY) {
                candidate = detected
                candidateSinceNanos = input.timestampNanos
                oppositeSinceNanos = 0L
            } else if (candidate == detected) {
                oppositeSinceNanos = 0L
            } else if (signedDetection == detected && abs(signal) >= config.threshold) {
                if (oppositeSinceNanos == 0L) oppositeSinceNanos = input.timestampNanos
                if (input.timestampNanos - oppositeSinceNanos >= DIRECTION_SWITCH_HOLD_MS * 1_000_000L) {
                    candidate = detected
                    candidateSinceNanos = input.timestampNanos
                    oppositeSinceNanos = 0L
                }
            } else {
                oppositeSinceNanos = 0L
            }
            if (input.stepDetected && candidate != PhysicalMovementState.STATIONARY) {
                val position = pdr.onStep(candidate)
                output = output.copy(
                    pdrPositionMeters = position.positionMeters,
                    stepCount = position.stepCount,
                )
            }
            if (input.timestampNanos - candidateSinceNanos >= config.minimumActiveMs * 1_000_000L) {
                val stepBoost = if (input.stepDetected) .2f else 0f
                output = output.copy(
                    state = candidate,
                    confidence = (activity / config.threshold + stepBoost).coerceIn(0f, 1f),
                    forwardSignal = signal,
                    timestampNanos = input.timestampNanos,
                )
                return output
            }
        } else {
            if (candidate != PhysicalMovementState.STATIONARY &&
                input.timestampNanos - lastIntentNanos >= config.stopTimeoutMs * 1_000_000L
            ) {
                candidate = PhysicalMovementState.STATIONARY
                candidateSinceNanos = input.timestampNanos
            }
        }

        if (output.state != PhysicalMovementState.STATIONARY &&
            input.timestampNanos - lastIntentNanos >= config.stopTimeoutMs * 1_000_000L
        ) {
            resetWindow()
            output = output.copy(
                state = PhysicalMovementState.STATIONARY,
                confidence = 0f,
                forwardSignal = 0f,
                timestampNanos = input.timestampNanos,
            )
        } else if (output.state == PhysicalMovementState.STATIONARY) {
            output = output.copy(forwardSignal = signal, timestampNanos = input.timestampNanos)
        }
        return output
    }

    fun reset() {
        resetWindow()
        candidate = PhysicalMovementState.STATIONARY
        candidateSinceNanos = 0L
        lastIntentNanos = 0L
        lastSampleNanos = 0L
        lastStepNanos = NO_TIMESTAMP
        oppositeSinceNanos = 0L
        pdr.reset()
        output = PhysicalMovementOutput()
    }

    private fun resetWindow() {
        window.fill(0f)
        windowCount = 0
        windowIndex = 0
    }

    private companion object {
        const val WINDOW_SIZE = 6
        const val EXIT_THRESHOLD_RATIO = .6f
        const val DIRECTION_SWITCH_HOLD_MS = 700L
        const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}
