package com.gyrobridge.app.service

import com.gyrobridge.app.domain.model.SessionError
import com.gyrobridge.app.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {
    @Test
    fun `start always begins paused`() {
        val controller = SessionController()

        val snapshot = controller.onEvent(SessionEvent.Start)

        assertEquals(SessionStatus.PAUSED, snapshot.status)
        assertFalse(snapshot.resumeRequested)
    }

    @Test
    fun `never activates before explicit resume`() {
        val controller = readyController()

        val snapshot = controller.onEvent(SessionEvent.CalibrationCaptured)

        assertEquals(SessionStatus.PAUSED, snapshot.status)
        assertFalse(snapshot.resumeRequested)
    }

    @Test
    fun `auto calibration resumes only after reference capture`() {
        val controller = readyController()

        val calibrating = controller.onEvent(SessionEvent.ExplicitResume(autoCalibrate = true))
        val active = controller.onEvent(SessionEvent.CalibrationCaptured)

        assertEquals(SessionStatus.CALIBRATING, calibrating.status)
        assertEquals(SessionStatus.ACTIVE, active.status)
        assertTrue(active.hasReference)
    }

    @Test
    fun `manual resume without reference remains paused`() {
        val controller = readyController()

        val snapshot = controller.onEvent(SessionEvent.ExplicitResume(autoCalibrate = false))

        assertEquals(SessionStatus.PAUSED, snapshot.status)
        assertTrue(snapshot.resumeRequested)
    }

    @Test
    fun `accessibility disconnect cancels resume requirement`() {
        val controller = readyController()
        controller.onEvent(SessionEvent.CalibrationCaptured)
        controller.onEvent(SessionEvent.ExplicitResume(autoCalibrate = false))

        val disconnected = controller.onEvent(SessionEvent.AccessibilityChanged(false))
        val reconnected = controller.onEvent(SessionEvent.AccessibilityChanged(true))

        assertEquals(SessionStatus.WAITING_ACCESSIBILITY, disconnected.status)
        assertFalse(disconnected.resumeRequested)
        assertEquals(SessionStatus.PAUSED, reconnected.status)
    }

    @Test
    fun `sensor failure is an explicit error`() {
        val controller = SessionController()
        controller.onEvent(SessionEvent.Start)

        val snapshot = controller.onEvent(SessionEvent.SensorStarted(false))

        assertEquals(SessionStatus.ERROR, snapshot.status)
        assertEquals(SessionError.SENSOR_START_FAILED, snapshot.error)
    }

    @Test
    fun `lost sensor pauses an active session and remembers resume intent`() {
        val controller = readyController()
        controller.onEvent(SessionEvent.ExplicitResume(autoCalibrate = true))
        controller.onEvent(SessionEvent.CalibrationCaptured)

        val snapshot = controller.onEvent(SessionEvent.SensorLost)

        assertEquals(SessionStatus.WAITING_SENSOR, snapshot.status)
        assertFalse(snapshot.sensorAvailable)
        assertTrue(snapshot.resumeRequested)
    }

    private fun readyController() = SessionController().apply {
        onEvent(SessionEvent.Start)
        onEvent(SessionEvent.AccessibilityChanged(true))
        onEvent(SessionEvent.SensorStarted(true))
    }
}
