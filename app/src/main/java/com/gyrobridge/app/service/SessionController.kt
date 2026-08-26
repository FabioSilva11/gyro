package com.gyrobridge.app.service

import com.gyrobridge.app.domain.model.SessionError
import com.gyrobridge.app.domain.model.SessionStatus

sealed interface SessionEvent {
    data object Start : SessionEvent
    data class AccessibilityChanged(val available: Boolean) : SessionEvent
    data class SensorStarted(val success: Boolean) : SessionEvent
    data class ExplicitResume(val autoCalibrate: Boolean) : SessionEvent
    data object CalibrationCaptured : SessionEvent
    data object Pause : SessionEvent
    data class Failure(val error: SessionError) : SessionEvent
}

data class SessionSnapshot(
    val status: SessionStatus = SessionStatus.STOPPED,
    val error: SessionError? = null,
    val accessibilityAvailable: Boolean = false,
    val sensorAvailable: Boolean = false,
    val hasReference: Boolean = false,
    val resumeRequested: Boolean = false,
)

class SessionController {
    var snapshot: SessionSnapshot = SessionSnapshot()
        private set

    fun onEvent(event: SessionEvent): SessionSnapshot {
        snapshot = when (event) {
            SessionEvent.Start -> SessionSnapshot(status = SessionStatus.PAUSED)
            is SessionEvent.AccessibilityChanged -> onAccessibilityChanged(event.available)
            is SessionEvent.SensorStarted -> onSensorStarted(event.success)
            is SessionEvent.ExplicitResume -> onExplicitResume(event.autoCalibrate)
            SessionEvent.CalibrationCaptured -> onCalibrationCaptured()
            SessionEvent.Pause -> snapshot.copy(
                status = if (snapshot.accessibilityAvailable) SessionStatus.PAUSED else SessionStatus.WAITING_ACCESSIBILITY,
                error = null,
                resumeRequested = false,
            )
            is SessionEvent.Failure -> snapshot.copy(
                status = SessionStatus.ERROR,
                error = event.error,
                resumeRequested = false,
            )
        }
        return snapshot
    }

    private fun onAccessibilityChanged(available: Boolean): SessionSnapshot {
        if (!available) {
            return snapshot.copy(
                status = if (snapshot.status == SessionStatus.STOPPED) SessionStatus.STOPPED else SessionStatus.WAITING_ACCESSIBILITY,
                accessibilityAvailable = false,
                resumeRequested = false,
            )
        }
        return snapshot.copy(
            status = when {
                snapshot.status == SessionStatus.STOPPED -> SessionStatus.STOPPED
                snapshot.status == SessionStatus.ERROR -> SessionStatus.ERROR
                else -> SessionStatus.PAUSED
            },
            accessibilityAvailable = true,
            resumeRequested = false,
        )
    }

    private fun onSensorStarted(success: Boolean): SessionSnapshot = if (success) {
        snapshot.copy(
            status = if (snapshot.accessibilityAvailable) SessionStatus.PAUSED else SessionStatus.WAITING_ACCESSIBILITY,
            error = null,
            sensorAvailable = true,
        )
    } else {
        snapshot.copy(
            status = SessionStatus.ERROR,
            error = SessionError.SENSOR_START_FAILED,
            sensorAvailable = false,
            resumeRequested = false,
        )
    }

    private fun onExplicitResume(autoCalibrate: Boolean): SessionSnapshot = when {
        !snapshot.sensorAvailable -> snapshot.copy(status = SessionStatus.WAITING_SENSOR, resumeRequested = true)
        !snapshot.accessibilityAvailable -> snapshot.copy(status = SessionStatus.WAITING_ACCESSIBILITY, resumeRequested = false)
        autoCalibrate -> snapshot.copy(status = SessionStatus.CALIBRATING, error = null, resumeRequested = true)
        snapshot.hasReference -> snapshot.copy(status = SessionStatus.ACTIVE, error = null, resumeRequested = true)
        else -> snapshot.copy(status = SessionStatus.PAUSED, error = null, resumeRequested = true)
    }

    private fun onCalibrationCaptured(): SessionSnapshot = snapshot.copy(
        status = if (
            snapshot.resumeRequested && snapshot.sensorAvailable && snapshot.accessibilityAvailable
        ) SessionStatus.ACTIVE else if (snapshot.accessibilityAvailable) SessionStatus.PAUSED else SessionStatus.WAITING_ACCESSIBILITY,
        error = null,
        hasReference = true,
    )
}
