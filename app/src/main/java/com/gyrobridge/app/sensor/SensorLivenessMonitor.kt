package com.gyrobridge.app.sensor

class SensorLivenessMonitor(
    private val timeoutNanos: Long,
) {
    private var lastActivityNanos = 0L
    private var recoveryRequested = false

    fun onSensorStarted(nowNanos: Long) {
        lastActivityNanos = nowNanos
        recoveryRequested = false
    }

    fun onSample(nowNanos: Long) {
        lastActivityNanos = nowNanos
        recoveryRequested = false
    }

    fun shouldRecover(nowNanos: Long): Boolean {
        if (lastActivityNanos == 0L || recoveryRequested) return false
        if (nowNanos - lastActivityNanos < timeoutNanos) return false
        recoveryRequested = true
        return true
    }

    fun stop() {
        lastActivityNanos = 0L
        recoveryRequested = false
    }
}
