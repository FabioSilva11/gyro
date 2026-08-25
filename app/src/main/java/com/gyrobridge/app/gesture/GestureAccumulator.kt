package com.gyrobridge.app.gesture

import kotlin.math.hypot

class GestureAccumulator(private val maxDistance: Float) {
    private var dx = 0f; private var dy = 0f; private var oldestNanos = 0L

    @Synchronized fun add(request: GestureRequest) {
        if (oldestNanos == 0L) oldestNanos = request.queuedAtNanos
        dx = (dx + request.dx).coerceIn(-maxDistance, maxDistance)
        dy = (dy + request.dy).coerceIn(-maxDistance, maxDistance)
    }

    @Synchronized fun take(minDistance: Float = 0f): GestureRequest? {
        if (dx == 0f && dy == 0f) return null
        if (hypot(dx.toDouble(), dy.toDouble()) < minDistance.coerceAtLeast(0f)) return null
        return GestureRequest(dx, dy, oldestNanos).also { clear() }
    }

    @Synchronized fun clear() { dx = 0f; dy = 0f; oldestNanos = 0L }
    @Synchronized fun isEmpty() = dx == 0f && dy == 0f
}
