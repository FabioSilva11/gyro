package com.gyrobridge.app.sensor

enum class CalibrationPhase { IDLE, WAITING_STABLE, CAPTURED }

data class CalibrationDecision(
    val phase: CalibrationPhase,
    val captureReference: Boolean = false,
)

class CalibrationController(
    private val stableForNanos: Long = 300_000_000L,
    private val timeoutNanos: Long = 3_000_000_000L,
) {
    var phase: CalibrationPhase = CalibrationPhase.IDLE
        private set
    private var startedAtNanos = 0L
    private var stableSinceNanos = 0L

    fun begin(nowNanos: Long) {
        phase = CalibrationPhase.WAITING_STABLE
        startedAtNanos = nowNanos
        stableSinceNanos = nowNanos
    }

    fun cancel() {
        phase = CalibrationPhase.IDLE
        startedAtNanos = 0L
        stableSinceNanos = 0L
    }

    fun onSample(stable: Boolean, nowNanos: Long): CalibrationDecision {
        if (phase != CalibrationPhase.WAITING_STABLE) return CalibrationDecision(phase)
        if (!stable) stableSinceNanos = nowNanos
        val stableElapsed = nowNanos - stableSinceNanos
        val totalElapsed = nowNanos - startedAtNanos
        if (stableElapsed >= stableForNanos || totalElapsed >= timeoutNanos) {
            phase = CalibrationPhase.CAPTURED
            return CalibrationDecision(phase, captureReference = true)
        }
        return CalibrationDecision(phase)
    }
}
