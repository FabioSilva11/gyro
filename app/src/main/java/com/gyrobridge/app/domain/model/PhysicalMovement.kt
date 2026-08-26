package com.gyrobridge.app.domain.model

enum class PhysicalMovementState { STATIONARY, FORWARD, BACKWARD }

data class MovementZone(
    val centerX: Float = 0.20f,
    val centerY: Float = 0.72f,
    val radius: Float = 0.12f,
    val mappedDisplayRotation: DisplayRotation? = null,
)

data class PhysicalMovementConfig(
    val enabled: Boolean = true,
    val forwardEnabled: Boolean = true,
    val backwardEnabled: Boolean = true,
    val threshold: Float = 0.12f,
    val sensitivity: Float = 1f,
    val minimumActiveMs: Long = 180L,
    val stopTimeoutMs: Long = 1_000L,
    val joystickStrength: Float = 0.85f,
    val stepLengthMeters: Float = 0.70f,
    val zone: MovementZone = MovementZone(),
)

enum class SessionStatus {
    STOPPED,
    PAUSED,
    WAITING_ACCESSIBILITY,
    WAITING_SENSOR,
    CALIBRATING,
    ACTIVE,
    ERROR,
}

enum class SessionError {
    NO_SENSOR_AVAILABLE,
    SENSOR_START_FAILED,
    PHYSICAL_SENSOR_UNAVAILABLE,
}

fun MovementZone.sanitized(): MovementZone = copy(
    centerX = centerX.safe(0f, 1f, 0.20f),
    centerY = centerY.safe(0f, 1f, 0.72f),
    radius = radius.safe(0.01f, 0.50f, 0.12f),
)

fun PhysicalMovementConfig.sanitized(): PhysicalMovementConfig = copy(
    threshold = threshold.safe(0.01f, 20f, 0.35f),
    sensitivity = sensitivity.safe(0.01f, 10f, 1f),
    minimumActiveMs = minimumActiveMs.coerceIn(50L, 5_000L),
    stopTimeoutMs = stopTimeoutMs.coerceIn(50L, 10_000L),
    joystickStrength = joystickStrength.safe(0f, 1f, 0.85f),
    stepLengthMeters = stepLengthMeters.safe(0.20f, 1.50f, 0.70f),
    zone = zone.sanitized(),
)
