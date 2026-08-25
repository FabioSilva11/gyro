package com.gyrobridge.app.gesture

enum class GestureState { IDLE, QUEUED, DISPATCHING, COMPLETED, CANCELLED, ERROR }

data class GestureMetrics(
    val state: GestureState = GestureState.IDLE,
    val queued: Long = 0, val sent: Long = 0, val completed: Long = 0, val cancelled: Long = 0,
    val dropped: Long = 0, val averageDurationMs: Float = 0f, val effectiveHz: Float = 0f,
    val cancellationPercent: Float = 0f, val averageLatencyMs: Float = 0f,
    val p50LatencyMs: Float = 0f, val p90LatencyMs: Float = 0f, val p95LatencyMs: Float = 0f, val p99LatencyMs: Float = 0f,
    val multitouchAvailable: Boolean = true,
)

data class GestureRequest(val dx: Float, val dy: Float, val queuedAtNanos: Long = System.nanoTime())
