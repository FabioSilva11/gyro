package com.gyrobridge.app.core

import android.app.Application
import com.gyrobridge.app.data.repository.ProfileRepository
import com.gyrobridge.app.domain.model.ControlProfile
import com.gyrobridge.app.domain.model.PhysicalMovementState
import com.gyrobridge.app.domain.model.SessionError
import com.gyrobridge.app.domain.model.SessionStatus
import com.gyrobridge.app.gesture.GestureMetrics
import com.gyrobridge.app.profile.ProfileManager
import com.gyrobridge.app.sensor.MotionOutput
import com.gyrobridge.app.sensor.OrientationSample
import com.gyrobridge.app.sensor.SensorInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppGraph {
    lateinit var repository: ProfileRepository; private set
    lateinit var profileManager: ProfileManager; private set
    val runtime = RuntimeState()

    fun initialize(application: Application) {
        if (::repository.isInitialized) return
        repository = ProfileRepository(application)
        profileManager = ProfileManager(application, repository)
    }
}

class RuntimeState {
    private val _sessionActive = MutableStateFlow(false)
    private val _sessionPaused = MutableStateFlow(false)
    private val _activeProfile = MutableStateFlow<ControlProfile?>(null)
    private val _orientation = MutableStateFlow(OrientationSample())
    private val _motion = MutableStateFlow(MotionOutput())
    private val _sensorInfo = MutableStateFlow(SensorInfo())
    private val _gestureMetrics = MutableStateFlow(GestureMetrics())
    private val _foregroundPackage = MutableStateFlow<String?>(null)
    private val _overlayStatus = MutableStateFlow(OverlayStatus())
    private val _calibrating = MutableStateFlow(false)
    private val _autoDetectActive = MutableStateFlow(false)
    private val _a11yAvailable = MutableStateFlow(true)
    private val _sessionStatus = MutableStateFlow(SessionStatus.STOPPED)
    private val _sessionError = MutableStateFlow<SessionError?>(null)
    private val _physicalMovementState = MutableStateFlow(PhysicalMovementState.STATIONARY)

    val sessionActive = _sessionActive.asStateFlow(); val sessionPaused = _sessionPaused.asStateFlow()
    val activeProfile = _activeProfile.asStateFlow(); val orientation = _orientation.asStateFlow()
    val motion = _motion.asStateFlow(); val sensorInfo = _sensorInfo.asStateFlow()
    val gestureMetrics = _gestureMetrics.asStateFlow(); val foregroundPackage = _foregroundPackage.asStateFlow()
    val overlayStatus = _overlayStatus.asStateFlow()
    val calibrating = _calibrating.asStateFlow()
    val autoDetectActive = _autoDetectActive.asStateFlow()
    val a11yAvailable = _a11yAvailable.asStateFlow()
    val sessionStatus = _sessionStatus.asStateFlow()
    val sessionError = _sessionError.asStateFlow()
    val physicalMovementState = _physicalMovementState.asStateFlow()

    fun setSession(active: Boolean, paused: Boolean = false) { _sessionActive.value = active; _sessionPaused.value = paused }
    fun setPaused(value: Boolean) { _sessionPaused.value = value }
    fun setProfile(value: ControlProfile?) { _activeProfile.value = value }
    fun setOrientation(value: OrientationSample) { _orientation.value = value }
    fun setMotion(value: MotionOutput) { _motion.value = value }
    fun setSensorInfo(value: SensorInfo) { _sensorInfo.value = value }
    fun setGestureMetrics(value: GestureMetrics) { _gestureMetrics.value = value }
    fun setForegroundPackage(value: String?) { _foregroundPackage.value = value }
    fun setOverlayStatus(visible: Boolean, message: String? = null) { _overlayStatus.value = OverlayStatus(visible, message) }
    fun setCalibrating(value: Boolean) { _calibrating.value = value }
    fun setAutoDetectActive(value: Boolean) { _autoDetectActive.value = value }
    fun setA11yAvailable(value: Boolean) { _a11yAvailable.value = value }
    fun setSessionStatus(value: SessionStatus, error: SessionError? = null) { _sessionStatus.value = value; _sessionError.value = error }
    fun setPhysicalMovementState(value: PhysicalMovementState) { _physicalMovementState.value = value }
    fun reset() { setSession(false); setCalibrating(false); setProfile(null); setMotion(MotionOutput()); setOverlayStatus(false, "Sessão encerrada"); setAutoDetectActive(false); setA11yAvailable(true); setSessionStatus(SessionStatus.STOPPED); setPhysicalMovementState(PhysicalMovementState.STATIONARY) }
}

data class OverlayStatus(val visible: Boolean = false, val message: String? = null)
