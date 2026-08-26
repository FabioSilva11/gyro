package com.gyrobridge.app.sensor

import com.gyrobridge.app.domain.model.PhysicalMovementConfig
import com.gyrobridge.app.domain.model.PhysicalMovementState
import com.gyrobridge.app.domain.model.sanitized
import kotlin.math.abs

data class PhysicalMovementInput(
    val forwardAcceleration: Float,
    val stepDetected: Boolean,
    val timestampNanos: Long,
)

data class PhysicalMovementOutput(
    val state: PhysicalMovementState = PhysicalMovementState.STATIONARY,
    val confidence: Float = 0f,
    val forwardSignal: Float = 0f,
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
    var output = PhysicalMovementOutput()
        private set

    fun process(input: PhysicalMovementInput): PhysicalMovementOutput {
        if (lastSampleNanos != 0L && input.timestampNanos - lastSampleNanos >= config.stopTimeoutMs * 1_000_000L) {
            resetWindow()
            candidate = PhysicalMovementState.STATIONARY
            output = PhysicalMovementOutput(timestampNanos = input.timestampNanos)
        }
        lastSampleNanos = input.timestampNanos
        val scaled = input.forwardAcceleration * config.sensitivity
        window[windowIndex] = scaled
        windowIndex = (windowIndex + 1) % window.size
        windowCount = (windowCount + 1).coerceAtMost(window.size)
        var sum = 0f
        for (index in 0 until windowCount) sum += window[index]
        val signal = if (windowCount == 0) 0f else sum / windowCount
        val threshold = if (output.state == PhysicalMovementState.STATIONARY) config.threshold else config.threshold * EXIT_THRESHOLD_RATIO
        val detected = when {
            signal >= threshold && config.forwardEnabled -> PhysicalMovementState.FORWARD
            signal <= -threshold && config.backwardEnabled -> PhysicalMovementState.BACKWARD
            else -> PhysicalMovementState.STATIONARY
        }

        if (detected != PhysicalMovementState.STATIONARY) {
            lastIntentNanos = input.timestampNanos
            if (candidate != detected) {
                candidate = detected
                candidateSinceNanos = input.timestampNanos
            }
            if (input.timestampNanos - candidateSinceNanos >= config.minimumActiveMs * 1_000_000L) {
                val stepBoost = if (input.stepDetected) .2f else 0f
                output = PhysicalMovementOutput(
                    state = detected,
                    confidence = (abs(signal) / config.threshold + stepBoost).coerceIn(0f, 1f),
                    forwardSignal = signal,
                    timestampNanos = input.timestampNanos,
                )
                return output
            }
        } else {
            candidate = PhysicalMovementState.STATIONARY
            candidateSinceNanos = input.timestampNanos
        }

        if (output.state != PhysicalMovementState.STATIONARY &&
            input.timestampNanos - lastIntentNanos >= config.stopTimeoutMs * 1_000_000L
        ) {
            resetWindow()
            output = PhysicalMovementOutput(timestampNanos = input.timestampNanos)
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
    }
}
